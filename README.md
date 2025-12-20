

#  Event-Driven Batch & Kafka System: 선착순 캠페인 처리

> **Java 25와 Spring Boot 4.0** 환경에서 대규모 트래픽 폭증을 안전하게 처리하기 위한 **이벤트 기반 아키텍처** 실험 프로젝트입니다.

## 📌 프로젝트 소개

단순한 기능 구현을 넘어, **"왜 이 기술이 필요한가?"**에 대한 답을 찾는 것에 집중했습니다. 순간적으로 몰리는 선착순 요청을 Kafka를 통해 완충하고, 데이터의 정합성을 보장하며, 무거운 집계 로직은 배치를 통해 분리하는 **엔터프라이즈급 백엔드 설계**를 지향합니다.

### 핵심 질문

* **Kafka**: API 서버의 부하를 줄이고 동시성을 제어하는 '완충 지대'로 활용할 수 있는가?
* **Spring Batch**: 실시간 트랜잭션과 무거운 통계 로직을 어떻게 분리할 것인가?
* **Concurrency**: 애플리케이션 락이 아닌 인프라(Kafka)와 DB(Atomic Update)로 어떻게 동시성을 해결하는가?

---

## 🛠 Tech Stack

| 분류 | 기술 | 선택 이유 |
| --- | --- | --- |
| **Language** | **Java 25** | 최신 가상 스레드(Virtual Thread) 최적화를 통한 고성능 I/O 처리 |
| **Framework** | **Spring Boot 4.0.1** | 최신 릴리즈의 안정성 및 Spring Ecosystem 활용 |
| **Message Broker** | **Apache Kafka** | 트래픽 서지(Surge) 대응 및 이벤트 순서 보장 (Partition=1) |
| **Database** | **MySQL (RDS)** | 인덱스와 트랜잭션을 통한 원자적(Atomic) 수량 차감 및 데이터 정합성 |
| **Batch** | **Spring Batch** | 대량의 로그 데이터를 실시간 흐름 방해 없이 통계 데이터로 가공 |
| **Infrastructure** | **AWS (EC2, ALB, VPC)** | 서비스 운영 환경 직접 구축 및 네트워크 보안 제어 |

---

## 🏗 System Architecture

1. **Traffic Ingress**: 사용자의 선착순 참여 요청이 가상 스레드 기반의 API 서버로 유입됩니다.
2. **Buffering (Kafka)**: API 서버는 DB에 직접 접근하지 않고 Kafka Topic에 이벤트를 발행 후 즉시 응답합니다.
3. **Processing (Consumer)**: 단일 파티션 컨슈머가 이벤트를 순차적으로 읽어 DB의 수량을 차감합니다.
4. **Aggregation (Batch)**: 누적된 참여 데이터는 새벽 시간대 Batch Job을 통해 통계 DB로 마이그레이션됩니다.

---

## 🔥 핵심 설계 포인트

### 1. 가상 스레드 (Virtual Threads) 기반 처리

* Java 25의 개선된 가상 스레드를 활용하여, 수천 명의 요청이 몰려도 적은 메모리로 API 서버가 블로킹 없이 Kafka에 이벤트를 적재합니다.

### 2. Kafka를 활용한 동시성 제어

* **문제**: 여러 서버 인스턴스에서 동시 접근 시 DB 락(Lock) 경합으로 인한 성능 저하 발생.
* **해결**: Kafka의 파티션 구조를 활용하여 선착순 판단 순서를 인프라 레벨에서 강제하고, 소비자(Consumer) 단에서 처리 속도를 조절하여 DB 부하를 제어합니다.

### 3. 원자적 수량 차감 (Atomic Update)

* 별도의 분산 락 없이 SQL 수준에서 수량을 체크하고 차감하여 정합성을 보장합니다.
```sql
UPDATE campaign SET remaining_quantity = remaining_quantity - 1
WHERE id = :id AND remaining_quantity > 0;

```



---

## 🧪 트래픽 폭증 테스트 (Performance Test)

**k6**를 활용하여 시스템의 한계치와 안정성을 테스트합니다.

* **시나리오**: 1초 동안 100명의 사용자가 50개의 한정 수량 상품에 동시 응모
* **검증 지표**:
* **정합성**: 발급 성공 데이터가 정확히 50개인가? (초과 발급 0건)
* **안정성**: 요청 폭증 시 API 서버의 응답 속도가 일정하게 유지되는가?
* **복구력**: 컨슈머 장애 시 Kafka에 데이터가 안전하게 보관되는가?



---

## 📂 프로젝트 핵심 성과

* **설계의 근거**: Elasticsearch나 Redis 등 화려한 기술을 무분별하게 쓰지 않고, 문제 해결에 꼭 필요한 기술만 선택한 논리적 근거 확보.
* **관심사 분리**: 실시간 참여 로직(Kafka)과 비실시간 집계 로직(Batch)을 명확히 분리하여 시스템 확장성 증대.
* **최신 스택 적용**: Spring Boot 4.0 및 Java 25 기능을 실무적인 동시성 문제 해결에 녹여냄.

---

### 💡 다음 단계 (Next Steps)

* [ ] Prometheus/Grafana를 연동하여 가상 스레드 및 Kafka 처리량 모니터링 구축
* [ ] Kafka 장애 상황(Dead Letter Queue) 처리 로직 추가

