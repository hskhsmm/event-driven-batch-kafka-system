# k6 부하 테스트 가이드

## 📋 사전 준비

### 1. k6 설치

**Windows (Chocolatey):**
```bash
choco install k6
```

**Windows (직접 다운로드):**
https://k6.io/docs/get-started/installation/

**Mac:**
```bash
brew install k6
```

**Linux:**
```bash
sudo gpg -k
sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt-get update
sudo apt-get install k6
```

### 2. 애플리케이션 실행 확인
```bash
# MySQL, Kafka 실행 확인
docker-compose ps

# 애플리케이션 실행
./gradlew bootRun

# 또는
java -jar build/libs/batch-kafka-system-0.0.1-SNAPSHOT.jar
```

### 3. 테스트용 캠페인 생성
```bash
curl -X POST http://localhost:8080/api/admin/campaigns \
  -H "Content-Type: application/json" \
  -d '{
    "name": "k6 부하 테스트 캠페인",
    "totalStock": 50
  }'
```

---

## 🚀 테스트 실행

### 테스트 1: 기본 부하 테스트
```bash
k6 run k6-load-test.js
```

**출력 예시:**
```
     ✓ status is 200
     ✓ response has success field

     checks.........................: 100.00% ✓ 200       ✗ 0
     http_req_duration..............: avg=45ms  p(95)=120ms
     http_reqs......................: 100     20/s
     participation_success..........: 100
```

### 테스트 2: 정합성 검증 테스트 (권장)
```bash
k6 run k6-verify-test.js
```

**특징:**
- 캠페인 자동 조회
- 실행 전/후 안내 메시지
- 검증 가이드 제공

---

## 📊 결과 확인

### 1. Kafka Consumer 로그 확인
```bash
# 애플리케이션 로그에서 Consumer 처리 확인
# "선착순 참여 성공", "선착순 마감" 메시지 확인
```

### 2. 배치 집계 실행
```bash
# 오늘 날짜로 집계
curl -X POST "http://localhost:8080/api/admin/batch/aggregate?date=$(date +%Y-%m-%d)"
```

### 3. 통계 API로 확인
```bash
curl "http://localhost:8080/api/admin/stats/daily?date=$(date +%Y-%m-%d)"
```

**예상 응답:**
```json
{
  "success": true,
  "data": {
    "summary": {
      "totalSuccess": 50,
      "totalFail": 50,
      "overallSuccessRate": "50.00%"
    }
  }
}
```

### 4. DB 직접 확인 (가장 정확)
```bash
docker exec -it mysql mysql -uroot -p
```

```sql
-- 캠페인 재고 확인
SELECT id, name, current_stock, total_stock
FROM campaign;

-- 참여 결과 집계
SELECT status, COUNT(*) as count
FROM participation_history
WHERE DATE(created_at) = CURDATE()
GROUP BY status;

-- 예상 결과:
-- SUCCESS: 50
-- FAIL: 50
```

---

## 🎯 테스트 시나리오 상세

### 시나리오 1: 순간 폭증 (Spike Test)
```javascript
// k6-load-test.js
rate: 100,      // 100개 요청
timeUnit: '1s', // 1초 동안
```

**검증 항목:**
- ✅ 정합성: 성공 건수 = 재고 수량 (50개)
- ✅ 안정성: 95% 요청이 500ms 이내 응답
- ✅ Kafka: 순서대로 처리되는가?

### 시나리오 2: 점진적 증가 (Ramp-up Test)
```bash
# k6-verify-test.js 수정
stages: [
  { duration: '5s', target: 50 },   // 5초간 50명
  { duration: '5s', target: 100 },  // 5초간 100명
  { duration: '5s', target: 0 },    // 정리
]
```

---

## 🐛 트러블슈팅

### 문제 1: "connection refused"
```bash
# 애플리케이션이 실행 중인지 확인
curl http://localhost:8080/api/admin/campaigns

# 포트 확인
netstat -ano | findstr :8080
```

### 문제 2: "캠페인을 찾을 수 없습니다"
```bash
# 캠페인 생성 확인
curl http://localhost:8080/api/admin/campaigns

# 없으면 생성
curl -X POST http://localhost:8080/api/admin/campaigns \
  -H "Content-Type: application/json" \
  -d '{"name":"테스트","totalStock":50}'
```

### 문제 3: "모든 요청이 성공 (100개)"
→ **정상 아님!** 재고가 50개면 50개만 성공해야 함
- Kafka Consumer 정상 작동 확인
- DB 트랜잭션 확인
- `decreaseStockAtomic` 쿼리 확인

### 문제 4: "모든 요청이 실패 (0개)"
→ Kafka 또는 Consumer 문제
```bash
# Kafka 컨테이너 확인
docker ps | grep kafka

# Consumer 로그 확인
# "Kafka 메시지 수신" 로그가 있는지 확인
```

---

## 📈 성능 목표

| 지표 | 목표 | 설명 |
|------|------|------|
| **정합성** | 100% | 성공 건수 = 재고 수량 (정확히 일치) |
| **응답 시간 (p95)** | < 500ms | 95%의 요청이 500ms 이내 |
| **처리량** | 100 req/s | 초당 100개 요청 처리 |
| **실패율** | ~50% | 재고 소진 후 정상 실패 (50/100) |

---

## 🔬 고급 테스트

### 다양한 부하 패턴 테스트
```bash
# 1000명 동시 요청
k6 run -e USERS=1000 k6-load-test.js

# 특정 캠페인 테스트
k6 run -e CAMPAIGN_ID=2 k6-load-test.js
```

### HTML 리포트 생성
```bash
k6 run --out json=results.json k6-verify-test.js
```

---

## ✅ 성공 기준

1. **정합성 검증**
   - `campaign.current_stock = 0` (재고 완전 소진)
   - `participation_history` 집계: SUCCESS 50건, FAIL 50건
   - 초과 발급 없음

2. **성능 기준**
   - p95 응답 시간 < 500ms
   - API 에러율 < 1% (재고 소진 제외)

3. **Kafka 순서 보장**
   - Consumer 로그에서 순차 처리 확인
   - 재고가 음수가 되지 않음

---

## 📝 참고

- k6 공식 문서: https://k6.io/docs/
- 프로젝트 README: [README.md](./README.md)
