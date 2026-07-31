# ADR-010 · 트랜잭셔널 아웃박스 — DB↔Kafka 정합성(exactly-once 발행), 정산은 분리

- 상태: **Accepted** (1단계 아웃박스 구현 완료 / 2단계 정산은 후속)
- 날짜: 2026-07-31
- 슬라이스: S08(정합성 보강). 1단계 아웃박스 / 2단계 PG 정산·보상.
- 관련: [[ADR-008]](Kafka 이벤트 백본·DLQ), [[ADR-006]](주문 상태 전이 원자화), [[TS-011]](§한계 ③ PG 보상), IMP-011(예정)

## 맥락
현재 `order.paid` 발행 경로는 결제 트랜잭션 커밋 **후**에 Kafka로 보낸다:
`PaymentService.finalizePaid` → `events.publishEvent(OrderEvent)` → `OrderEventKafkaBridge`
`@TransactionalEventListener(AFTER_COMMIT)` → `kafkaTemplate.send(...)`(실패는 로그 후 삼킴).

이 구조엔 구멍이 있다 — **AFTER_COMMIT은 DB 트랜잭션 바깥**이라, 커밋은 됐는데 `send` 직전/도중
**프로세스 크래시**하거나 **브로커 다운**이면 이벤트가 **영구 유실**된다. ADR-008이 "SSE는 best-effort,
DB가 진실원"으로 이 유실을 감수했지만, 이는 이 프로젝트가 내세우는 "정확히 한 번"의 **발행 축이 비어 있음**을
뜻한다. 분산 트랜잭션(XA)으로 DB와 Kafka를 한 커밋에 묶는 건 무겁고 우리 스택에 맞지 않는다.

별개로, TS-011 §한계 ③이 남긴 **다른 종류의 구멍**이 있다: PG 승인은 성공했는데 좌석이 만료 sweep에 풀려
`finalizePaid`가 롤백되면, **DB엔 아무 흔적도 안 남고**(tx 롤백) PG에만 미아 승인이 남는다. 이건 "DB에 커밋된
사실을 밖으로 전달"하는 아웃박스로는 닫을 수 없다(닫을 DB 행 자체가 없음).

제약: 로컬 gradle 없음 → 백엔드는 CI(Testcontainers) 검증. 동시성 원칙(분산락 금지, ADR-002/003/006) 유지.
이벤트는 `order.paid` 한 종류부터 점진 적용(ADR-008의 "점진 이관" 계승).

## 결정
### 1단계 — 트랜잭셔널 아웃박스 (내부 DB ↔ Kafka 정합성)
1. **비즈니스 tx와 같은 트랜잭션에서 아웃박스 행을 INSERT.** `finalizePaid`의 `events.publishEvent(...)`를
   `outbox_events` INSERT로 대체 — 주문/결제 상태 전이와 **원자적으로** 커밋/롤백된다(유령·유실 0).
2. **폴링 릴레이가 발행.** `@Scheduled + @SchedulerLock("outbox-relay")`가 PENDING을 `created_at` 순으로
   읽어 `kafkaTemplate.send(topic, aggregateId, payload)` → 성공 시 `PUBLISHED` 마킹, 실패 시 `attempts++`로
   두고 다음 틱 재시도. **단일 릴레이(ShedLock)**로 순서·중복을 통제한다.
3. **publish-then-mark 순서.** 발행 성공 후에만 마킹한다 → 크래시 시 재발행되는 **at-least-once**. (mark-then-publish는
   지금과 같은 유실 버그 재발이라 기각.)
4. **소비자 멱등 — Redis SETNX(경량).** 발행 메시지에 `eventId(UUID)`를 실어, `OrderEventConsumer`가 처리 전
   `SETNX dedup:order-event:{id}` TTL 24h로 중복을 흡수한다. **SSE 전달은 중복의 업무 영향이 작고 영구 감사가
   불필요**하므로 경량 SETNX가 적절 — 금전·재고·정산을 수행하는 소비자였다면 `processed_events` 테이블 +
   비즈니스 트랜잭션(인박스 패턴)으로 묶었을 것.
5. **멱등 저장소 장애는 fail-open.** Redis가 죽었다고 소비자를 실패시키면 재시도·DLQ로 **실시간 알림 전체가
   멈춘다.** SSE는 중복 피해가 작으므로 Redis 장애 시 **경고 로그 후 broadcast는 그대로 수행**(중복 허용).
   "멱등도 best-effort, SSE도 best-effort"로 일관.
6. **프로듀서 멱등 보강.** `enable.idempotence=true` + `acks=all`로 프로듀서 재시도 중복을 값싸게 억제.
7. **보존/청소.** 발행 성공은 `PUBLISHED`로 남겨 운영 가시성(언제·몇 번째 시도에 나갔나)을 준다.
   `@Scheduled + @SchedulerLock("outbox-purge")`가 매일 새벽 `status='PUBLISHED' AND published_at < now()-7d`만
   삭제한다. **PENDING·실패 이벤트는 절대 지우지 않는다.**

