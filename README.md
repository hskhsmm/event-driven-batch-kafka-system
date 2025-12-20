
#  Event-Driven Batch & Kafka System

## 선착순 캠페인 처리 아키텍처 실험

> **Java 25 + Spring Boot 4.0** 환경에서
> 순간적으로 폭증하는 선착순 트래픽을 **안전하게 처리하기 위한 이벤트 기반 백엔드 아키텍처 실험 프로젝트**입니다.

---

##  프로젝트 소개

이 프로젝트는 단순한 기능 구현을 넘어서
**“왜 이 기술이 필요한가?”**, **“어디까지가 적절한 사용인가?”**에 대한 답을 코드로 증명하는 것을 목표로 합니다.

선착순 이벤트와 같이 **짧은 시간에 요청이 폭증하는 상황**에서
API 서버, DB, 배치 처리의 역할을 명확히 분리하고,

* 실시간 요청은 **Kafka를 통해 완충**
* 정합성은 **DB의 원자적 연산**
* 무거운 집계는 **Spring Batch로 비동기 분리**

하는 **엔터프라이즈급 백엔드 설계**를 지향합니다.

---

## ❓ 핵심 질문

* **Kafka**
  → API 서버와 DB 사이의 동시성 책임을 인프라로 위임할 수 있는가?

* **Spring Batch**
  → 실시간 트랜잭션과 무거운 통계 로직을 어떻게 분리할 것인가?

* **Concurrency**
  → 애플리케이션 락 없이, Kafka와 DB만으로 정합성을 보장할 수 있는가?

---

## 🛠 Tech Stack

| 분류                 | 기술                      | 선택 이유                                           |
| ------------------ | ----------------------- | ----------------------------------------------- |
| **Language**       | **Java 25**             | 최신 가상 스레드(Virtual Thread)를 통한 고효율 I/O 처리        |
| **Framework**      | **Spring Boot 4.0.1**   | 최신 Spring 생태계 기반의 안정적인 애플리케이션 구성                |
| **Message Broker** | **Apache Kafka**        | 트래픽 서지 흡수 및 이벤트 순서 보장 (Partition=1, Consumer=1) |
| **Database**       | **MySQL (RDS)**         | 트랜잭션과 인덱스를 통한 원자적 수량 차감                         |
| **Batch**          | **Spring Batch**        | 실시간 흐름을 방해하지 않는 대량 데이터 집계                       |
| **Infrastructure** | **AWS (EC2, ALB, VPC)** | 운영 환경 직접 구축 및 네트워크/보안 제어                        |

---

## 🏗 System Architecture

1. **Traffic Ingress**
   다수의 사용자가 동시에 선착순 참여 요청을 API 서버로 전송합니다.

2. **Virtual Thread 기반 API 처리**
   API 서버는 Java 25의 가상 스레드를 활용해
   대량의 HTTP 요청을 저비용으로 처리합니다.

3. **Buffering (Kafka)**
   API 서버는 DB에 직접 접근하지 않고
   **Kafka Topic에 이벤트를 발행한 뒤 즉시 응답**합니다.

4. **Processing (Consumer)**
   단일 파티션 컨슈머가 이벤트를 **순차적으로 소비**하며
   DB에서 선착순 수량을 차감합니다.

5. **Aggregation (Batch)**
   누적된 참여 이력은 새벽 시간대 Spring Batch를 통해
   통계 테이블로 마이그레이션됩니다.

---

##  핵심 설계 포인트

### 1️. Virtual Thread 기반 Ingress 처리

* 가상 스레드를 활용해
  **HTTP 요청 수 ≠ OS 스레드 수** 구조를 유지
* 대량의 요청이 몰려도 API 서버는
  **Kafka 이벤트 발행까지 안정적으로 처리**

👉 Virtual Thread는 **DB 처리용이 아니라 트래픽 유입(Ingress) 처리 최적화**에 사용됩니다.

---

### 2️. Kafka를 활용한 동시성 책임 분리

#### 문제

* 여러 API 서버 인스턴스가 동시에 DB에 접근
* DB 락 경합 증가
* 초과 발급 또는 성능 저하 위험

#### 해결

* Kafka를 **API 서버와 DB 사이의 완충 지대(Buffer)**로 사용
* **Partition = 1, Consumer = 1** 구조로
  선착순 판단 순서를 인프라 레벨에서 강제

```text
동시성 제어를 애플리케이션이 아닌
Kafka의 순서 보장 메커니즘에 위임
```

❗ 이 구조는 단순히 메시징을 위해 Kafka를 도입한 것이 아니라
API 서버와 DB 사이의 **동시성 책임을 완전히 분리하기 위한 선택**입니다.
Kafka가 없을 경우, 다수의 API 서버는 동일한 캠페인 행에 동시 접근하며
락 경합과 정합성 문제를 피하기 어렵습니다.

---

### 3️. 원자적 수량 차감 (Atomic Update)

별도의 분산 락 없이
**SQL 수준에서 수량 검증과 차감을 동시에 수행**합니다.

```sql
UPDATE campaign
SET remaining_quantity = remaining_quantity - 1
WHERE id = :id
  AND remaining_quantity > 0;
```

* 성공 시: 선착순 발급 성공
* 실패 시: 이미 소진된 상태

👉 DB가 잘하는 일을 DB에게 맡겨
**락 없이도 정합성을 보장**합니다.

---

<img width="220" height="598" alt="image" src="https://github.com/user-attachments/assets/b3fd5307-21a8-4b85-aa7f-f31387edff13" />



---
##  트래픽 폭증 테스트 (Performance Test)

**k6**를 활용해 시스템의 안정성과 한계를 검증합니다.

### 테스트 시나리오

* 1초 동안 **100명 동시 요청**
* 한정 수량 **50개 캠페인**

### 검증 지표

* **정합성**
  → 성공 발급 수 = 50건 (초과 발급 0건)
* **안정성**
  → 요청 폭증 상황에서도 API 응답 지연 없음
* **복구력**
  → Consumer 장애 시에도 Kafka에 이벤트 유지
  Offset 기반 재처리를 통해 손실 없이 복구 가능

---

##  프로젝트 핵심 성과

* **설계의 근거 명확화**
  → Redis, Elasticsearch 등 범용 기술을 무분별하게 사용하지 않음
  → 문제 해결에 필요한 최소 기술만 선택

* **관심사 분리**
  → 실시간 참여 처리(Kafka)
  → 비실시간 통계 처리(Spring Batch)

* **실무 관점 동시성 해결**
  → 애플리케이션 락이 아닌
  인프라 + DB 원자 연산으로 문제 해결

---

##  Next Steps

* [ ] Prometheus / Grafana 연동
  → Virtual Thread, Kafka Consumer 처리량 모니터링
* [ ] Kafka 장애 대응
  → Dead Letter Queue(DLQ) 및 재처리 전략 추가
* [ ] Kafka 미사용 구조와의 성능/정합성 비교 실험

---

###  마무리

이 프로젝트는  
“Kafka와 Batch를 써봤다”가 아니라,  “실시간 이벤트 처리와 지연 허용 집계를 언제 분리해야 하는지  
설명할 수 있는 구조”를 목표로 합니다.

선착순과 같이 즉각적인 정합성이 필요한 영역은 Kafka로 처리하고,  
통계·집계처럼 실시간성이 필요 없는 영역은 Batch로 분리함으로써  

동시성 문제를 코드가 아닌  
**아키텍처 레벨에서 해결한 경험**을 기록한 프로젝트입니다.

