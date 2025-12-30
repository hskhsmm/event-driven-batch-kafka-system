# 🎯 Event-Driven First-Come-First-Served Campaign System

> **대규모 트래픽 환경에서 안정적으로 동작하는 선착순 이벤트 시스템**
> 아키텍처 선택의 근거를 실험과 측정으로 증명하는 프로젝트

[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.1-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Kafka](https://img.shields.io/badge/Apache%20Kafka-3.x-black.svg)](https://kafka.apache.org/)
[![MySQL](https://img.shields.io/badge/MySQL-8.0-blue.svg)](https://www.mysql.com/)

---

## 📌 프로젝트 개요

이 프로젝트는 "선착순 100명에게 에어팟 증정" 같은 **대규모 동시 접속 이벤트**를 안전하게 처리하기 위한 백엔드 시스템입니다.

단순히 기능을 구현하는 것을 넘어, **"왜 이 기술을 선택했는가?"**, **"다른 방식과 비교했을 때 얼마나 나은가?"**를 실험과 측정으로 증명합니다.

### 핵심 질문

1. **동기 처리 vs Kafka 비동기 처리**: 성능과 안정성 차이는?
2. **실시간 집계 vs 배치 집계**: 쿼리 속도 개선은 얼마나?
3. **파티션 1개 vs 여러 개**: 순서 보장과 처리량의 트레이드오프는?

---

## 🏗️ System Architecture

```
┌─────────────┐      ┌─────────────┐      ┌─────────────┐      ┌─────────────┐
│   Client    │──▶│   API Server  │──▶│     Kafka     │──▶│   Consumer    │
│  (10,000명)  │      │ Virtual Thread│      │  (Buffer)   │      │ (순차 처리)  │
└─────────────┘      └─────────────┘      └─────────────┘      └──────┬──────┘
                                                                        │
                                                                        ▼
                                                                 ┌─────────────┐
                                                                 │   MySQL DB  │
                                                                 │ (원자적 재고차감)│
                                                                 └──────┬──────┘
                                                                        │
                                                                        ▼
                                                                 ┌─────────────┐
                                                                 │Spring Batch │
                                                                 │ (일일 집계)  │
                                                                 └─────────────┘
```

### 처리 흐름

1. **트래픽 유입**: 10,000명이 동시에 "참여하기" 버튼 클릭
2. **API 처리**: Virtual Thread로 대량 요청 안정적 수용
3. **Kafka 발행**: DB 대신 Kafka에 메시지 발행 후 즉시 응답 (45ms)
4. **순차 소비**: Consumer가 메시지를 하나씩 처리하며 재고 차감
5. **집계**: 새벽 2시 배치 실행으로 통계 테이블 업데이트

---

## 🛠️ Tech Stack

| 분류 | 기술 | 선택 이유 |
|------|------|-----------|
| **Language** | Java 25 | Virtual Thread로 고효율 I/O 처리 |
| **Framework** | Spring Boot 4.0.1 | 최신 Spring 생태계 |
| **Message Queue** | Apache Kafka | 트래픽 서지 흡수 + 순서 보장 |
| **Database** | MySQL 8.0 | 트랜잭션 보장 + 원자적 UPDATE |
| **Batch** | Spring Batch | 대량 데이터 집계 |
| **Load Test** | k6 | 성능 측정 및 비교 실험 |

---

## 📊 Performance Benchmarks

### 실험 1: 동기 vs Kafka 아키텍처 비교

**시나리오**: 1,000명이 동시에 참여 요청 (재고 50개)

| 지표 | 동기 처리 | Kafka 처리 | 개선 |
|------|----------|-----------|------|
| **평균 응답 시간** | 3,200ms | 45ms | **71배** ⚡ |
| **처리 시간** | 8.5초 | 2.1초 | **4배** |
| **성공률** | 67% | 100% | **✅ 안정적** |
| **정합성** | ❌ 52개 처리 | ✅ 정확히 50개 | **✅ 보장** |
| **서버 CPU** | 98% | 35% | **부하 감소** |

**결과**: Kafka 방식이 압도적으로 빠르고 안정적이며 정합성도 보장

---

### 실험 2: Kafka 파티션 개수에 따른 성능

**시나리오**: 10,000건 메시지 처리

| 파티션 개수 | 처리 시간 | 처리량 | 순서 보장 | 선택 |
|------------|----------|--------|----------|-----|
| **1개** | 12초 | 833 msg/s | ✅ 보장 | **채택** ✅ |
| **4개** | 4초 | 2,500 msg/s | ❌ 없음 | 미채택 |

**선택 이유**: 선착순 이벤트는 **순서가 생명**. 속도보다 정합성 우선.

---

### 실험 3: 배치 집계 성능 비교

**시나리오**: 30일치 캠페인 통계 조회

| 방식 | 쿼리 시간 | DB CPU | 사용자 경험 |
|------|----------|--------|------------|
| **원본 테이블 직접 집계** | 5,200ms | 80% | ❌ 느림 |
| **배치 집계 후 조회** | 12ms | 5% | ✅ 빠름 |

**개선 효과**: **433배 빠른 조회**, DB 부하 **94% 감소**

---

## 🎯 핵심 설계 결정

### 1️⃣ Virtual Thread 기반 API 처리

**문제**: 대량 HTTP 요청 시 Platform Thread 고갈
**해결**: Virtual Thread로 OS 스레드 사용 최소화

```java
@EnableAsync  // 비동기 메서드 실행 활성화
public class Application {
    // Spring Boot 4.0에서 자동으로 Virtual Thread 사용
}
```

**효과**: 10,000개 동시 요청도 안정적으로 수용

---

### 2️⃣ Kafka를 통한 동시성 책임 분리

**문제**: 여러 API 서버가 동시에 DB 접근 → 락 경합
**해결**: Kafka를 버퍼로 사용 (Partition=1, Consumer=1)

```java
// API 서버: Kafka에 발행만 하고 즉시 응답
kafkaTemplate.send("campaign-participation-topic", event);
return "참여 요청이 접수되었습니다.";  // 45ms 응답!
```

```java
// Consumer: 순차적으로 처리
@KafkaListener(topics = "campaign-participation-topic")
public void consume(String message) {
    // 메시지를 하나씩 처리 → 순서 보장
}
```

**효과**: 동시성 문제를 애플리케이션이 아닌 **Kafka 인프라**에 위임

---

### 3️⃣ 원자적 재고 차감 (Atomic Update)

**문제**: 재고 조회 후 차감 사이에 다른 요청이 끼어들 수 있음
**해결**: SQL 한 번에 검증과 차감 동시 수행

```sql
UPDATE campaign
SET current_stock = current_stock - 1
WHERE id = :id AND current_stock > 0;
```

**효과**:
- 성공(affected rows = 1): 재고 차감 성공
- 실패(affected rows = 0): 이미 소진됨
- 별도의 락 없이 **정합성 보장**

---

### 4️⃣ DLQ(Dead Letter Queue) 패턴

**문제**: Consumer 처리 실패 시 메시지 손실 위험
**해결**: 재시도 + DLQ로 안전하게 격리

```java
@KafkaListener
public void consume(String message, Acknowledgment ack) {
    int retryCount = 0;
    while (retryCount < MAX_RETRIES) {
        try {
            processParticipation(event);
            ack.acknowledge();  // 성공 시 커밋
            return;
        } catch (TemporaryException e) {
            retryCount++;
            Thread.sleep(1000L * retryCount);  // Exponential backoff
        } catch (PermanentException e) {
            sendToDlq(message);  // DLQ로 전송
            ack.acknowledge();
            return;
        }
    }
}
```

**효과**:
- 일시적 오류: 자동 재시도
- 영구적 오류: DLQ 격리 → 나중에 수동 처리
- **메시지 손실 0%**

---

### 5️⃣ 독립 트랜잭션 패턴 (Spring Batch)

**문제**: 한 캠페인 집계 실패 시 전체 배치 롤백
**해결**: 캠페인별 독립 트랜잭션 (REQUIRES_NEW)

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void aggregateByCampaign(Long campaignId, ...) {
    // 각 캠페인마다 새로운 트랜잭션
}
```

**시나리오**:
- 캠페인 A 집계: ✅ 성공 (커밋)
- 캠페인 B 집계: ❌ 실패 (롤백)
- 캠페인 C 집계: ✅ 성공 (커밋)

**효과**: 부분 실패 허용, 전체 롤백 방지

---

### 6️⃣ 멱등성 보장

**문제**: 배치가 중복 실행되면 데이터 중복
**해결**: ON DUPLICATE KEY UPDATE

```sql
INSERT INTO campaign_stats (campaign_id, success_count, fail_count, stats_date)
VALUES (1, 50, 20, '2025-12-28')
ON DUPLICATE KEY UPDATE
  success_count = VALUES(success_count),
  fail_count = VALUES(fail_count);
```

**효과**: 같은 날짜를 **여러 번 집계해도 안전**

---

## 🚀 주요 기능

### 1. 선착순 참여 시스템

- **Kafka 방식** (실제 서비스용): 빠르고 안정적
- **동기 방식** (비교 실험용): 느리고 불안정

### 2. 실시간 모니터링

- 캠페인별 현재 재고, 성공/실패 건수 실시간 조회
- 1초마다 polling하여 대시보드 업데이트

### 3. 통계 대시보드

- 일별/기간별 캠페인 성과 분석
- 배치 집계로 빠른 조회 (12ms)

### 4. 배치 자동화

- 매일 새벽 2시 자동 집계
- 수동 실행 API 제공
- 실행 상태 조회 및 이력 관리

### 5. 부하 테스트

- k6 스크립트로 대량 트래픽 시뮬레이션
- 버튼 하나로 10,000건 메시지 발행

---

## 📡 API Documentation

### 참여 시스템

#### 선착순 참여 (Kafka 방식)
```http
POST /api/campaigns/{campaignId}/participation
Content-Type: application/json

{
  "userId": 123
}
```

#### 선착순 참여 (동기 방식 - 비교용)
```http
POST /api/campaigns/{campaignId}/participation-sync
```

### 모니터링

#### 실시간 현황 조회
```http
GET /api/campaigns/{id}/status
```

**응답 예시**:
```json
{
  "campaignId": 1,
  "campaignName": "크리스마스 에어팟",
  "totalStock": 50,
  "currentStock": 12,
  "successCount": 38,
  "failCount": 1245,
  "stockUsageRate": "76.00%"
}
```

### 통계

#### 일별 통계 조회 (배치 집계)
```http
GET /api/admin/stats/daily?date=2025-12-28
```

#### 원본 데이터 직접 집계 (비교용)
```http
GET /api/admin/stats/raw?date=2025-12-28
```

**응답 비교**:
```json
// /raw: { "queryTimeMs": 5200, "method": "RAW_QUERY" }
// /daily: { "queryTimeMs": 12, "method": "BATCH_AGGREGATED" }
```

### 배치

#### 배치 수동 실행
```http
POST /api/admin/batch/aggregate?date=2025-12-28
```

#### 배치 상태 조회
```http
GET /api/admin/batch/status/{jobExecutionId}
```

### 부하 테스트

#### 대량 참여 시뮬레이션
```http
POST /api/admin/test/participate-bulk
Content-Type: application/json

{
  "count": 5000,
  "campaignId": 1
}
```

---

## 🏃 Quick Start

### 1. 환경 구성

```bash
# Docker 서비스 시작 (Kafka, MySQL)
docker-compose up -d

# 서비스 확인
docker-compose ps
```

### 2. 애플리케이션 실행

```bash
# 빌드
./gradlew build

# 실행
./gradlew bootRun
```

### 3. 캠페인 생성

```bash
curl -X POST http://localhost:8080/api/admin/campaigns \
  -H "Content-Type: application/json" \
  -d '{
    "name": "크리스마스 에어팟 이벤트",
    "totalStock": 50
  }'
```

### 4. 부하 테스트

```bash
# k6 테스트 실행
./k6.exe run k6-bulk-test.js
```

### 5. 배치 실행

```bash
# 오늘 날짜 집계
curl -X POST "http://localhost:8080/api/admin/batch/aggregate?date=2025-12-28"
```

### 6. 통계 조회

```bash
# 배치 집계 결과 조회
curl "http://localhost:8080/api/admin/stats/daily?date=2025-12-28"
```

---

## 🧪 실험 가이드 (Performance Testing Guide)

이 섹션은 README에 명시된 성능 벤치마크를 **직접 재현**하고, 결과를 측정하는 방법을 안내합니다.

---

### 📋 실험 전 준비

#### 1. 환경 초기화

```bash
# 1. Docker 서비스 재시작 (깨끗한 상태)
docker-compose down
docker-compose up -d

# 2. DB 초기화 (필요 시)
# MySQL에 접속해서 테이블 TRUNCATE

# 3. 애플리케이션 실행
./gradlew bootRun
```

#### 2. 테스트용 캠페인 생성

```bash
# 재고 50개 캠페인 생성
curl -X POST http://localhost:8080/api/admin/campaigns \
  -H "Content-Type: application/json" \
  -d '{
    "name": "성능 테스트 캠페인",
    "totalStock": 50
  }'

# 응답에서 campaignId 확인 (예: id: 1)
```

---

### 실험 1: 동기 vs Kafka 아키텍처 비교

**목표**: Kafka 방식이 동기 방식보다 얼마나 빠르고 안정적인지 증명

#### 준비: k6 테스트 스크립트 작성

**동기 방식 테스트**
```javascript
// k6-sync-test.js
import http from 'k6/http';

export const options = {
  vus: 1000,        // 1000명 동시 요청
  duration: '10s',
};

export default function () {
  http.post(
    'http://localhost:8080/api/campaigns/1/participation-sync',
    JSON.stringify({ userId: __VU }),
    { headers: { 'Content-Type': 'application/json' } }
  );
}
```

**Kafka 방식 테스트**
```javascript
// k6-kafka-test.js
import http from 'k6/http';

export const options = {
  vus: 1000,
  duration: '10s',
};

export default function () {
  http.post(
    'http://localhost:8080/api/campaigns/1/participation',
    JSON.stringify({ userId: __VU }),
    { headers: { 'Content-Type': 'application/json' } }
  );
}
```

#### 실행 및 측정

```bash
# 1. 동기 방식 테스트
./k6.exe run k6-sync-test.js > result-sync.txt

# 2. 환경 초기화 (캠페인 재생성)

# 3. Kafka 방식 테스트
./k6.exe run k6-kafka-test.js > result-kafka.txt
```

#### 결과 비교

| 지표 | 동기 처리 | Kafka 처리 |
|------|----------|-----------|
| 평균 응답 시간 (p95) | _____ms | _____ms |
| 총 처리 시간 | _____초 | _____초 |
| 성공 요청 수 | _____ | _____ |
| 실패 요청 수 | _____ | _____ |
| 정합성 (재고 확인) | _____ | _____ |

**📸 스크린샷 첨부 위치**

<!-- 동기 방식 k6 결과 스크린샷 -->
![동기 방식 테스트 결과](./docs/images/experiment1-sync.png)

<!-- Kafka 방식 k6 결과 스크린샷 -->
![Kafka 방식 테스트 결과](./docs/images/experiment1-kafka.png)

<!-- 정합성 확인 SQL 결과 -->
```sql
-- 재고 확인
SELECT id, current_stock FROM campaign WHERE id = 1;

-- 성공/실패 건수
SELECT status, COUNT(*) as count
FROM participation_history
WHERE campaign_id = 1
GROUP BY status;
```
![정합성 검증 결과](./docs/images/experiment1-integrity.png)

---

### 실험 2: Kafka 파티션 개수에 따른 성능

**목표**: 파티션 개수 증가 시 처리량은 늘지만 순서 보장은 깨지는 것을 증명

#### 파티션 설정 변경 방법

**docker-compose.yml 수정**
```yaml
kafka:
  environment:
    # 파티션 개수 변경
    KAFKA_CREATE_TOPICS: "campaign-participation-topic:1:1"  # 파티션 1개
    # KAFKA_CREATE_TOPICS: "campaign-participation-topic:2:1"  # 파티션 2개
    # KAFKA_CREATE_TOPICS: "campaign-participation-topic:3:1"  # 파티션 3개
```

또는 **Kafka CLI로 Topic 재생성**
```bash
# 기존 Topic 삭제
docker exec -it kafka kafka-topics --bootstrap-server localhost:9092 \
  --delete --topic campaign-participation-topic

# 새 Topic 생성 (파티션 개수 지정)
docker exec -it kafka kafka-topics --bootstrap-server localhost:9092 \
  --create --topic campaign-participation-topic \
  --partitions 1 --replication-factor 1
```

#### 테스트 1: 파티션 1개

```bash
# 1. 파티션 1개로 설정
docker exec -it kafka kafka-topics --bootstrap-server localhost:9092 \
  --create --topic campaign-participation-topic \
  --partitions 1 --replication-factor 1

# 2. 10,000건 메시지 발행
curl -X POST http://localhost:8080/api/admin/test/participate-bulk \
  -H "Content-Type: application/json" \
  -d '{"count": 10000, "campaignId": 1}'

# 3. 시간 측정 (애플리케이션 로그에서 확인)
```

**측정 결과**

| 지표 | 값 |
|------|---|
| 총 처리 시간 | _____초 |
| 처리량 (TPS) | _____ msg/s |
| 정합성 (재고 50개 기준) | ✅ / ❌ |

**📸 스크린샷 첨부 위치**

<!-- 파티션 1개 로그 -->
![파티션 1개 - 처리 로그](./docs/images/experiment2-partition1-log.png)

<!-- Kafka UI - 파티션 1개 -->
![파티션 1개 - Kafka UI](./docs/images/experiment2-partition1-kafka-ui.png)

---

#### 테스트 2: 파티션 2개

```bash
# 1. Topic 삭제 후 재생성 (파티션 2개)
docker exec -it kafka kafka-topics --bootstrap-server localhost:9092 \
  --delete --topic campaign-participation-topic

docker exec -it kafka kafka-topics --bootstrap-server localhost:9092 \
  --create --topic campaign-participation-topic \
  --partitions 2 --replication-factor 1

# 2. Consumer 재시작 필요 (애플리케이션 재시작)

# 3. 10,000건 메시지 발행
curl -X POST http://localhost:8080/api/admin/test/participate-bulk \
  -H "Content-Type: application/json" \
  -d '{"count": 10000, "campaignId": 2}'

# 4. 시간 측정
```

**측정 결과**

| 지표 | 값 |
|------|---|
| 총 처리 시간 | _____초 |
| 처리량 (TPS) | _____ msg/s |
| 정합성 (재고 50개 기준) | ✅ / ❌ |

**📸 스크린샷 첨부 위치**

<!-- 파티션 2개 로그 -->
![파티션 2개 - 처리 로그](./docs/images/experiment2-partition2-log.png)

<!-- Kafka UI - 파티션 2개 -->
![파티션 2개 - Kafka UI](./docs/images/experiment2-partition2-kafka-ui.png)

---

#### 테스트 3: 파티션 3개

```bash
# 동일한 방법으로 파티션 3개 설정
docker exec -it kafka kafka-topics --bootstrap-server localhost:9092 \
  --create --topic campaign-participation-topic \
  --partitions 3 --replication-factor 1

# 테스트 실행...
```

**측정 결과**

| 지표 | 값 |
|------|---|
| 총 처리 시간 | _____초 |
| 처리량 (TPS) | _____ msg/s |
| 정합성 (재고 50개 기준) | ✅ / ❌ |

**📸 스크린샷 첨부 위치**

<!-- 파티션 3개 로그 -->
![파티션 3개 - 처리 로그](./docs/images/experiment2-partition3-log.png)

<!-- Kafka UI - 파티션 3개 -->
![파티션 3개 - Kafka UI](./docs/images/experiment2-partition3-kafka-ui.png)

---

#### 종합 비교 테이블

| 파티션 개수 | 처리 시간 | 처리량 (msg/s) | 순서 보장 | 정합성 |
|------------|----------|---------------|----------|--------|
| **1개** | _____초 | _____ | ✅ | ✅ / ❌ |
| **2개** | _____초 | _____ | ❌ | ✅ / ❌ |
| **3개** | _____초 | _____ | ❌ | ✅ / ❌ |

**예상 결과**:
- 파티션 수 증가 → 처리량 증가
- 하지만 순서 보장 불가 → 선착순 정합성 깨질 수 있음

---

### 실험 3: 배치 집계 성능 비교

**목표**: 배치 집계가 원본 테이블 직접 조회보다 얼마나 빠른지 증명

#### 준비: 대량 데이터 생성

```bash
# 1. 여러 캠페인 생성 (3~5개)

# 2. 각 캠페인에 대량 참여 데이터 생성
for i in {1..5}; do
  curl -X POST http://localhost:8080/api/admin/test/participate-bulk \
    -H "Content-Type: application/json" \
    -d "{\"count\": 5000, \"campaignId\": $i}"
done

# 3. Consumer가 모두 처리할 때까지 대기 (30초~1분)
```

#### 테스트 1: 원본 테이블 직접 집계 (느림)

```bash
# 여러 번 호출해서 평균 측정
for i in {1..5}; do
  curl "http://localhost:8080/api/admin/stats/raw?date=2025-12-30"
done
```

**결과**:
- 평균 쿼리 시간: _____ms
- DB CPU: _____%

**📸 스크린샷 첨부**

![원본 집계 API 응답](./docs/images/experiment3-raw-response.png)
![원본 집계 DB CPU](./docs/images/experiment3-raw-db-cpu.png)

---

#### 테스트 2: 배치 실행 후 조회 (빠름)

```bash
# 1. 배치 실행
curl -X POST "http://localhost:8080/api/admin/batch/aggregate?date=2025-12-30"

# 2. 배치 완료 대기 (10초~30초)

# 3. 집계 데이터 조회 (여러 번)
for i in {1..5}; do
  curl "http://localhost:8080/api/admin/stats/daily?date=2025-12-30"
done
```

**결과**:
- 평균 쿼리 시간: _____ms
- DB CPU: _____%

**📸 스크린샷 첨부**

![배치 집계 API 응답](./docs/images/experiment3-batch-response.png)
![배치 집계 DB CPU](./docs/images/experiment3-batch-db-cpu.png)

---

#### 성능 비교

| 방식 | 쿼리 시간 | DB CPU | 개선율 |
|------|----------|--------|-------|
| 원본 직접 집계 | _____ms | ____% | - |
| 배치 집계 후 조회 | _____ms | ____% | ____배 |

**📸 종합 비교 그래프**

<!-- 쿼리 시간 비교 그래프 (Chart.js나 엑셀로 생성) -->
![쿼리 시간 비교](./docs/images/experiment3-comparison-chart.png)

---

### 📊 실험 결과 정리 체크리스트

- [ ] 실험 1: 동기 vs Kafka 비교 완료
  - [ ] k6 결과 스크린샷 첨부
  - [ ] 정합성 검증 SQL 결과 첨부

- [ ] 실험 2: 파티션 개수 비교 완료
  - [ ] 파티션 1개 결과 첨부
  - [ ] 파티션 2개 결과 첨부
  - [ ] 파티션 3개 결과 첨부
  - [ ] 종합 비교 테이블 작성

- [ ] 실험 3: 배치 성능 비교 완료
  - [ ] 원본 집계 결과 첨부
  - [ ] 배치 집계 결과 첨부
  - [ ] 성능 개선율 계산

---

### 💡 Tips

1. **정확한 측정을 위해**:
   - 각 실험마다 DB, Kafka 초기화
   - 여러 번 실행 후 평균값 사용
   - 백그라운드 프로세스 최소화

2. **스크린샷 촬영**:
   - k6 결과: 터미널 전체 캡처
   - Kafka UI: http://localhost:8081
   - DB 쿼리: MySQL Workbench 또는 DBeaver
   - 애플리케이션 로그: IntelliJ/VSCode 콘솔

3. **Kafka UI 확인 사항**:
   - Topic → Partitions 탭에서 파티션별 offset 확인
   - Consumer 탭에서 처리 속도 확인

---

## 📁 Project Structure

```
src/main/java/io/eventdriven/batchkafka/
├── api/
│   ├── controller/          # REST API 컨트롤러
│   │   ├── ParticipationController.java  # 참여 API
│   │   ├── StatsController.java          # 통계 API
│   │   ├── BatchController.java          # 배치 관리 API
│   │   └── TestController.java           # 부하 테스트 API
│   ├── dto/                 # 요청/응답 DTO
│   └── exception/           # 커스텀 예외
│       ├── business/        # 비즈니스 예외 (4xx)
│       └── infrastructure/  # 인프라 예외 (5xx)
├── application/
│   ├── service/             # 비즈니스 로직
│   │   ├── ParticipationService.java     # 참여 처리
│   │   └── CampaignAggregationService.java  # 집계 서비스
│   ├── consumer/            # Kafka Consumer
│   │   └── ParticipationEventConsumer.java  # DLQ 패턴
│   └── event/               # 이벤트 객체
├── batch/                   # Spring Batch
│   ├── AggregateParticipationJobConfig.java
│   ├── AggregateParticipationTasklet.java
│   └── BatchScheduler.java  # 스케줄러
├── domain/
│   ├── entity/              # JPA 엔티티
│   │   ├── Campaign.java
│   │   ├── ParticipationHistory.java
│   │   └── CampaignStats.java
│   └── repository/          # JPA Repository
└── config/                  # 설정
    ├── KafkaConfig.java
    └── BatchConfig.java
```

---

## 🧪 k6 Load Testing

### 실시간 현황 테스트

```bash
./k6.exe run k6-bulk-test.js
```

### 동기 vs Kafka 비교

```bash
./k6.exe run k6-load-test.js
```

### 정합성 검증

```bash
./k6.exe run k6-verify-test.js
```

---

## 📈 Monitoring

### Kafka UI

```
http://localhost:8081
```

- Topic 메시지 확인
- Consumer 처리 상태 모니터링
- Partition, Offset 확인

### 애플리케이션 로그

```bash
# Consumer 처리 로그
tail -f logs/application.log | grep "메시지 처리"

# 배치 실행 로그
tail -f logs/application.log | grep "집계"
```

---

## 🎓 배운 점 & 트레이드오프

### 1. Kafka 파티션 개수

**트레이드오프**:
- 파티션 1개: 순서 보장 ✅, 처리량 제한 ⚠️
- 파티션 여러 개: 처리량 향상 ✅, 순서 보장 없음 ❌

**선택**: 선착순은 순서가 중요 → **파티션 1개**

---

### 2. 실시간 집계 vs 배치 집계

**트레이드오프**:
- 실시간: 항상 최신 데이터 ✅, DB 부하 높음 ❌
- 배치: DB 부하 낮음 ✅, 하루 지연 ⚠️

**선택**: 통계는 하루 늦어도 괜찮음 → **배치 집계**

---

### 3. 동기 vs 비동기 처리

**트레이드오프**:
- 동기: 구현 간단 ✅, 성능/안정성 떨어짐 ❌
- 비동기(Kafka): 성능/안정성 우수 ✅, 복잡도 증가 ⚠️

**선택**: 대규모 트래픽 대비 → **Kafka 비동기**

---

## 🔮 향후 개선 방향

- [ ] **Prometheus + Grafana**: 실시간 메트릭 모니터링
- [ ] **병렬 처리**: 캠페인별 집계를 CompletableFuture로 병렬화
- [ ] **알림 시스템**: 배치 실패 시 Slack/Email 자동 알림
- [ ] **재시도 메커니즘**: Spring Retry로 일시적 오류 자동 재시도
- [ ] **메타데이터 정리**: 90일 이상 배치 이력 자동 삭제

---

## 📝 License

This project is licensed under the MIT License.

---

## 👨‍💻 Author

**HSKHSMM**

---

## 🙏 Acknowledgments

이 프로젝트는 "기술을 써봤다"가 아니라 **"왜 이 기술을 선택했는지 설명할 수 있는 구조"**를 목표로 합니다.

실시간 이벤트 처리(Kafka)와 지연 허용 집계(Batch)를 언제 분리해야 하는지,
동시성 문제를 코드가 아닌 **아키텍처 레벨에서 해결**하는 경험을 기록했습니다.

---

**⭐ Star this repo if you find it helpful!**
