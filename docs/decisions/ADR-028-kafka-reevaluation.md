# ADR-028 · Kafka 재평가 — 지금 제품 요구로는 과하다고 판단, 아웃박스 → Redis Pub/Sub으로 대체한다

- 상태: **Accepted(2026-10-14 20:39 KST, 사용자 결정 — B. Kafka 제거)** — 구현 전. 제거 설계·의존성 목록·단계별 PR·게이트·롤백은 [kafka-removal-design.md](../deployment/kafka-removal-design.md)
- 날짜: 2026-10-14
- 관련: [[ADR-008]](Kafka 이벤트 백본 + DLQ — 이 ADR이 재평가하는 결정), [[ADR-010]](트랜잭셔널 아웃박스), [[ADR-012]](3 AZ·멀티브로커),
  [[IMP-016]](브로커 1대 장애 실증), [[TS-023]](다중 Pod SSE 전달), `docs/deployment/portfolio-framing-and-decisions.md`(도입 의도),
  [operating-requirements.md](../deployment/operating-requirements.md)(운영 요구사항 결정), [workload-responsibilities.md](../deployment/workload-responsibilities.md)

## 1. 맥락 — 왜 도입했나 (당시 기록)
- [[ADR-008]]: 실시간 이벤트(`order.paid` 등)를 앱 메모리에서 바로 SSE로 broadcast하던 구조의 한계 —
  (1) 이벤트 백본이 없어 소비자 확장·재처리·감사가 불가능, (2) 결제 **커밋 전에** broadcast돼 "롤백됐는데 알림은 나간" 유령 이벤트 여지.
- `portfolio-framing-and-decisions.md`: "라이브 K8s + 멀티브로커 Kafka를 **의도적으로 채택**" — 근거 "(a) 분산/K8s/Kafka 운영 역량 **시연 목적**, (b) 폭주+정합성 도메인이라 확장·복제가 의미 있음".
- 같은 문서의 근거는 셋이다 — (a) 시연, (b) 폭주+정합성 도메인이라 확장·복제가 의미 있음, (c) 실증으로 과함을 상쇄. 다만 **실제 이벤트 소비자 기준으로는 평가하지 않았다**. 이 ADR이 그 빈칸이다.

## 2. 지금 실제 쓰임 (코드 사실)
- 아웃박스에 쌓이는 이벤트는 **`order.paid` 하나**다(`PaymentService.appendOutbox("order.paid", …)` 한 곳).
  경로: 결제 트랜잭션에서 아웃박스 적재 → 릴레이(1초, ShedLock)가 Kafka `order-events`로 발행 → `OrderEventConsumer`(eventId Redis SETNX 멱등) → SSE(파드 간 전달 — [[TS-023]] A3, Redis 경유는 TS가 "강한 추론"으로 표기).
- `payment.vbank.deposited`(`PaymentService.java:329·364`), `order.cancelled`·`order.refunded`(`RefundService.java:217-218`, `RefundConverger.java:108-109`)는 Kafka를 거치지 않고 `OrderSseRegistry`로 바로 broadcast한다 — Kafka를 지나는 것은 `order.paid`뿐이다.
- 프론트(`useOrder.ts`)는 마운트·SSE 재연결(`onopen`)·주문 이벤트 3종 때 **주문을 다시 조회**한다. **폴링은 없다** — 알림이 빠지면 화면이 갱신되지 않은 채 멈춰 있을 수 있고, 재진입·재연결 때 DB 상태로 맞춰진다(코드 주석도 같은 위험을 적는다).
  다만 주 흐름은 `order.paid` 알림에 의존하지 않는다 — 카드·간편결제는 결제 API 응답의 `orderStatus`로 바로 화면을 넘기고(`pay/page.tsx`), 무통장은 직접 broadcast되는 `payment.vbank.deposited`로 갱신된다.
- 마지막 구간(Redis Pub/Sub → SSE)은 메시지를 보존하지 않는다. Kafka의 내구성이 **브라우저 도착**을 보장하지 않는다 — 위의 "알림이 빠지면 멈춤" 위험은 Kafka가 있어도 그대로다.
- 대기열 진입·승격, 좌석 선점, 주문·결제 정합성은 Kafka를 거치지 않는다(Redis Lua, PostgreSQL 조건부 UPDATE·트랜잭션).
- DLQ: 소비자 예외가 재시도(`FixedBackOff` 300ms × 2) 뒤 `order-events.DLT` → `dlq_messages`(DB) 적재, 사람이 admin API로 원본 토픽에 재발행. 소비자가 하는 일이 SSE 전달뿐이고 `SsePubSub.publish`는 예외를 삼키며 SETNX는 fail-open이라, 소비자가 예외를 낼 경로는 사실상 payload 이상(역직렬화 실패 등)뿐이다(코드에서 끌어낸 추론).

