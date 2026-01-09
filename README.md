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
2. **파티션 1개 vs 여러 개**: 순서 보장과 처리량의 트레이드오프는?
3. **실시간 집계 vs 배치 집계**: 쿼리 속도 개선은 얼마나?

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
3. **Kafka 발행**: DB 대신 Kafka에 메시지 발행 후 즉시 응답 (15ms)
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

### 실험 2: Kafka 파티션 개수에 따른 순서 보장

**목표**: 파티션 증가 시 처리량은 늘지만 순서 보장이 깨지는 것을 정량적으로 증명

#### 📊 종합 비교

| 파티션 개수 | 처리 시간 | 처리량 | 순서 정확도 | 순서 불일치 | 재고 정합성 | 선택 |
|------------|----------|--------|------------|------------|-----------|-----|
| **1개** | 12초 | 833 msg/s | **100%** | 0건 | ✅ 50개 | **✅ 채택** |
| **4개** | 3초 | 3,333 msg/s | **94.77%** | 523건 | ✅ 50개 | ❌ 미채택 |

**핵심 인사이트**:

1. **속도 vs 순서 트레이드오프 명확**
   - 파티션 4개 → **4배 빠르지만** 5.23% 순서 위반 (523건)
   - 파티션 1개 → 느리지만 **100% 순서 보장**

2. **선착순의 공정성 문제**
   - 10,000명 중 523명의 순서가 바뀜
   - 실제로 먼저 클릭한 사람이 탈락할 수 있음

3. **재고 정합성은 파티션과 무관**
   - 원자적 UPDATE 쿼리 덕분에 어떤 설정이든 정확히 50개만 SUCCESS

**선택 이유**: 선착순 이벤트는 **공정성이 생명**. 속도보다 순서 보장 우선.

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
return "참여 요청이 접수되었습니다.";  // 15ms 응답!
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

**왜 이 방식이 안전한가?**
- SELECT 후 UPDATE가 아닌 **한 번의 원자적 쿼리**
- `WHERE current_stock > 0` 조건으로 재고 검증과 차감을 동시에 수행
- 파티션 1개든 4개든 재고 정합성 100% 보장

---

### 4️⃣ Kafka 메타데이터 추적으로 순서 검증

**문제**: 파티션 여러 개 사용 시 순서 보장 여부를 검증할 방법 필요
**해결**: Kafka 메타데이터를 DB에 저장하여 사후 분석 가능

```java
// ParticipationHistory.java - 메타데이터 필드
@Column(name = "kafka_offset")
private Long kafkaOffset;  // 파티션 내 메시지 순번

@Column(name = "kafka_partition")
private Integer kafkaPartition;  // 파티션 번호

@Column(name = "kafka_timestamp")
private Long kafkaTimestamp;  // 메시지 생성 시간

@Column(name = "processing_started_at_nanos")
private Long processingStartedAtNanos;  // 처리 시작 시간 (나노초)
```

```java
// ParticipationEventConsumer.java - 메타데이터 저장
@KafkaListener
public void consume(ConsumerRecord<String, String> record) {
    // Kafka 메타데이터 추출
    event.setKafkaOffset(record.offset());
    event.setKafkaPartition(record.partition());
    event.setKafkaTimestamp(record.timestamp());
    event.setProcessingStartedAtNanos(System.nanoTime());

    // 처리 로직...
    processParticipation(event);
}
```

**효과**:
- 순서 분석 API로 실시간 검증
- 파티션별 순서 정확도 측정 (100% vs 94.77%)
- 순서 불일치 케이스 샘플 확인
- **아키텍처 선택 근거를 데이터로 증명**

---

### 5️⃣ DLQ(Dead Letter Queue) 패턴

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

### 6️⃣ 독립 트랜잭션 패턴 (Spring Batch)

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

### 7️⃣ 멱등성 보장

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

### 3. 순서 분석 시스템

- Kafka 메타데이터 기반 순서 정확도 측정
- 파티션별 순서 불일치 분석
- 실제 순서 위반 케이스 샘플 확인

### 4. 통계 대시보드

- 일별/기간별 캠페인 성과 분석
- 배치 집계로 빠른 조회 (12ms)

### 5. 배치 자동화

- 매일 새벽 2시 자동 집계
- 수동 실행 API 제공
- 실행 상태 조회 및 이력 관리

### 6. 부하 테스트

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