### 2단계 — 정산(Reconciliation) + 보상 (외부 PG ↔ 내부 상태 정합성)
8. ③의 "PG 승인 후 크래시" 구간은 아웃박스가 아니라 **정산 잡**으로 닫는다. 주기적으로 **"PG엔 승인, 우리
   주문은 PENDING(만료 임박/경과)"** 인 결제를 찾아 PG 상태와 대조하고, 불일치면 **승인 취소(void)·상태 복구**로
   보상한다. `finalizePaid`의 즉시 `gateway.refund` 보상(TS-011 §7)은 유지하고, 정산은 그 **크래시 구간의
   안전망**이다.

> 한 문장 서사: **분산 트랜잭션 없이 — 내부 DB↔메시지 브로커 정합성은 Outbox로, 외부 PG↔내부 주문 상태
> 정합성은 Reconciliation·보상으로 해결한다.**

## 고려한 대안
- **현행 유지(AFTER_COMMIT best-effort)**: 크래시/브로커 다운 시 발행 유실. "정확히 한 번" 논지의 구멍 → 기각.
- **CDC(Debezium로 WAL 테일링)**: 폴링이 없고 강력하나 **새 인프라(커넥터·Connect 클러스터)** 가 크다.
  이 규모·단일 이벤트엔 과함 → 기각(후속 확장 여지로만).
- **Kafka EOS(트랜잭션 프로듀서)**: 프로듀서 재시도 중복은 줄지만 **DB↔Kafka 원자성은 못 준다**(진짜 문제).
  아웃박스가 정답 → EOS는 `enable.idempotence`만 값싸게 채택하고 전면 트랜잭션은 기각.
- **소비 멱등을 processed_events 테이블로**: 금전/재고/정산 소비자엔 옳지만, 우리 소비자는 SSE 전달뿐이라
  과함. **SETNX 경량**으로 충분 → 채택(성격이 바뀌면 테이블로 승격).
- **정산을 아웃박스에 통합**: 대상이 다르다(롤백돼 DB에 없는 외부 부수효과). 억지로 묶으면 개념이 흐려짐 →
  같은 S08 안의 **별도 2단계**로 분리.
- **발행 즉시 삭제**: 저장은 아끼나 언제·몇 번 만에 나갔는지·재처리 판단·테스트 증거가 사라짐 → 7일 보존 채택.

## 결과 / 한계 (정직)
- **1단계 구현 완료**: `V14__outbox_events.sql`, `OutboxEvent`/`OutboxEventRepository`, `OutboxRelay`(발행+purge),
  `finalizePaid`가 같은 tx에 적재, `OrderEventKafkaBridge` **은퇴(삭제)**, `OrderEventConsumer` SETNX 멱등.
  검증: 아웃박스→릴레이→Kafka→SSE 경로 + PUBLISHED 마킹, 중복 흡수, tx 원자성(커밋 시 적재/롤백 시 0행).
- **2단계 정산은 미착수**: PG 승인 후 크래시로 롤백된 미아 승인(§8)은 아직 열려 있다 — 후속에서 닫는다.
- **IMP-011 실증은 후속**: 브로커 다운 상태에서 K건 결제 → 구 방식은 도달 0/K(유실), 아웃박스는 복구 후 K/K.
  `benchmarks/outbox-delivery-{before,after}.json`에 박제 예정(현재는 기능 검증까지).
- **at-least-once의 대가 = 중복**: 재발행·리밸런스로 소비자가 같은 이벤트를 두 번 받을 수 있다 → SETNX 멱등으로
  흡수. 단 fail-open(§5) 구간에선 중복 broadcast 가능(SSE라 무해).
- **폴링 지연**: 릴레이 주기만큼 발행이 늦다(초 단위). 실시간성이 필요하면 주기를 줄이거나 CDC 후속.
- **SETNX TTL 경계**: 재전달 최대 창(재시도+릴레이+브로커 다운 지속+Lag)이 24h를 넘으면 중복이 샐 수 있으나,
  그 정도 장애는 예외적이고 SSE 중복은 무해 → 감수.
- **2단계 정산의 실 PG 의존**: PG 상태 대조는 실 PG 조회 API(Toss 결제 조회)를 전제 — Mock 구간에선 시뮬레이션.
  가상계좌(vbank) 경로의 보상 의미(입금 반환)는 실 PG 배선 시 별도 검토.
- **범위**: `order.paid` 한 경로만 아웃박스 이관. 좌석/큐 SSE는 best-effort 직접 유지(ADR-008 계승).
