# Kafka 파티션 확장 트레이드오프 실험 가이드

## 목차
1. [실험 개요](#실험-개요)
2. [실험 전 준비사항](#실험-전-준비사항)
3. [실험 수행 절차](#실험-수행-절차)
4. [파티션별 설정 요약](#파티션별-설정-요약)
5. [결과 수집 방법](#결과-수집-방법)
6. [트러블슈팅](#트러블슈팅)

---

## 실험 개요

### 목적
**단일 파티션(Baseline)을 기준으로 파티션 수를 늘려가며 처리 속도와 순서 보장 간의 트레이드오프를 측정**

### 실험 시나리오
```
파티션 1개 (Baseline) ✅ 완료
  → TPS: 1,047
  → 순서 보장: 100%
  → CPU: 31%

파티션 2개 (첫 번째 실험)
  → 예상 TPS: 1,500~1,800 (1.5~1.8배)
  → 순서 보장: 파티션별로만 보장
  → CPU: 50~55%

파티션 3개
  → 예상 TPS: 2,000~2,500 (2~2.5배)
  → 순서 보장: 파티션별로만 보장
  → CPU: 60~65%

파티션 5개
  → 예상 TPS: 3,000~4,000 (3~4배)
  → 순서 보장: 파티션별로만 보장
  → CPU: 70~80%
  → ⚠️ DB 병목 시작 가능성

파티션 10개 (오버 프로비저닝)
  → 예상 TPS: 4,000~5,000 (4~5배, 수익 체감)
  → 순서 보장: 파티션별로만 보장
  → CPU: 90~100%
  → ⚠️ 스레드 경합 발생 예상
```

### 측정 지표
각 실험마다 다음을 측정합니다:
- ✅ **처리 속도**: TPS, 평균/P50/P95/P99 응답 시간
- ✅ **순서 보장율**: `order_mismatches` 카운트, 파티션별 순서 분석
- ✅ **데이터 정합성**: Kafka Offset vs DB Records
- ✅ **리소스 사용률**: CPU, 메모리, 네트워크 I/O
- ✅ **DB 성능**: Connection Pool 사용률, 쿼리 응답 시간
- ✅ **Kafka 안정성**: Consumer Lag, 리밸런싱 발생 여부

---

## 실험 전 준비사항

### 1. 환경 초기화

```bash
# 1. 기존 데이터 삭제 (선택적)
docker exec mysql mysql -uroot -ppassword -e "
  USE batch_kafka_db;
  TRUNCATE TABLE participation_history;
  DELETE FROM campaign WHERE id = 17;
"

# 2. 캠페인 생성 (재고 10만 개)
curl -X POST http://localhost:8080/api/campaigns \
  -H "Content-Type: application/json" \
  -d '{
    "name": "파티션 실험 캠페인",
    "description": "트레이드오프 측정",
    "totalStock": 100000,
    "startDate": "2026-01-14T00:00:00",
    "endDate": "2026-12-31T23:59:59"
  }'

# 캠페인 ID 확인 (예: 17번)
curl http://localhost:8080/api/campaigns/17
```

### 2. Docker 및 애플리케이션 상태 확인

```bash
# Docker 컨테이너 상태
docker ps
# kafka, mysql, batch-kafka-app 모두 Running 확인

# Kafka 브로커 상태
docker exec kafka kafka-broker-api-versions --bootstrap-server localhost:9092

# MySQL 연결 확인
docker exec mysql mysql -uroot -ppassword -e "SELECT 1"
```

---

## 실험 수행 절차

### 파티션 1개 실험 (Baseline) - 이미 완료 ✅

이미 완료된 실험이므로 건너뜁니다.

**결과 요약**:
- TPS: 1,047 req/s
- 순서 일치율: 100.00%
- CPU: 31%
- 데이터: 100,000건 완료

---

### 파티션 2개 실험

#### Step 1: 애플리케이션 중지
```bash
# Spring Boot 애플리케이션 중지
pkill -f "batch-kafka-system"
# 또는 IDE에서 Stop
```

#### Step 2: 기존 데이터 정리
```bash
# participation_history 테이블 초기화
docker exec mysql mysql -uroot -ppassword -e "
  USE batch_kafka_db;
  TRUNCATE TABLE participation_history;
"

# 캠페인 재고 초기화 (10만 개로 복구)
docker exec mysql mysql -uroot -ppassword -e "
  USE batch_kafka_db;
  UPDATE campaign SET total_stock = 100000, current_stock = 100000 WHERE id = 17;
"
```

#### Step 3: Kafka 파티션 변경
```bash
# 파티션 수를 2개로 증가
docker exec kafka kafka-topics \
  --bootstrap-server localhost:9092 \
  --alter \
  --topic campaign-participation-topic \
  --partitions 2

# 확인
docker exec kafka kafka-topics \
  --bootstrap-server localhost:9092 \
  --describe \
  --topic campaign-participation-topic
```

**출력 예시**:
```
Topic: campaign-participation-topic	PartitionCount: 2	...
```

#### Step 4: 애플리케이션 재시작 (p2 프로필)
```bash
# 프로필을 local,p2로 설정하여 시작
SPRING_PROFILES_ACTIVE=local,p2 ./gradlew bootRun

# 또는 IDE에서 VM Options:
# -Dspring.profiles.active=local,p2
```

**로그 확인**:
```
🔧 Producer 설정 - Buffer: 128MB, Batch: 64KB, Linger: 20ms, Compression: none, MaxBlock: 60000ms
🔧 Consumer 설정 - MaxPollRecords: 300, MaxPollInterval: 600000ms, SessionTimeout: 45000ms
🔧 Kafka 토픽 'campaign-participation-topic' 파티션 수 자동 감지: 2 → Consumer concurrency: 2
```

#### Step 5: K6 부하 테스트 실행
```bash
# K6 테스트 시작 (10만 건)
k6 run k6-load-test.js \
  -e TOTAL_REQUESTS=100000 \
  -e CAMPAIGN_ID=17 \
  -e PARTITIONS=2

# 또는 프론트엔드에서 시작
# http://localhost:8080/admin → Kafka 탭 → 총 요청 수: 100000, 파티션: 2
```

#### Step 6: 결과 수집

테스트 완료 후:

```bash
# 1. Kafka Consumer Group Lag 확인
docker exec kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --group campaign-participation-group \
  --describe

# 2. DB 레코드 수 확인
docker exec mysql mysql -uroot -ppassword -e "
  USE batch_kafka_db;
  SELECT COUNT(*) as total_records FROM participation_history WHERE campaign_id = 17;
"

# 3. 파티션별 분포 확인
docker exec mysql mysql -uroot -ppassword -e "
  USE batch_kafka_db;
  SELECT kafka_partition, COUNT(*) as count
  FROM participation_history
  WHERE campaign_id = 17
  GROUP BY kafka_partition;
"

# 4. 순서 일치율 확인
curl http://localhost:8080/api/stats/processing-sequence

# 5. CPU/메모리 사용률 (실시간 모니터링)
docker stats batch-kafka-app --no-stream
```

#### Step 7: 결과 저장

```bash
# 결과를 파일로 저장
mkdir -p experiment-results/p2

# K6 결과 저장 (이미 results/k6-*.json에 저장됨)
cp results/k6-*.json experiment-results/p2/

# 스크린샷 캡처
# - 프론트 대시보드 (TPS, 응답 시간)
# - Kafka 순서 분석 결과
# - CPU/메모리 그래프
# - DB 쿼리 결과
```

---

### 파티션 3개 실험

**Step 1~7**: 위와 동일하되, 다음만 변경:

```bash
# Step 3: 파티션 변경
docker exec kafka kafka-topics \
  --bootstrap-server localhost:9092 \
  --alter \
  --topic campaign-participation-topic \
  --partitions 3

# Step 4: 프로필 변경
SPRING_PROFILES_ACTIVE=local,p3 ./gradlew bootRun

# Step 5: K6 환경변수 변경
k6 run k6-load-test.js \
  -e TOTAL_REQUESTS=100000 \
  -e CAMPAIGN_ID=17 \
  -e PARTITIONS=3
```

---

### 파티션 5개 실험

```bash
# 파티션 변경
docker exec kafka kafka-topics \
  --bootstrap-server localhost:9092 \
  --alter \
  --topic campaign-participation-topic \
  --partitions 5

# 프로필 변경
SPRING_PROFILES_ACTIVE=local,p5 ./gradlew bootRun

# K6 테스트
k6 run k6-load-test.js \
  -e TOTAL_REQUESTS=100000 \
  -e CAMPAIGN_ID=17 \
  -e PARTITIONS=5
```

**⚠️ 주의**: 파티션 5개부터는 **DB 병목**이 발생할 수 있습니다.
- MySQL Connection Pool을 모니터링하세요
- 쿼리 응답 시간이 급증하면 정상입니다

---

### 파티션 10개 실험 (오버 프로비저닝)

```bash
# 파티션 변경
docker exec kafka kafka-topics \
  --bootstrap-server localhost:9092 \
  --alter \
  --topic campaign-participation-topic \
  --partitions 10

# 프로필 변경
SPRING_PROFILES_ACTIVE=local,p10 ./gradlew bootRun

# K6 테스트
k6 run k6-load-test.js \
  -e TOTAL_REQUESTS=100000 \
  -e CAMPAIGN_ID=17 \
  -e PARTITIONS=10
```

**⚠️ 주의**: 파티션 10개는 **매우 높은 리소스 사용**:
- CPU 90~100% 예상
- 컨텍스트 스위칭 오버헤드
- DB Connection Pool 경합
- **수익 체감 구간**: 5개 대비 2배 빠르지 않을 것

---

## 파티션별 설정 요약

| 파티션 | 프로필 | MAX_POLL_RECORDS | DB Pool | Buffer Memory | Linger MS | 예상 TPS | 예상 CPU |
|--------|--------|------------------|---------|---------------|-----------|----------|----------|
| **1개** | `local` | 500 | 20 | 128MB | 20ms | 1,047 | 31% |
| **2개** | `local,p2` | 300 | 25 | 128MB | 20ms | 1,500~1,800 | 50~55% |
| **3개** | `local,p3` | 250 | 30 | 192MB | 15ms | 2,000~2,500 | 60~65% |
| **5개** | `local,p5` | 200 | 40 | 256MB | 10ms | 3,000~4,000 | 70~80% |
| **10개** | `local,p10` | 100 | 60 | 512MB | 5ms | 4,000~5,000 | 90~100% |

### 설정 변경 철학

1. **MAX_POLL_RECORDS 감소**: 파티션이 늘면 각 Consumer 스레드의 부하를 줄여 타임아웃 방지
2. **DB Pool 증가**: Consumer 스레드가 늘면 동시 DB 접속 증가
3. **Buffer Memory 증가**: 파티션이 늘면 Producer가 더 많은 메모리 필요
4. **Linger MS 감소**: 파티션이 늘면 배치를 빨리 전송하여 전체 처리 속도 향상

---

## 결과 수집 방법

### 1. 성능 지표

#### TPS 및 응답 시간
```bash
# 프론트엔드 대시보드에서 확인
http://localhost:8080/admin

또는 K6 결과에서:
- TPS (throughput)
- 평균, P50, P95, P99 응답 시간
```

#### CPU/메모리 사용률
```bash
# 실시간 모니터링
docker stats batch-kafka-app

# 특정 시점 캡처
docker stats batch-kafka-app --no-stream
```

### 2. 순서 보장율

#### SQL 쿼리로 확인
```sql
-- 전체 순서 일치율
SELECT
  COUNT(*) - 1 as total_comparisons,
  SUM(CASE WHEN processing_sequence < next_processing_sequence
           AND kafka_timestamp > next_kafka_timestamp
      THEN 1 ELSE 0 END) as order_mismatches,
  100.0 * (1 - SUM(...) / NULLIF(COUNT(*) - 1, 0)) as order_accuracy_percent
FROM participation_history;

-- 파티션별 순서 일치율
SELECT
  kafka_partition,
  COUNT(*) as total,
  MIN(processing_sequence) as min_seq,
  MAX(processing_sequence) as max_seq
FROM participation_history
GROUP BY kafka_partition;

-- 파티션 간 순서 역전 확인
SELECT
  h1.kafka_partition as p1,
  h2.kafka_partition as p2,
  h1.processing_sequence as seq1,
  h2.processing_sequence as seq2,
  h1.kafka_timestamp as ts1,
  h2.kafka_timestamp as ts2
FROM participation_history h1
JOIN participation_history h2
  ON h1.kafka_timestamp > h2.kafka_timestamp
  AND h1.processing_sequence < h2.processing_sequence
WHERE h1.kafka_partition != h2.kafka_partition
LIMIT 10;
```

### 3. 데이터 정합성

```bash
# Kafka Offset 확인
docker exec kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --group campaign-participation-group \
  --describe

# DB 레코드 수 확인
docker exec mysql mysql -uroot -ppassword -e "
  SELECT COUNT(*) FROM participation_history WHERE campaign_id = 17;
"
```

### 4. 파티션 분포 분석

```sql
-- 파티션별 메시지 수
SELECT
  kafka_partition,
  COUNT(*) as message_count,
  ROUND(COUNT(*) * 100.0 / (SELECT COUNT(*) FROM participation_history), 2) as percentage
FROM participation_history
GROUP BY kafka_partition
ORDER BY kafka_partition;

-- 이상적: 각 파티션이 균등하게 분배 (편향 없음)
-- 파티션 2개: 각 50%
-- 파티션 3개: 각 33.3%
-- 파티션 5개: 각 20%
```

---

## 트러블슈팅

### 문제 1: 애플리케이션이 시작되지 않음

**증상**:
```
Failed to start bean 'kafkaListenerContainerFactory'
```

**원인**: 파티션 변경 후 Kafka 메타데이터가 업데이트되지 않음

**해결**:
```bash
# 1. Kafka 재시작
docker restart kafka

# 2. 30초 대기
sleep 30

# 3. 애플리케이션 재시작
SPRING_PROFILES_ACTIVE=local,p2 ./gradlew bootRun
```

---

### 문제 2: Consumer Lag이 계속 증가

**증상**:
```
LAG: 50000 (계속 증가)
```

**원인**: Consumer 처리 속도 < Producer 전송 속도

**해결**:
```bash
# 1. 프로필이 올바른지 확인
# application-pX.yml의 MAX_POLL_RECORDS가 너무 크지 않은지

# 2. DB Connection Pool 확인
docker exec mysql mysql -uroot -ppassword -e "SHOW PROCESSLIST;"

# 3. CPU 사용률 확인
docker stats batch-kafka-app

# 4. 필요시 파티션 감소 후 재시도
```

---

### 문제 3: 순서 일치율이 100%가 안 나옴 (파티션 1개일 때)

**증상**:
```
order_accuracy_percent: 99.5%
```

**원인**: 파티션이 실제로 1개가 아니거나, 리밸런싱 발생

**해결**:
```bash
# 1. 파티션 수 재확인
docker exec kafka kafka-topics \
  --bootstrap-server localhost:9092 \
  --describe \
  --topic campaign-participation-topic

# 2. 파티션이 1개가 아니면 삭제 후 재생성
docker exec kafka kafka-topics \
  --bootstrap-server localhost:9092 \
  --delete \
  --topic campaign-participation-topic

# 3. 애플리케이션 재시작 (자동 생성)
```

---

### 문제 4: 메모리 부족 (OOM)

**증상**:
```
java.lang.OutOfMemoryError: Java heap space
```

**원인**: 파티션 10개 + Producer Buffer 512MB = 메모리 초과

**해결**:
```bash
# JVM Heap 크기 증가
export JAVA_OPTS="-Xmx4g -Xms2g"
./gradlew bootRun
```

---

### 문제 5: DB Connection Pool 고갈

**증상**:
```
HikariPool - Connection is not available
```

**원인**: Consumer 스레드 수 > DB Pool Size

**해결**:
```yaml
# application-pX.yml 수정
spring:
  datasource:
    hikari:
      maximum-pool-size: 80  # 증가
```

---

## 실험 체크리스트

각 파티션 실험마다 다음을 확인하세요:

- [ ] 기존 데이터 삭제 완료
- [ ] 캠페인 재고 10만 개로 초기화
- [ ] Kafka 파티션 변경 완료
- [ ] 올바른 프로필로 애플리케이션 시작
- [ ] 로그에서 설정값 확인 (MAX_POLL_RECORDS, Buffer Memory 등)
- [ ] K6 테스트 완료 (100,000건)
- [ ] Consumer Lag = 0 확인
- [ ] DB 레코드 수 = 100,000 확인
- [ ] 순서 일치율 측정 완료
- [ ] CPU/메모리 사용률 기록
- [ ] 스크린샷 저장
- [ ] 결과를 experiment-results/pX/ 폴더에 저장

---

## 다음 단계

모든 실험 완료 후:

1. **결과 비교 표 작성**:
   - 파티션 수별 TPS 그래프
   - 파티션 수별 순서 일치율 그래프
   - 파티션 수별 CPU 사용률 그래프

2. **최적 파티션 수 결정**:
   - TPS와 순서 보장의 균형점 찾기
   - 비즈니스 요구사항에 따른 선택

3. **최종 보고서 작성**:
   - 단일 파티션 Baseline 보고서 (이미 완료)
   - 멀티 파티션 트레이드오프 보고서
   - 권장 아키텍처 제안

---

**실험 시작 날짜**: 2026-01-14
**Baseline 완료**: ✅ 파티션 1개 (TPS 1,047, 순서 100%)
**다음 실험**: 파티션 2개

Good luck! 🚀