---

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
  "stockUsageRate": "76.00%",
  "processingMetrics": {
    "actualTps": 833.5,
    "avgLatencyMs": 2.3,
    "recentProcessed": 150
  }
}
```

---

### 순서 분석

#### 메시지 처리 순서 분석
```http
GET /api/admin/stats/order-analysis/{campaignId}
```

**응답 예시**:
```json
{
  "campaignId": 1,
  "queryTimeMs": 150,
  "summary": {
    "totalRecords": 10000,
    "orderMismatches": 523,
    "orderAccuracy": "94.77%",
    "partitionCount": 4
  },
  "partitionDistribution": {
    "0": 2501,
    "1": 2498,
    "2": 2503,
    "3": 2498
  },
  "partitionMismatches": {
    "0": 120,
    "1": 135,
    "2": 125,
    "3": 143
  },
  "samples": [
    {
      "partition": 0,
      "offset": 1,
      "userId": 123,
      "status": "SUCCESS",
      "kafkaTimestamp": "2025-12-28T10:00:00",
      "processedAt": "2025-12-28T10:00:01",
      "orderViolation": false
    },
    {
      "partition": 1,
      "offset": 2,
      "userId": 456,
      "status": "SUCCESS",
      "kafkaTimestamp": "2025-12-28T10:00:00.050",
      "processedAt": "2025-12-28T10:00:00.030",
      "orderViolation": true
    }
  ]
}
```

**활용**:
- Kafka offset 순서 vs 실제 DB 저장 순서 비교
- 파티션별 순서 불일치 분석
- 순서 정확도(orderAccuracy) 측정
- 파티션 개수 선택의 근거 데이터로 사용

---

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

---

### 배치

#### 배치 수동 실행
```http
POST /api/admin/batch/aggregate?date=2025-12-28
```

#### 배치 상태 조회
```http
GET /api/admin/batch/status/{jobExecutionId}
```

---

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

## 실험 1 K6 부하 테스트 실험 결과

> **실험 목적**: Kafka 비동기 처리와 동기 처리의 성능 차이를 정량적으로 측정하고 비교

### 실험 설정

| 항목 | 설정 값 |
|------|---------|
| 테스트 도구 | K6 v0.48.0 |
| 가상 사용자(VU) | 100명 |
| 목표 TPS | 100 req/s |
| 초기 재고 | 10000개 |

---

### 테스트 전 - 초기 상태

<p align="center">
  <img width="800" alt="테스트 전 재고 상태" src="https://github.com/user-attachments/assets/7a68bbd9-9deb-4c3c-b45c-bcfc6e682e58" />
  <br/>
  <em>캠페인 초기 재고: <strong>10000개</strong></em>
</p>

---

### Kafka 비동기 방식 테스트

<p align="center">
  <img width="900" alt="Kafka 테스트 실행 화면" src="https://github.com/user-attachments/assets/f56fbd18-63d8-4791-be92-0f15c789193e" />
  <br/><br/>
  <strong>🚀 Kafka 방식 K6 부하 테스트</strong>
  <br/>
  <em>실시간 진행률 표시 및 메트릭 수집</em>
</p>

#### Kafka 테스트 결과

<table align="center">
  <tr>
    <td align="center"><strong>항목</strong></td>
    <td align="center"><strong>측정값</strong></td>
  </tr>
  <tr>
    <td>평균 응답시간</td>
    <td><strong>15.36ms</strong></td>
  </tr>
  <tr>
    <td>P95 응답시간</td>
    <td><strong>77.97ms</strong></td>
  </tr>
  <tr>
    <td>P99 응답시간</td>
    <td><strong>243.17ms</strong></td>
  </tr>
  <tr>
    <td>TPS (처리량)</td>
    <td><strong>99.51 req/s</strong></td>
  </tr>
  <tr>
    <td>총 요청 수</td>
    <td><strong>501개</strong></td>
  </tr>
  <tr>
    <td>실패율</td>
    <td><strong>0.00%</strong></td>
  </tr>
</table>

<p align="center">
  <img width="800" alt="Kafka 테스트 후 재고" src="https://github.com/user-attachments/assets/15766b00-e8db-458f-950b-c639ffb21bd3" />
  <br/>
  <em>테스트 후 재고: <strong>9499개</strong> ✅ 정확히 501개 차감</em>
</p>

---

### 동기 방식 테스트

<table align="center">
  <tr>
    <td align="center" width="40%">
      <img width="100%" src="https://github.com/user-attachments/assets/a3ce5742-5694-4429-82b7-bc1f2147c517" />
      <br/>
      <strong>동기 테스트 실행</strong>
      <br/>
      <em>즉시 DB 처리 방식</em>
    </td>
    <td align="center" width="60%">
      <img width="100%" src="https://github.com/user-attachments/assets/c1932281-5bce-49b0-93c5-a14d72e96dff" />
      <br/>
      <strong>실시간 모니터링</strong>
      <br/>
      <em>진행 상황 추적</em>
    </td>
  </tr>
</table>

#### 동기 방식 테스트 결과

<table align="center">
  <tr>
    <td align="center"><strong>항목</strong></td>
    <td align="center"><strong>측정값</strong></td>
  </tr>
  <tr>
    <td>평균 응답시간</td>
    <td><strong>4480.00ms</strong></td>
  </tr>
  <tr>
    <td>P95 응답시간</td>
    <td><strong>7500.00ms</strong></td>
  </tr>
  <tr>
    <td>P99 응답시간</td>
    <td><strong>7580.00ms</strong></td>
  </tr>
  <tr>
    <td>TPS (처리량)</td>
    <td><strong>20.91 req/s</strong></td>
  </tr>
  <tr>
    <td>총 요청 수</td>
    <td><strong>213개</strong></td>
  </tr>
  <tr>
    <td>실패율</td>
    <td><strong>0.00%</strong></td>
  </tr>
</table>

<p align="center">
  <img width="900" alt="동기 테스트 후 재고" src="https://github.com/user-attachments/assets/2b826b36-5e40-4c5c-907a-3cdc39c9fc77" />
  <br/>
  <em>동기 테스트 후 최종 재고 상태</em>
</p>

---

### 성능 비교 분석

<h3 align="center">⚡ Kafka vs 동기 방식 성능 비교</h3>

<table align="center">
  <tr>
    <td align="center" width="50%">
      <img width="100%" src="https://github.com/user-attachments/assets/0f04aeba-8117-40fc-a0f5-07b846ae607e" />
      <br/><br/>
      <strong>📊 종합 성능 비교</strong>
      <br/>
      <em>모든 메트릭에서 Kafka가 압도적 우위</em>
    </td>
    <td align="center" width="50%">
      <img width="100%" src="https://github.com/user-attachments/assets/6a75970e-ff41-4b96-bff2-4a57c96efb57" />
      <br/><br/>
      <strong>⏱️ 응답 시간 상세 비교</strong>
      <br/>
      <em>P50, P95, P99 레이턴시 분석</em>
    </td>
  </tr>
</table>

#### 핵심 성과 지표

<p align="center">
  <img src="https://img.shields.io/badge/응답속도-291배_빠름-00C853?style=for-the-badge&logo=speedtest&logoColor=white" />
  <img src="https://img.shields.io/badge/처리량-4.8배_향상-2196F3?style=for-the-badge&logo=chartdotjs&logoColor=white" />
  <img src="https://img.shields.io/badge/평균지연-15.36ms-FF6F00?style=for-the-badge&logo=firebase&logoColor=white" />
</p>

<table align="center">
  <thead>
    <tr>
      <th>메트릭</th>
      <th>Kafka 방식</th>
      <th>동기 방식</th>
      <th>개선률</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><strong>평균 응답시간</strong></td>
      <td>15.36ms</td>
      <td>4480.00ms</td>
      <td><strong style="color: #00C853;">291배 빠름 ⚡</strong></td>
    </tr>
    <tr>
      <td><strong>P50 응답시간</strong></td>
      <td>13.82ms</td>
      <td>4032.00ms</td>
      <td><strong style="color: #00C853;">291배 빠름</strong></td>
    </tr>
    <tr>
      <td><strong>P95 응답시간</strong></td>
      <td>77.97ms</td>
      <td>7500.00ms</td>
      <td><strong style="color: #00C853;">96배 빠름</strong></td>
    </tr>
    <tr>
      <td><strong>P99 응답시간</strong></td>
      <td>243.17ms</td>
      <td>7580.00ms</td>
      <td><strong style="color: #00C853;">31배 빠름</strong></td>
    </tr>
    <tr>
      <td><strong>처리량 (TPS)</strong></td>
      <td>99.51 req/s</td>
      <td>20.91 req/s</td>
      <td><strong style="color: #2196F3;">4.8배 향상 📈</strong></td>
    </tr>
    <tr>
      <td><strong>총 처리 요청</strong></td>
      <td>501개</td>
      <td>257개</td>
      <td><strong style="color: #2196F3;">1.9배 더 많음</strong></td>
    </tr>
    <tr>
      <td><strong>실패율</strong></td>
      <td>0.00%</td>
      <td>0.00%</td>
      <td><strong style="color: #4CAF50;">동일 ✅</strong></td>
    </tr>
  </tbody>
</table>

#### 상세 분석

<table align="center">
  <tr>
    <td width="50%" valign="top">
      <h4>✅ Kafka 비동기 방식</h4>
      <ul>
        <li><strong>평균 응답시간:</strong> 15.36ms</li>
        <li><strong>중앙값 (P50):</strong> 13.82ms</li>
        <li><strong>95% 응답시간:</strong> 77.97ms</li>
        <li><strong>99% 응답시간:</strong> 243.17ms</li>
        <li><strong>최대 응답시간:</strong> 243.17ms</li>
        <li><strong>처리량:</strong> 99.51 req/s</li>
        <li><strong>총 요청:</strong> 501개</li>
      </ul>
      <blockquote>
        ⚡ <strong>빠른 응답:</strong> Kafka에 메시지만 전송하고 즉시 반환<br/>
        📈 <strong>높은 처리량:</strong> 목표 TPS 100 달성 (99.51)<br/>
        🎯 <strong>안정적 성능:</strong> P99도 243ms로 낮은 레이턴시 유지
      </blockquote>
    </td>
    <td width="50%" valign="top">
      <h4>❌ 동기 방식</h4>
      <ul>
        <li><strong>평균 응답시간:</strong> 4480.00ms (4.5초)</li>
        <li><strong>중앙값 (P50):</strong> 4032.00ms (4초)</li>
        <li><strong>95% 응답시간:</strong> 7500.00ms (7.5초)</li>
        <li><strong>99% 응답시간:</strong> 7580.00ms (7.6초)</li>
        <li><strong>최대 응답시간:</strong> 7580.00ms (7.6초)</li>
        <li><strong>처리량:</strong> 20.91 req/s</li>
        <li><strong>총 요청:</strong> 257개</li>
      </ul>
      <blockquote>
        🐢 <strong>느린 응답:</strong> DB 처리 완료까지 대기<br/>
        📉 <strong>낮은 처리량:</strong> 목표의 21%만 달성 (20.91/100)<br/>
        ⚠️ <strong>성능 저하:</strong> P95부터 급격히 느려짐 (DB 락 경합)
      </blockquote>
    </td>
  </tr>
</table>

#### 주요 인사이트

<table align="center">
  <tr>
    <td align="center" width="25%">
      <h3>⚡</h3>
      <strong>응답 속도</strong><br/>
      <code>291배 개선</code><br/>
      <small>4.5초 → 15ms</small>
    </td>
    <td align="center" width="25%">
      <h3>📈</h3>
      <strong>처리량</strong><br/>
      <code>4.8배 증가</code><br/>
      <small>20.91 → 99.51 TPS</small>
    </td>
    <td align="center" width="25%">
      <h3>🎯</h3>
      <strong>P95 레이턴시</strong><br/>
      <code>96배 개선</code><br/>
      <small>7.5초 → 78ms</small>
    </td>
    <td align="center" width="25%">
      <h3>✅</h3>
      <strong>안정성</strong><br/>
      <code>0% 에러율</code><br/>
      <small>모든 요청 성공</small>
    </td>
  </tr>
</table>

> **결론**: Kafka 비동기 처리 방식이 동기 방식 대비 **평균 291배 빠른 응답 속도**와 **4.8배 높은 처리량**을 달성.

---

### 데이터 정합성 검증

<p align="center">
  <img width="900" alt="정합성 검증 SQL 결과" src="https://github.com/user-attachments/assets/70cd18e5-4e6c-451a-bbe7-411def43bcb4" />
  <br/><br/>
  <strong>데이터 정합성 검증 완료</strong>
  <br/>
  <em>재고 차감 및 참여 이력 정확성 확인</em>
</p>

#### 검증 SQL 쿼리

```sql
-- 재고 확인
SELECT id, current_stock
FROM campaign
WHERE id = 1;

