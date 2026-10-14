# ADR-028 · Kafka 재평가 — 제품 요구로는 과하다, 아웃박스 → Redis Pub/Sub으로 대체를 제안 (결정 전)

- 상태: **Proposed(결정 대기)** — 이 문서는 사실·대안·유지/제거 조건을 정리하고 "제거"를 **제안**한다. 결정은 사용자가 한다
- 날짜: 2026-10-14
- 관련: [[ADR-008]](Kafka 이벤트 백본 + DLQ — 이 ADR이 재평가하는 결정), [[ADR-010]](트랜잭셔널 아웃박스), [[ADR-012]](3 AZ·멀티브로커),
  [[IMP-016]](브로커 1대 장애 실증), [[TS-023]](다중 Pod SSE 전달), `docs/deployment/portfolio-framing-and-decisions.md`(도입 의도),
  [operating-requirements.md](../deployment/operating-requirements.md)(운영 요구사항 결정), [workload-responsibilities.md](../deployment/workload-responsibilities.md)

## 1. 맥락 — 왜 도입했나 (당시 기록)
- [[ADR-008]]: 실시간 이벤트(`order.paid` 등)를 앱 메모리에서 바로 SSE로 broadcast하던 구조의 한계 —
  (1) 이벤트 백본이 없어 소비자 확장·재처리·감사가 불가능, (2) 결제 **커밋 전에** broadcast돼 "롤백됐는데 알림은 나간" 유령 이벤트 여지.
- `portfolio-framing-and-decisions.md`: "라이브 K8s + 멀티브로커 Kafka를 **의도적으로 채택**" — 근거 "(a) 분산/K8s/Kafka 운영 역량 **시연 목적**, (b) 폭주+정합성 도메인이라 확장·복제가 의미 있음".
- 즉 도입 근거는 **운영 실험(시연)**이었고, "제품 요구가 Kafka를 필요로 하는가"는 따로 평가하지 않았다. 이 ADR이 그 빈칸이다.

## 2. 지금 실제 쓰임 (코드 사실)
- 아웃박스에 쌓이는 이벤트는 **`order.paid` 하나**다(`PaymentService.appendOutbox("order.paid", …)` 한 곳).
  경로: 결제 트랜잭션에서 아웃박스 적재 → 릴레이(1초, ShedLock)가 Kafka `order-events`로 발행 → `OrderEventConsumer`(eventId Redis SETNX 멱등) → SSE(파드 간 전달 — [[TS-023]] A3, Redis 경유는 TS가 "강한 추론"으로 표기).
- `payment.vbank.deposited`는 Kafka를 거치지 않고 `OrderSseRegistry`로 바로 broadcast한다.
- 프론트(`useOrder.ts`)는 주문 SSE 이벤트를 받으면 **주문을 다시 조회**한다 — 알림은 화면 갱신의 계기일 뿐, 알림이 빠져도 표시가 틀리지 않는다(재조회·재방문 때 DB 상태가 보인다).
- 마지막 구간(Redis Pub/Sub → SSE)은 메시지를 보존하지 않는다. Kafka의 내구성이 **브라우저 도착**을 보장하지 않는다.
- 대기열 진입·승격, 좌석 선점, 주문·결제 정합성은 Kafka를 거치지 않는다(Redis Lua, PostgreSQL 조건부 UPDATE·트랜잭션).
- DLQ: 소비자 예외가 재시도 뒤 `order-events.DLT` → `dlq_messages`(DB) 적재, 사람이 admin API로 재처리. 소비자가 하는 일이 SSE 전달뿐이라 DLQ에 쌓일 실패의 실익이 작다.

## 3. Kafka의 운영 비용 (설정 사실)
| 항목 | 지금 |
|---|---|
| 브로커 | 3개, KRaft, **controller와 broker를 한 프로세스가 겸함**(`roles: [controller, broker]`) — 공식 문서가 중요한 프로덕션에 권하는 역할 분리 구성은 아니다 |
| 자원 | 브로커 파드에 **CPU·메모리 requests·limits가 없다**(`KafkaNodePool`에 `resources` 없음) — 스케줄러가 몫을 잡지 않고, 노드 메모리 압박 시 먼저 퇴거될 수 있다 |
| 스토리지 | PVC 10Gi × 3(gp2 EBS), AZ 종속 |
| 배치 | 가용 영역 분산 `DoNotSchedule` — AZ별 노드 그룹·최소 노드가 필요한 이유 중 하나([[ADR-012]]) |
| 운영 절차 | Strimzi 운영자 설치, bring-up·철거 단계, PVC 정리, 테스트의 Testcontainers Kafka |
| 복제 | RF 3, min.insync 2, acks=all — 브로커 1대 장애에도 쓰기 지속([[IMP-016]] 실측) |

