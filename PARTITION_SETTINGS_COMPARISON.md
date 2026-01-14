# Kafka 파티션별 설정 비교표

## 빠른 실행 명령어

```bash
# 파티션 1개 (Baseline)
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun

# 파티션 2개
docker exec kafka kafka-topics --bootstrap-server localhost:9092 --alter --topic campaign-participation-topic --partitions 2
SPRING_PROFILES_ACTIVE=local,p2 ./gradlew bootRun

# 파티션 3개
docker exec kafka kafka-topics --bootstrap-server localhost:9092 --alter --topic campaign-participation-topic --partitions 3
SPRING_PROFILES_ACTIVE=local,p3 ./gradlew bootRun

# 파티션 5개
docker exec kafka kafka-topics --bootstrap-server localhost:9092 --alter --topic campaign-participation-topic --partitions 5
SPRING_PROFILES_ACTIVE=local,p5 ./gradlew bootRun

# 파티션 10개
docker exec kafka kafka-topics --bootstrap-server localhost:9092 --alter --topic campaign-participation-topic --partitions 10
SPRING_PROFILES_ACTIVE=local,p10 ./gradlew bootRun
```

---

## 설정값 비교표

### Producer 설정

| 파티션 | buffer-memory | batch-size | linger-ms | compression | max-block-ms |
|--------|--------------|------------|-----------|-------------|--------------|
| 1개 (local) | 128MB | 64KB | 20ms | none | 60000ms |
| 2개 (p2) | 128MB | 64KB | 20ms | none | 60000ms |
| 3개 (p3) | **192MB** | 64KB | **15ms** | none | 60000ms |
| 5개 (p5) | **256MB** | **96KB** | **10ms** | none | 60000ms |
| 10개 (p10) | **512MB** | **128KB** | **5ms** | none | 60000ms |

**변경 이유**:
- `buffer-memory`: 파티션 증가 → 더 많은 버퍼 필요
- `batch-size`: 파티션 많을수록 큰 배치로 효율화
- `linger-ms`: 파티션 많으면 빠르게 전송 (지연 감소)

---

### Consumer 설정

| 파티션 | max-poll-records | max-poll-interval-ms | session-timeout-ms | concurrency |
|--------|------------------|----------------------|--------------------|-------------|
| 1개 (local) | 500 | 600000 (10분) | 45000 (45초) | 1 |
| 2개 (p2) | **300** | 600000 | 45000 | **2** |
| 3개 (p3) | **250** | 600000 | 45000 | **3** |
| 5개 (p5) | **200** | 600000 | 45000 | **5** |
| 10개 (p10) | **100** | 600000 | 45000 | **10** |

**변경 이유**:
- `max-poll-records`: 파티션 증가 → 각 스레드 부하 감소 → 타임아웃 방지
- `concurrency`: 파티션 수와 동일하게 자동 설정

---

### DB Connection Pool

| 파티션 | maximum-pool-size | minimum-idle | connection-timeout |
|--------|-------------------|--------------|-------------------|
| 1개 (local) | 20 | 10 | 20000ms |
| 2개 (p2) | **25** | **12** | 20000ms |
| 3개 (p3) | **30** | **15** | 20000ms |
| 5개 (p5) | **40** | **20** | 20000ms |
| 10개 (p10) | **60** | **30** | **30000ms** |

**변경 이유**:
- Consumer 스레드 증가 → 동시 DB 접속 증가
- `connection-timeout`: 10개 파티션에서는 대기 시간 증가 필요

---

## 예상 성능 비교

| 파티션 | 예상 TPS | 배수 | 예상 CPU | 순서 보장 | DB 병목 | 비고 |
|--------|---------|------|----------|-----------|---------|------|
| 1개 | 1,047 | 1.0x | 31% | ✅ 100% | 없음 | Baseline |
| 2개 | 1,500~1,800 | 1.5~1.8x | 50~55% | ⚠️ 파티션별 | 없음 | 순서 깨짐 시작 |
| 3개 | 2,000~2,500 | 2.0~2.5x | 60~65% | ⚠️ 파티션별 | 없음 | 균형적 선택 |
| 5개 | 3,000~4,000 | 3.0~4.0x | 70~80% | ⚠️ 파티션별 | ⚠️ 시작 | DB 주의 |
| 10개 | 4,000~5,000 | 4.0~5.0x | 90~100% | ⚠️ 파티션별 | ❌ 심각 | 수익 체감 |

---

## 설정 파일 위치

```
src/main/resources/
├── application.yml              # 공통 설정
├── application-local.yml        # 파티션 1개 (Baseline)
├── application-p2.yml           # 파티션 2개
├── application-p3.yml           # 파티션 3개
├── application-p5.yml           # 파티션 5개
└── application-p10.yml          # 파티션 10개
```

---

## 설정 변경 철학 요약

### 1. 배치 크기 감소 (MAX_POLL_RECORDS)
- **Why**: 파티션 증가 → 각 Consumer 스레드의 부하 감소
- **Effect**: 타임아웃 방지, 메모리 안정화

### 2. 버퍼 메모리 증가 (BUFFER_MEMORY)
- **Why**: 파티션 증가 → Producer가 더 많은 파티션에 전송
- **Effect**: 버퍼 대기 감소, 블로킹 타임아웃 방지

### 3. 전송 지연 감소 (LINGER_MS)
- **Why**: 파티션 많을수록 빠르게 분산 전송 필요
- **Effect**: 처리 속도 향상, 전체 지연 시간 단축

### 4. DB Pool 증가
- **Why**: Consumer 스레드 증가 → 동시 DB 접속 증가
- **Effect**: Connection 대기 시간 감소, DB 병목 완화

---

## 언제 어떤 설정을 사용할까?

### 순서 보장이 절대적으로 중요할 때
→ **파티션 1개 (local)**
- 예: 금융 거래, 주문 처리, 이벤트 소싱

### 순서와 속도의 균형이 필요할 때
→ **파티션 3개 (local,p3)**
- TPS 2배 향상
- CPU 효율 60%
- 파티션별 순서는 보장

### 최대 처리 속도가 필요할 때
→ **파티션 5개 (local,p5)**
- TPS 3~4배 향상
- CPU 효율 75%
- DB 병목 주의

### 실험/테스트 목적
→ **파티션 10개 (local,p10)**
- 오버 프로비저닝 효과 측정
- 수익 체감 구간 확인
- 시스템 한계 파악

---

## 트레이드오프 요약

```
┌─────────────┬──────────────┬──────────────┐
│   파티션 1  │   파티션 3   │   파티션 5   │
├─────────────┼──────────────┼──────────────┤
│ 순서: 100%  │ 순서: 파티션별│ 순서: 파티션별│
│ TPS: 1,047  │ TPS: 2,500   │ TPS: 3,500   │
│ CPU: 31%    │ CPU: 60%     │ CPU: 75%     │
│ 구현: 단순  │ 구현: 보통   │ 구현: 복잡   │
│ DB: 안정    │ DB: 안정     │ DB: 병목     │
└─────────────┴──────────────┴──────────────┘

선택 기준:
- 순서 > 속도: 파티션 1개
- 순서 = 속도: 파티션 3개 ⭐ (추천)
- 순서 < 속도: 파티션 5개
```

---

**작성일**: 2026-01-14
**버전**: v1.0