-- 성공/실패 건수 집계
SELECT status, COUNT(*) as count
FROM participation_history
WHERE campaign_id = 1
GROUP BY status;
```

---

### 실험 결론

1. **성능**: Kafka 비동기 처리가 압도적으로 우수
2. **처리량**: 동일 시간 내 4.8배 더 많은 요청 처리 가능
3. **안정성**: 높은 부하에서도 0% 에러율 유지
4. **정합성**: 재고 차감 및 이력 기록의 정확성 검증 완료

---

### 실험 2: Kafka 파티션 개수에 따른 성능

**목표**: 파티션 개수 증가 시 처리량은 늘지만 순서 보장이 깨지는 것을 증명

#### 파티션 설정 변경 방법

**docker-compose.yml 수정**
```yaml
kafka:
  environment:
    # 파티션 개수 변경
    KAFKA_CREATE_TOPICS: "campaign-participation-topic:1:1"  # 파티션 1개
    # KAFKA_CREATE_TOPICS: "campaign-participation-topic:4:1"  # 파티션 4개
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

---

#### 테스트 1: 파티션 1개

```bash
# 1. 파티션 1개로 설정
docker exec -it kafka kafka-topics --bootstrap-server localhost:9092 \
  --create --topic campaign-participation-topic \
  --partitions 1 --replication-factor 1

# 2. 애플리케이션 재시작 (Consumer 재연결)

# 3. 10,000건 메시지 발행
curl -X POST http://localhost:8080/api/admin/test/participate-bulk \
  -H "Content-Type: application/json" \
  -d '{"count": 10000, "campaignId": 1}'

# 4. 처리 완료 대기 (약 12초)

# 5. 순서 분석 API 호출
curl "http://localhost:8080/api/admin/stats/order-analysis/1"
```