## 3. Kafka의 운영 비용 (설정 사실)
| 항목 | 지금 |
|---|---|
| 브로커 | 3개, KRaft, **controller와 broker를 한 프로세스가 겸함**(`roles: [controller, broker]`) — 공식 문서가 중요한 프로덕션에 권하는 역할 분리 구성은 아니다 |
| 자원 | 브로커에 **CPU·메모리 requests·limits를 적지 않았다**(`KafkaNodePool`·`Kafka.spec.kafka`에 `resources` 없음 — 매니페스트 기준. Strimzi가 실제로 만든 Pod의 값은 미확인) — 그대로라면 스케줄러가 몫을 잡지 않는다. 노드 메모리 압박 시 먼저 퇴거될 수 있다(LimitRange가 없다는 데서 나온 추론, 클러스터 QoS 미관찰) |
| 스토리지 | PVC 10Gi × 3(gp2 EBS), AZ 종속 |
| 배치 | 가용 영역 분산 `DoNotSchedule` — AZ별 노드 그룹·최소 노드가 필요한 이유 중 하나([[ADR-012]]) |
| 운영 절차 | Strimzi 운영자 설치, bring-up·철거 단계, PVC 정리, 테스트의 Testcontainers Kafka |
| 복제 | RF 3, min.insync 2, 앱 프로듀서 acks=all — 브로커 1대 장애에도 쓰기 지속은 **Kafka 레벨에서 실측**(`kafka-console-producer --acks=all`), 앱의 `order.paid` 발행 경로는 미검증([[IMP-016]] — 부분 검증) |

## 4. 원래 문제는 Kafka 없이 풀리는가
| ADR-008의 문제 | Kafka 없이 |
|---|---|
| 커밋 전 유령 알림 | **아웃박스**가 이미 해결한다 — 결제와 같은 트랜잭션에 이벤트를 쓰고, 커밋된 것만 릴레이가 내보낸다([[ADR-010]]) |
| 감사·재처리 | 아웃박스 행(발행 시각·시도 횟수·실패)이 기록이다 — PUBLISHED 행은 7일 뒤 정리(`outbox.retention-days`), DEAD 행은 남는다. 재처리 = PENDING/실패 행 재발행 |
| 소비자 확장 | 지금 소비자는 "SSE 전달" 하나다. 파드 간 전달은 이미 Redis 팬아웃이 한다 |

## 5. 대안
| 안 | 내용 | 장점 | 대가 |
|---|---|---|---|
| A. 유지 | 지금 그대로, 한계를 문서에 명시 | 변경 없음, Kafka 운영 실험이 코드에 남음 | 위 운영 비용. "알림 하나에 브로커 3대?"라는 질문에 제품 근거로 답하기 어렵다(예상 — 추측) |
| **B. 제거 — 아웃박스 → Redis Pub/Sub(채택)** | 릴레이가 Kafka 대신 Redis Pub/Sub으로 발행(SSE 팬아웃 채널), 소비자·DLQ·Strimzi 제거 | 구성 요소 축소(브로커 3·EBS 3·운영자), 아웃박스로 "이벤트 발생" 내구성은 유지, 프론트·SSE 계약 변화 0 | Kafka 실험이 코드에서 사라짐(기록은 남김), 재생(replay)·다중 소비자 능력 상실 — 지금은 쓰지 않음 |
| C. 아웃박스 → SQS | 관리형 큐로 비동기 작업 | 재시도·DLQ 관리형 | AWS 의존, 지금 요구에는 역시 과함 |
| D. MSK | 관리형 Kafka | 브로커 운영 부담 감소 | 비용, 필요성 문제는 그대로 |
| E. Redis Streams | 이미 쓰는 Redis로 보존·소비자 그룹·재생 | 새 구성 요소 없이 Kafka 기능 일부 | 지금은 재생·다중 소비자 요구가 없어 Pub/Sub보다 복잡 — **다시 도입할 조건이 생기면 Kafka보다 먼저 볼 후보** |

## 6. 결정 — B(아웃박스 → Redis Pub/Sub) — 사용자 결정 2026-10-14 20:39 KST
- 유지: 아웃박스 적재(결제 트랜잭션과 원자), 릴레이(at-least-once), 커밋된 것만 알림, SSE + 재조회.
- 제거: Kafka 프로듀서·`OrderEventConsumer`·DLQ 소비자와 admin 재처리(대상 재설계), Strimzi·Kafka 매니페스트, Testcontainers Kafka.
- **전달 보장 정책(바뀌는 의미):** 아웃박스 `PUBLISHED` = "**릴레이가 Redis에 `PUBLISH`를 시도해 그 호출이 성공했다**"는 뜻이다. 구독자 수(0 포함)·파드의 로컬 전달·사용자(브라우저) 수신을 뜻하지 않는다.
  - 지금의 `PUBLISHED`("Kafka 로그에 복제·저장됨")보다 약하다. **잃는 것**은 소비자가 다시 붙을 때까지 메시지를 들고 있던 Kafka의 보관이다.
  - 브라우저 수신은 Kafka가 있을 때도 보장되지 않았다. 마지막 Redis·SSE 홉이 비보존이고, 소비자의 Redis 발행 실패는 삼켜졌다.
  - 릴레이는 실패를 던지는 별도 발행 메서드를 쓴다 — `SsePubSub.publish()`(예외를 삼킴)와 `OrderSseRegistry.broadcast()`(커밋 뒤로 미뤄 마킹이 먼저 됨)는 쓰지 않는다. 그래서 Redis 실패는 PENDING으로 남아 재시도된다(at-least-once 유지).
  - 중복은 프론트의 재조회 1회로 끝나므로 소비자 멱등(SETNX)은 소비자와 함께 없앤다. 상세: [kafka-removal-design.md](../deployment/kafka-removal-design.md) §1·§2.
