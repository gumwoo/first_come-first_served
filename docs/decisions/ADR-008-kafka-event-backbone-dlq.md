# ADR-008 · Kafka 이벤트 백본 + DLQ — SSE 라스트홉 유지, AFTER_COMMIT 발행

- 상태: Accepted
- 날짜: 2026-07-24
- 슬라이스: S07(운영, Phase 4)
- 관련: [[ADR-006]](주문 상태 전이), [[ADR-007]](관리자 인증), `docs/db/dlq_messages.md`

## 맥락
S07 이전까지 실시간 이벤트(`order.paid` 등)는 **애플리케이션 메모리 안에서 직접 SSE로 broadcast**됐다.
단일 서버 데모에선 동작하지만 (1) 이벤트 백본이 없어 소비자 확장·재처리·감사가 불가능하고
(2) 결제 트랜잭션 **커밋 전에** broadcast가 불려 "롤백됐는데 알림은 나간" 유령 이벤트 여지가 있었다.
S07 운영 요건(실패 메시지 관측·재처리)을 채우려면 이벤트 파이프라인을 실체화해야 한다.

제약: 로컬 gradle 없음 → 백엔드는 CI(Testcontainers)에서만 검증. 단일 노드 데모(파티션/복제 1).
동시성 원칙(분산락 금지, ADR-002/003/006)은 유지.

## 결정
1. **Kafka를 이벤트 백본, SSE는 마지막 홉으로 유지**. 기존 SSE 전달 계층을 걷어내지 않고 그 앞단만
   Kafka로 바꾼다: `Producer → order-events 토픽 → @KafkaListener consumer → OrderSseRegistry.broadcast`.
   브라우저 push 방식(SSE)은 그대로라 프론트 계약 변화 0.
2. **커밋 후에만 발행(AFTER_COMMIT)**. 결제 서비스는 도메인 이벤트를 Spring 이벤트로 발행하고,
   `@TransactionalEventListener(AFTER_COMMIT)` 브리지가 그때 Kafka로 보낸다. 롤백 시 미발행 →
   유령 `order.paid` 구조적 차단.
3. **발행 실패는 삼킨다(best-effort 알림)**. 결제는 이미 커밋됐고 **DB가 진실원**. 브로커 다운이
   결제를 실패로 되돌리면 안 된다. `producer max.block.ms=3000`으로 브로커 불가 시 send() 블록도 바운드.
4. **DLQ는 DB에 적재**. 컨슈머 예외 → `DefaultErrorHandler`(FixedBackOff 300ms×2회) 소진 →
   `DeadLetterPublishingRecoverer`가 `order-events.DLT` 발행 → `DlqConsumer`가 `dlq_messages`에
   PENDING으로 저장(원본 토픽·예외 헤더 포함). 운영은 `GET /admin/dlq`·retry(원본 재발행→RETRIED)·
   discard(→DISCARDED)로 처리하고, 대시보드에 PENDING 적체를 노출.
5. **점진 이관**. 이번엔 `order.paid`만 Kafka 경유. `payment.vbank.deposited`·좌석/큐 이벤트는
   기존 직접 SSE 유지(후속 확대).

## 고려한 대안
- **SSE 전면 폐기 후 Kafka만**: 브라우저는 Kafka를 직접 못 구독한다. 어차피 마지막 홉은 SSE/WebSocket이
   필요 → SSE를 남기고 백본만 얹는 편이 변경 최소·계약 보존 → 채택.
- **커밋 전(또는 트랜잭션 내) 발행**: 롤백 시 유령 이벤트. AFTER_COMMIT이 정확 → 기각.
- **발행 실패 시 결제 롤백/재시도**: 이미 커밋된 결제를 알림 실패로 되돌리는 건 본말전도.
   알림은 best-effort로 두고 유실은 감수(DB 재조회로 복구 가능) → 기각.
- **DLQ를 Kafka DLT에만 두고 DB 미적재**: 운영자가 토픽을 직접 뒤져야 하고 상태(재시도/폐기) 관리가 없다.
   조회·상태전이·감사를 위해 DB 적재가 필요 → DB 테이블 채택.
- **RetryTopic(@RetryableTopic) 비동기 재시도**: 기능은 강력하나 토픽이 여러 개로 늘고 데모엔 과함.
   단순 backoff + 단일 DLT가 규모에 적합 → 기각.

## 결과 / 한계
- `OrderEventKafkaIntegrationTest`: 커밋 → AFTER_COMMIT → Kafka → consumer → SSE 전체 경로 검증.
- `DlqIntegrationTest`: 소비 강제 실패 → 재시도 소진 → DLT → `dlq_messages` 적재, 재시도/폐기 상태전이.
- **한계 1 — SSE 끝단 미검증**: 통합테스트는 consumer의 broadcast 호출까지만 본다. 브라우저까지의
  SSE 전달은 E2E에서만 확인(후속 admin E2E로 닫을 예정).
- **한계 2 — 알림 유실 가능**: 브로커 다운 시 `order.paid` 알림은 유실(설계상 감수). 사용자는 마이페이지
  재조회로 결제 상태 확인 가능(DB가 진실원).
- **한계 3 — 역직렬화 실패 메시지**: `ErrorHandlingDeserializer`를 붙이지 않아, payload 자체가 깨진
  메시지는 이 DLQ 경로로 안전하게 들어오지 않는다(현재는 "처리 예외"만 DLT 적재). 필요 시 후속 도입.
- **한계 4 — 재시도 누적**: 계속 실패하는 메시지를 재시도하면 DLT 행이 계속 쌓인다(상한 없음).
  운영자가 discard로 정리하는 전제. 자동 폐기 정책은 범위 밖.
- **한계 5 — 단일 노드**: 파티션/복제 1. 처리량·내결함성 확장은 배포/브로커 구성 시점의 별도 결정.