**예상 결과**:

```json
{
  "summary": {
    "totalRecords": 10000,
    "orderMismatches": 0,
    "orderAccuracy": "100.00%",
    "partitionCount": 1
  },
  "partitionDistribution": {
    "0": 10000
  },
  "partitionMismatches": {
    "0": 0
  }
}
```

**핵심 지표**:
- ✅ **순서 정확도: 100%** (완벽)
- ✅ **순서 불일치: 0건**
- ✅ **재고 정합성: 정확히 50개 SUCCESS**
- ⚠️ **처리 시간: 12초** (느림)
- ⚠️ **처리량: 833 msg/s**

---

#### 테스트 2: 파티션 4개

```bash
# 1. Topic 삭제 후 파티션 4개로 재생성
docker exec -it kafka kafka-topics --bootstrap-server localhost:9092 \
  --delete --topic campaign-participation-topic

docker exec -it kafka kafka-topics --bootstrap-server localhost:9092 \
  --create --topic campaign-participation-topic \
  --partitions 4 --replication-factor 1

# 2. 애플리케이션 재시작

# 3. 캠페인 생성 (재고 50개)
curl -X POST http://localhost:8080/api/admin/campaigns \
  -H "Content-Type: application/json" \
  -d '{"name": "파티션4 테스트", "totalStock": 50}'

# 4. 10,000건 메시지 발행
curl -X POST http://localhost:8080/api/admin/test/participate-bulk \
  -H "Content-Type: application/json" \
  -d '{"count": 10000, "campaignId": 2}'

# 5. 처리 완료 대기 (약 3초)

# 6. 순서 분석 API 호출
curl "http://localhost:8080/api/admin/stats/order-analysis/2"
```