## 4. 원래 문제는 Kafka 없이 풀리는가
| ADR-008의 문제 | Kafka 없이 |
|---|---|
| 커밋 전 유령 알림 | **아웃박스**가 이미 해결한다 — 결제와 같은 트랜잭션에 이벤트를 쓰고, 커밋된 것만 릴레이가 내보낸다([[ADR-010]]) |
| 감사·재처리 | 아웃박스 행(발행 시각·시도 횟수·실패)이 기록이다. 재처리 = PENDING/실패 행 재발행 |
| 소비자 확장 | 지금 소비자는 "SSE 전달" 하나다. 파드 간 전달은 이미 Redis 팬아웃이 한다 |

## 5. 대안
| 안 | 내용 | 장점 | 대가 |
|---|---|---|---|
| A. 유지 | 지금 그대로, 한계를 문서에 명시 | 변경 없음, Kafka 운영 실험이 코드에 남음 | 위 운영 비용, 면접에서 "알림 하나에 브로커 3대?"에 답하기 어려움 |
| **B. 제거 — 아웃박스 → Redis Pub/Sub(제안)** | 릴레이가 Kafka 대신 Redis Pub/Sub으로 발행(SSE 팬아웃 채널), 소비자·DLQ·Strimzi 제거 | 구성 요소 축소(브로커 3·EBS 3·운영자), 아웃박스로 "이벤트 발생" 내구성은 유지, 프론트·SSE 계약 변화 0 | Kafka 실험이 코드에서 사라짐(기록은 남김), 재생(replay)·다중 소비자 능력 상실 — 지금은 쓰지 않음 |
| C. 아웃박스 → SQS | 관리형 큐로 비동기 작업 | 재시도·DLQ 관리형 | AWS 의존, 지금 요구에는 역시 과함 |
| D. MSK | 관리형 Kafka | 브로커 운영 부담 감소 | 비용, 필요성 문제는 그대로 |

## 6. 제안 — B (결정 대기)
- 유지: 아웃박스 적재(결제 트랜잭션과 원자), 릴레이(at-least-once), 커밋된 것만 알림, SSE + 재조회.
- 제거: Kafka 프로듀서·`OrderEventConsumer`·DLQ 소비자와 admin 재처리(대상 재설계), Strimzi·Kafka 매니페스트, Testcontainers Kafka.
- 바뀌는 의미(설계 때 확정): 릴레이의 "발행 성공" = Redis `PUBLISH` 성공(구독자 수와 무관). 중복 전달은 프론트가 재조회만 하므로 영향이 작다 — 지금의 소비자 멱등(SETNX)이 계속 필요한지는 설계 때 판단.
- **다시 도입할 조건**: 같은 이벤트를 독립적으로 처리할 소비자가 둘 이상(정산·통계·외부 알림 등), 이벤트 재생 요구, Redis Pub/Sub로 감당 못 할 이벤트 처리량, 집계 단위 순서 보장 요구.
- 남기는 것: [[ADR-008]]·[[ADR-010]]·[[IMP-016]] 등 Kafka 문서는 지우지 않고 "ADR-028로 대체됨"을 표시해 **운영 실험의 기록**으로 둔다.

## 7. 제거한다면 — 단계별 계획(초안)
1. 앱·테스트: 릴레이 발행 대상 교체, 소비자·DLQ 제거, 테스트 교체(CI Testcontainers), 계약(`contracts/events.yaml`의 `dlq_suffix` 등)·하네스 규칙 정리.
2. k8s·인프라: Strimzi·Kafka 매니페스트, bring-up·철거 단계, 관련 하네스 규칙·fixture. EBS CSI는 Prometheus PVC가 쓰므로 유지 여부를 따로 확인.
3. 문서: ADR-008·010 상태, README·HARNESS·배포 문서, 장애 시험 스크립트.
- 각 단계는 PR → G1 → CI. 실제 클러스터 동작은 다음 측정 세션에서 확인.

## 8. 영향
- 평시 노드 하한의 이유 하나(Kafka 브로커 AZ 분산)가 사라진다. AZ별 노드 그룹·최소 노드는 RDS·Redis·api·대기열의 가용성 목표(G1)로 다시 판단한다 — 노드 수는 자원 산정에서 계산.
- [[ADR-012]] "2 AZ + 브로커 3개" 대안의 서술을 고친다: 2 AZ면 한 **AZ**에 브로커 2개가 몰린다(같은 노드라는 뜻은 아니다) — 그 AZ가 죽으면 ISR 1개로 쓰기 중단.

## 9. 확인하지 못한 것
- 실제 사용자 관점에서 `order.paid` 알림이 늦거나 빠질 때의 체감(재조회 주기와 함께) — 측정 없음.
- Kafka 브로커의 실제 CPU·메모리 사용량(requests가 없어 스케줄 관점 몫이 0) — 자원 산정에서 측정 데이터로 확인.
