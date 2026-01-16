# Event-Driven First-Come-First-Served Campaign System

> **10만 건 동시 트래픽 환경에서 공정한 선착순 처리를 보장하는 이벤트 시스템**
>
> *This project focuses on architectural decision-making through measurable experiments rather than feature completeness.*

[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.1-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Kafka](https://img.shields.io/badge/Apache%20Kafka-3.x-black.svg)](https://kafka.apache.org/)
[![MySQL](https://img.shields.io/badge/MySQL-8.0-blue.svg)](https://www.mysql.com/)

---

## 프로젝트 의도

이 프로젝트는 수만 명이 동시에 참여하는 선착순 이벤트 환경에서  
단순한 처리 성공이 아닌 **공정한 순서 보장과 데이터 정합성**을 목표로 설계되었습니다.

특히 Kafka 파티션 수에 따른  
**처리량 증가와 순서 보장 붕괴의 트레이드오프**를  
실험과 수치로 검증하고, 이를 기반으로 아키텍처 결정을 내리는 것을 핵심 목표로 삼았습니다.

### 왜 이 프로젝트인가

기존 선착순 시스템 구현 사례들은 대부분 다음 중 하나에 머무르는 경우가 많습니다.

- 단일 서버 동기 처리
- Kafka 도입 여부 자체에만 초점을 둔 구현

그러나 실제 운영 환경에서는 다음과 같은 질문에 대한 답이 명확하지 않은 경우가 많습니다.

- Kafka는 어디까지 책임지는가?
- 파티션을 늘리면 무엇이 좋아지고, 무엇이 달라지는가?
- 순서가 일부 섞여도 최종 결과는 어떻게 보장되는가?

본 프로젝트는 기능 구현보다  
**이 질문들에 실험과 수치로 답하는 것**을 목표로 설계되었습니다.

### 핵심 질문

- 파티션을 늘리면 무조건 좋은가?
- 처리량과 공정성, 둘 중 무엇이 더 중요한가?
- 순서가 섞여도 최종 결과는 어떻게 보장하는가?

---

## Tech Stack

| 분류 | 기술 | 선택 이유 |
|------|------|-----------|
| **Language** | Java 25 | Virtual Thread 기반 고효율 I/O 처리 |
| **Framework** | Spring Boot 4.0.1 | 최신 Spring 생태계 |
| **Message Queue** | Apache Kafka | 트래픽 서지 흡수 + 파티션 기반 순서 보장 |
| **Database** | MySQL 8.0 | 트랜잭션 보장 + 원자적 UPDATE |
| **Batch** | Spring Batch | 실시간 경로와 집계 로직 분리 |
| **Load Test** | k6 | 파티션별 성능 비교 실험 |
| **Cloud** | AWS (EC2, RDS, ALB, CodeDeploy) | 실제 운영 환경 구성 |

---

## Kafka 파티션별 비교 & 분석

> 이 프로젝트의 핵심 차별점

### 초기 가설

Kafka 파티션 수 증가에 따라 다음과 같은 트레이드오프를 예상했습니다.

| 파티션 수 | 예상 처리량 | 예상 순서 불일치 |
|-----------|-------------|------------------|
| 1개 | 낮음 | 0% |
| 3개 | 중간 | 10%+ |
| 5개 | 높음 | 20%+ |

---

## 실제 실험 결과 개요

본 프로젝트에서는 Kafka 파티션 수에 따른 영향을 단일 TPS 지표로 판단하지 않고,  
**측정 기준을 세 가지로 분리하여 실험을 진행**했습니다.

Kafka 시스템은 처리 구간에 따라 병목 지점과 의미가 달라지기 때문에,  
각 측정 기준은 서로 다른 관점을 제공합니다.

### 실험에서 사용한 측정 기준

| 구분 | 측정 기준 | 의미 |
|------|-----------|------|
| Kafka TPS | Producer publish → Consumer poll | Kafka 파이프라인 처리 속도 |
| Web Monitoring | Kafka TPS + CPU + 진행률 | 운영 관점의 부하 분포 |
| DB TPS | Consumer 처리 → DB transaction commit | 실제 비즈니스 처리 속도 |

이 중 **최종 성능 판단 기준은 DB transaction commit 기준(TPS)**으로 설정했습니다.  
그 이유와 근거는 아래 실험 결과를 통해 설명합니다.

---

## DB Commit 기준 실험 결과 (최종 판단 기준)

다음 표는 **Consumer가 메시지를 처리한 뒤 DB 트랜잭션 커밋까지 완료한 시점**을 기준으로  
초당 처리량(TPS)을 측정한 결과입니다.

모든 실험은 **동일한 조건에서 100,000건 이벤트**를 처리하며 진행되었습니다.

| partition_count | total_events | processing_tps | switch_ratio | violation_count |
|-----------------|--------------|----------------|--------------|-----------------|
| 1 | 100,000 | 285.71 | 0 | 0 |
| 3 | 100,000 | 279.33 | 0.0026 | 0 |
| 5 | 100,000 | 288.18 | 0.0020 | 0 |

### 지표 설명 (Metrics Description)

**partition_count**  
- Kafka 토픽에 설정한 파티션 개수입니다.

**total_events**  
- 실험에서 처리한 전체 이벤트 수입니다.

**processing_tps**  
- Consumer가 메시지를 처리하고 **DB 트랜잭션 커밋까지 완료한 초당 처리 건수(TPS)**입니다.  
- Kafka 내부 처리 속도가 아닌, **실제 비즈니스 처리량 기준**의 TPS입니다.

**switch_ratio**  
- Kafka 이벤트를 시간 순서(kafka_timestamp)로 정렬했을 때, 이전 이벤트와 파티션이 달라지는 비율입니다.  
- 값이 높을수록 전역 이벤트 순서가 더 자주 섞였음을 의미합니다.

**violation_count**  
- 동일한 키(user_id) 기준으로 Kafka offset 순서가 역전된 경우의 수입니다.  
- 이 값이 0이라는 것은, 파티션 수와 무관하게 키 기반 순서 보장이 유지되었음을 의미합니다.

---

## DB Commit 기준 결과 해석

- 파티션 수를 1 → 3 → 5로 증가시켜도  
  **DB commit 기준 TPS는 선형적으로 증가하지 않았습니다.**
- 파티션 1, 3, 5 모두 **유사한 처리량 범위(약 280 TPS 내외)**를 보였습니다.
- 이는 시스템의 병목이 Kafka가 아닌  
  **Consumer 이후 DB 처리 단계에 있음을 의미**합니다.

즉, Kafka는 더 많은 메시지를 동시에 전달할 수 있었지만,  
DB가 이를 지속적으로 처리하지는 못했습니다.

---

## Kafka 메시지 소비 기준 실험 (Kafka TPS)

다음은 **Producer publish 이후 Consumer poll 성공 시점**을 기준으로 측정한 결과입니다.  
이 측정은 **Kafka 파이프라인 자체의 처리 능력**을 보여줍니다.

이 구간에서는 DB 처리 여부와 무관하게,  
Kafka가 얼마나 빠르게 메시지를 전달하는지를 확인할 수 있습니다.

### 파티션별 Kafka 메시지 소비 흐름

#### 파티션 1개
- 단일 Consumer 흐름
- 메시지 소비 속도가 비교적 일정
- 처리 흐름이 예측 가능

[사진: 파티션 1개 Kafka TPS 화면]

---

#### 파티션 3개
- Consumer 병렬성 증가
- 초반 Kafka TPS 상승
- 메시지 소비 시작 속도 증가

[사진: 파티션 3개 Kafka TPS 화면]

---

#### 파티션 5개
- 가장 많은 Consumer가 동시에 동작
- 메시지 소비가 초반에 집중됨
- Kafka 파이프라인 기준 처리 속도는 가장 빠름

[사진: 파티션 5개 Kafka TPS 화면]

---

## Web 실시간 모니터링 기준 분석

웹 대시보드는 Kafka TPS, 처리 진행률, CPU 사용률을  
**운영 관점에서 실시간으로 관찰하기 위해 구성**되었습니다.

이 지표는 처리량 자체보다는  
**부하가 언제, 어떻게 발생하는지를 시각적으로 보여주는 역할**을 합니다.

### 파티션별 Web 모니터링 결과

#### 파티션 1개
- CPU 부하가 서서히 상승
- 처리 전반에 걸쳐 안정적인 패턴
- 부하 분산이 완만함

[사진: 파티션 1개 Web 모니터링]

---

#### 파티션 3개
- 메시지 소비가 더 빠르게 시작
- CPU 부하가 비교적 이른 시점에 상승
- 초반 부하 집중 현상 관측

[사진: 파티션 3개 Web 모니터링]

---

#### 파티션 5개
- 메시지 소비가 초반에 가장 집중
- CPU 사용률이 빠르게 피크에 도달
- 전체 처리 완료 시점은 큰 차이 없음

[사진: 파티션 5개 Web 모니터링]

---

## 중간 결론: 측정 기준에 따라 달라지는 해석

- Kafka TPS 기준에서는 파티션 수 증가에 따른 **명확한 처리 속도 차이**가 존재합니다.
- Web 모니터링 기준에서는 파티션 증가가  
  **부하 발생 시점과 분포를 앞당기는 효과**를 보였습니다.
- 그러나 DB commit 기준에서는  
  **실제 처리량이 거의 증가하지 않았습니다.**

따라서 본 프로젝트에서는  
**DB transaction commit 기준을 최종 성능 판단 지표로 채택**했습니다.


### 진짜 차이: 부하 패턴

가장 명확한 차이는 **초반 부하 패턴**에서 나타났습니다.

| 파티션 수 | CPU 상승 패턴 | 특징 |
|-----------|---------------|------|
| 1개 | 서서히 상승 | 완만한 부하 분산 |
| 3개 | 빠르게 상승 | 조기 포화 |
| 5개 | 더 빠르게 상승 | 초반 집중 부하 |

파티션이 증가하면 Consumer 스레드가 더 동시에 깨어나고, 메시지를 더 빠르게 가져와서 **DB에 더 이른 시점에 부하가 집중**됩니다.

### 파티션별 CPU 부하 모니터링

#### 파티션 1개

**부하 시작점**

[사진: p1 부하 시작점]

**부하 꺾이는 시점**

[사진: p1 꺾이는 시점]

**최고 CPU**

[사진: p1 최고 cpu]

**처리 시간**: ___초

---

#### 파티션 3개

**부하 시작점**

[사진: p3 부하 시작점]

**부하 꺾이는 시점**

[사진: p3 꺾이는 시점]

**최고 CPU**

[사진: p3 최고 cpu]

**처리 시간**: ___초

---

#### 파티션 5개

**부하 시작점**

[사진: p5 부하 시작점]

**부하 꺾이는 시점**

[사진: p5 꺾이는 시점]

**최고 CPU**

[사진: p5 최고 cpu]

**처리 시간**: ___초

### 실험 결론

실험 결과, Kafka 파티션 수를 증가시켜도 처리량이 선형적으로 증가하지는 않았으며, 전역 순서 섞임 또한 극적으로 증가하지는 않았습니다.

다만 파티션 수가 늘어날수록 메시지 소비가 초반에 집중되며, **CPU 부하가 더 빠르게 상승하는 패턴**을 확인할 수 있었습니다.

이는 Kafka 자체의 처리 성능보다는 **Consumer 이후 DB 처리 단계가 시스템 전체 처리량의 병목으로 작용했기 때문**입니다.  
즉, Kafka는 더 빠르게 이벤트를 전달할 수 있었지만, DB가 그 속도를 지속적으로 수용하지는 못했습니다.

본 시스템에서는 성능 이득이 제한적인 상황에서, 선착순 이벤트의 **공정성과 예측 가능한 처리 흐름**을 우선하여 **파티션 1개 구성을 최종 선택**했습니다.



| 구분 | 내용 |
|------|------|
| **증명하지 못한 것** | 파티션 증가 시 TPS 선형 증가, 순서 심각한 붕괴 |
| **실제로 증명한 것** | Kafka 파티션 효과는 맥락 의존적, 부하 패턴 차이 존재 |
| **최종 선택 근거** | 성능 이득 제한적 → 공정성(전역 순서 단순화) 우선 |

---

## DB 원자성 보장

> Kafka가 순서를 '완화'시켜도, 최종 결과는 틀리지 않게 만든 장치

파티션이 몇 개든, 순서가 조금 섞이든 **재고는 절대 초과되지 않습니다.**

### 원자적 재고 차감 쿼리

```sql
UPDATE campaign
SET current_stock = current_stock - 1
WHERE id = :id AND current_stock > 0;
```

### 왜 이 방식이 안전한가?

| 방식 | 문제점 |
|------|--------|
| SELECT 후 UPDATE | 조회와 차감 사이에 다른 요청이 끼어들 수 있음 |
| 원자적 UPDATE | 조건 + 차감을 **하나의 SQL로 수행**, 락 없이 정합성 보장 |

**결과 해석**:
- `affected rows = 1`: 재고 차감 성공 (SUCCESS)
- `affected rows = 0`: 이미 소진됨 (FAIL)

Kafka에서 전역 순서가 다소 섞이더라도 DB 레벨에서 원자적으로 재고를 검증하기 때문에, 최종 결과의 정합성은 항상 보장됩니다.

---

## 배치 처리

> 실시간과 집계를 분리한 이유

실시간 트래픽 경로에는 집계 로직을 두지 않습니다. 통계는 **지연 허용 가능**하기 때문에 Spring Batch로 분리했습니다.

### 배치 설계

| 항목 | 내용 |
|------|------|
| 실행 시간 | 매일 새벽 2시 자동 실행 |
| 집계 대상 | 캠페인별 성공/실패 건수 집계 |
| 멱등성 보장 | `ON DUPLICATE KEY UPDATE` |
| 부분 실패 허용 | `REQUIRES_NEW` 트랜잭션 전파 |

### 멱등성 쿼리

```sql
INSERT INTO campaign_stats (campaign_id, success_count, fail_count, stats_date)
VALUES (1, 50, 702, '2025-01-15')
ON DUPLICATE KEY UPDATE
  success_count = VALUES(success_count),
  fail_count = VALUES(fail_count);
```

같은 날짜를 **여러 번 집계해도 데이터 중복이 발생하지 않습니다.**

### 배치 실행 결과

<img width="614" height="606" alt="배치성공 1월15" src="https://github.com/user-attachments/assets/25f3b5e0-3330-4b67-9fd0-21ea4b529a52" />


---

## 클라우드 아키텍처

> 실제 AWS 환경에서 운영

### AWS 서비스 구성

| 분류 | 서비스 | 용도 |
|------|--------|------|
| **Compute** | EC2 (App Server) | API 서버 + Consumer + Batch |
| **Compute** | EC2 (Kafka) | Kafka 브로커 (Docker) |
| **Database** | RDS (MySQL) | 재고 관리, 이력 저장 |
| **Network** | ALB | 트래픽 분산, 헬스체크 |
| **CI/CD** | GitHub Actions | 빌드 및 배포 자동화 |
| **CI/CD** | CodeDeploy | EC2 무중단 배포 |
| **Container** | ECR | Docker 이미지 저장소 |
| **Container** | Docker | Kafka 컨테이너 실행 |
| **Security** | Parameter Store | 환경변수 및 시크릿 관리 |
| **Access** | SSM Session Manager | SSH 없이 EC2 접속 |

### 인프라 구성

| 구성 요소 | 설명 |
|-----------|------|
| **EC2 (App)** | Spring Boot 애플리케이션 (API + Consumer + Batch) |
| **EC2 (Kafka)** | Docker로 Kafka 브로커 운영 |
| **RDS** | MySQL 8.0, 재고 및 참여 이력 저장 |
| **ALB** | HTTPS 처리, 헬스체크, 트래픽 분산 |

[사진: AWS 아키텍처 다이어그램]

---

## 실제 서비스 화면

[사진: 선착순 참여 성공 화면]

[사진: 실시간 모니터링 화면]

---

## Quick Start

```bash
# 1. Docker 서비스 시작 (Kafka, MySQL)
docker-compose up -d

# 2. 애플리케이션 실행
./gradlew bootRun

# 3. 캠페인 생성
curl -X POST http://localhost:8080/api/admin/campaigns \
  -H "Content-Type: application/json" \
  -d '{"name": "선착순 이벤트", "totalStock": 100}'

# 4. 부하 테스트
./k6.exe run k6-bulk-test.js
```

---

## License

This project is licensed under the MIT License.

---

## Author

**HSKHSMM**

---

이 프로젝트는 "기술을 써봤다"가 아니라 **"왜 이 기술을 선택했는지 설명할 수 있는 구조"**를 목표로 합니다.

**파티션 개수에 따른 순서 보장 vs 성능 트레이드오프**를 정량적으로 증명하고, 이를 바탕으로 아키텍처 결정을 내린 과정을 담았습니다.