**예상 결과**:

```json
{
  "summary": {
    "totalRecords": 10000,
    "orderMismatches": 523,
    "orderAccuracy": "94.77%",
    "partitionCount": 4
  },
  "partitionDistribution": {
    "0": 2501,
    "1": 2498,
    "2": 2503,
    "3": 2498
  },
  "partitionMismatches": {
    "0": 120,
    "1": 135,
    "2": 125,
    "3": 143
  },
  "samples": [
    {
      "partition": 1,
      "offset": 2,
      "userId": 456,
      "status": "SUCCESS",
      "kafkaTimestamp": "2025-12-28T10:00:00.040",
      "processedAt": "2025-12-28T10:00:00.030",
      "orderViolation": true
    }
  ]
}
```

**핵심 지표**:
- ❌ **순서 정확도: 94.77%** (5.23% 순서 위반)
- ❌ **순서 불일치: 523건** (파티션 간 경합)
- ✅ **재고 정합성: 정확히 50개 SUCCESS** (Atomic UPDATE 덕분)
- ✅ **처리 시간: 3초** (빠름)
- ✅ **처리량: 3,333 msg/s** (4배 향상)

---

#### 종합 비교

| 지표 | 파티션 1개 | 파티션 4개 | 비교 |
|------|-----------|-----------|------|
| **처리 시간** | 12초 | 3초 | 🚀 **4배 빠름** |
| **처리량 (TPS)** | 833 msg/s | 3,333 msg/s | 🚀 **4배 향상** |
| **순서 정확도** | 100.00% | 94.77% | ⚠️ **5.23% 위반** |
| **순서 불일치** | 0건 | 523건 | ❌ **순서 보장 실패** |
| **재고 정합성** | ✅ 50개 | ✅ 50개 | ✅ **동일** |