- **다시 도입할 조건**(먼저 Redis Streams, 그다음 Kafka·SQS를 본다): 같은 이벤트를 독립적으로 처리할 소비자가 둘 이상(정산·통계·외부 알림 등), 이벤트 재생 요구, Redis로 감당 못 할 이벤트 처리량, 집계 단위 순서 보장 요구, **금전·재고를 바꾸는 소비자**(이때는 `processed_events`를 비즈니스 트랜잭션과 묶어야 한다 — `OrderEventConsumer` 주석), 서비스 분리 시 서비스 간 이벤트.
- 남기는 것: [[ADR-008]]·[[ADR-010]]·[[IMP-016]] 등 Kafka 문서는 지우지 않고 "ADR-028로 대체됨"을 표시해 **운영 실험의 기록**으로 둔다.

## 7. 제거 단계(개요 — 확정 순서·게이트·롤백은 [kafka-removal-design.md](../deployment/kafka-removal-design.md) §6)
1. 문서(이 ADR·설계 문서) → 프론트 재조회 보완(Kafka와 독립) → 관리 화면을 아웃박스 기준으로 **추가**(V20, 기존 DLQ 유지).
2. **앱 전환 = 릴리스 N:** 릴레이 발행 대상 교체, 소비자·DLQ 제거, 테스트 교체, 계약·하네스 규칙과 함께 `configmap`의 `KAFKA_*`(하네스 k8s 규칙 1 — 앱이 읽지 않는 env 금지)·앱 지표 기반 알림(`prometheusrule`의 DLQ 적체·ConsumerLag, `harness/alerts/rule-tests.yml`)도 **같은 PR**에서 제거. 떠 있는 클러스터에서 전환하면 그 앞에 "발행 대상 설정 전환 → Kafka 배출 확인" 단계를 둔다(ShedLock은 발행 경로 하나를 보장하지 않는다).
3. 운영 게이트 뒤 **인프라 제거:** Strimzi·Kafka 매니페스트, Kafka 브로커 모니터링(`dashboard-kafka.yaml`, `podmonitor-kafka.yaml`), bring-up·철거 단계, compose, 장애 시험. EBS CSI는 Prometheus PVC(gp2 20Gi)가 쓰므로 **유지**(terraform 주석만 정리).
4. N+1에서 `dlq_messages` 테이블·`dlq_pending_threshold` 컬럼 `DROP`(Expand-Contract, allow 주석), 마지막으로 문서 정리(ADR-008·010 상태, README·HARNESS·배포 문서).
- 각 단계는 PR → G1 → CI. 실제 클러스터 동작은 다음 측정 세션에서 확인.

## 8. 영향
- 평시 노드 하한의 이유 하나(Kafka 브로커 AZ 분산 `DoNotSchedule`)가 사라진다. 다만 AZ별 노드 그룹의 근거(EBS의 AZ 종속)는 Prometheus PVC에도 그대로 남는다 — 노드 그룹 구성은 api·대기열 파드의 가용성 목표(G1: 노드 1대 장애, G2: AZ 장애는 후속)와 Prometheus 배치로 다시 판단한다(RDS·Redis는 관리형이라 노드와 무관). 노드 수는 자원 산정에서 계산한다 — CPU requests 하한(A' 3·현재 B 5·B 2+2 후보 4)은 Kafka 제거만으로 낮아지지 않는다(설계 문서 §7).
- 3 AZ·AZ별 NAT·RDS Multi-AZ는 이번에 **유지**하지만, 3 AZ의 주된 근거(브로커 장애 도메인 분리)가 사라지므로 최종 운영 정책에서 비용과 가용성(G1은 노드 장애라 3 AZ가 필수 조건이 아님, G2가 AZ 장애)으로 다시 평가한다.
- 기존 B 성능 실측(IMP-033)은 Kafka가 있던 환경이다. 다음 측정은 "Kafka 제거만"과 "자원 최적화"를 나눠 잰다(설계 문서 §7.5).
- [[ADR-012]] "2 AZ + 브로커 3개" 대안의 서술을 고친다: 2 AZ면 한 **AZ**에 브로커 2개가 몰린다(같은 노드라는 뜻은 아니다) — 그 AZ가 죽으면 ISR 1개로 쓰기 중단.

## 9. 확인하지 못한 것
- 실제 사용자 관점에서 `order.paid` 알림이 늦거나 빠질 때의 체감 — 측정 없음(폴링이 없어 재연결·재진입 전까지 멈출 수 있다 — 제거 후에도 같은 성질이라 별도 개선 후보).
- Kafka 브로커의 실제 CPU·메모리 사용량(requests가 없어 스케줄 관점 몫이 0) — 자원 산정에서 측정 데이터로 확인.