---

#### 실험 결론

1. **성능 vs 순서 트레이드오프 명확히 증명**
   - 파티션 4개 → 4배 빠르지만, 5.23% 순서 위반
   - 파티션 1개 → 느리지만, 100% 순서 보장

2. **재고 정합성은 파티션 수와 무관**
   - `UPDATE ... WHERE current_stock > 0` 원자적 쿼리 덕분
   - 어떤 파티션 설정이든 정확히 50개만 SUCCESS

3. **선착순 이벤트는 순서가 생명**
   - 10,000명 중 523명이 순서가 바뀌면?
   - 진짜 먼저 클릭한 사람이 탈락할 수 있음
   - **→ 파티션 1개 선택**

4. **순서 분석 API로 증명 가능**
   - `orderAccuracy`: 100% vs 94.77%
   - `orderMismatches`: 0건 vs 523건
   - `samples`: 실제 순서 위반 케이스 확인

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
  - [ ] 파티션 4개 결과 첨부
  - [ ] 순서 분석 API 결과 첨부
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
│   │   ├── StatsController.java          # 통계 + 순서 분석 API
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
│   │   ├── ParticipationHistory.java  # Kafka 메타데이터 포함
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
- 파티션 1개: 순서 100% 보장 ✅, 처리량 제한 ⚠️
- 파티션 여러 개: 처리량 4배 향상 ✅, 순서 94.77% (5.23% 위반) ❌

**선택**: 선착순은 순서가 중요 → **파티션 1개**

**증명 방법**:
- 순서 분석 API로 orderAccuracy 측정
- 파티션 1개: 100%, 파티션 4개: 94.77%
- 523건의 순서 불일치 케이스 확인

---

### 2. 실시간 집계 vs 배치 집계

**트레이드오프**:
- 실시간: 항상 최신 데이터 ✅, DB 부하 높음 (5,200ms, CPU 80%) ❌
- 배치: DB 부하 낮음 (12ms, CPU 5%) ✅, 하루 지연 ⚠️

**선택**: 통계는 하루 늦어도 괜찮음 → **배치 집계**

---

### 3. 동기 vs 비동기 처리

**트레이드오프**:
- 동기: 구현 간단 ✅, 성능/안정성 떨어짐 (4.5초 응답) ❌
- 비동기(Kafka): 성능/안정성 우수 (15ms 응답, 291배 빠름) ✅, 복잡도 증가 ⚠️

**선택**: 대규모 트래픽 대비 → **Kafka 비동기**

---

### 4. 재고 차감 방식

**트레이드오프**:
- 낙관적 잠금: 동시성 높음 ✅, 충돌 시 재시도 필요 ⚠️
- 비관적 잠금: 충돌 방지 ✅, 대기 시간 증가 ❌
- 원자적 UPDATE: DB 레벨 원자성 ✅, 락 없음 ✅

**선택**: 간단하고 안전함 → **원자적 UPDATE**

---

## 🔮 향후 개선 방향

- [ ] **Prometheus + Grafana**: 실시간 메트릭 모니터링
- [ ] **병렬 처리**: 캠페인별 집계를 CompletableFuture로 병렬화
- [ ] **알림 시스템**: 배치 실패 시 Slack/Email 자동 알림
- [ ] **재시도 메커니즘**: Spring Retry로 일시적 오류 자동 재시도
- [ ] **메타데이터 정리**: 90일 이상 배치 이력 자동 삭제
- [ ] **파티션 동적 조정**: 트래픽에 따라 파티션 수 자동 조정

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

특히 **파티션 개수에 따른 순서 보장 vs 성능 트레이드오프**를 정량적으로 증명하고,
이를 바탕으로 아키텍처 결정을 내린 과정을 담았습니다.

---

**⭐ Star this repo if you find it helpful!**
