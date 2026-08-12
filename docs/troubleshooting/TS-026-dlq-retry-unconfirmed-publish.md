# TS-026 · DLQ 재처리가 브로커 확인 없이 "재처리했다"고 기록했다

- 슬라이스: S07(운영 DLQ) — 발견은 2026-08-13 리뷰
- 날짜: 2026-08-13
- 유형: 결함 발견 → 수정 — **같은 문제를 코드베이스가 두 가지로 처리하고 있었다**
- 관련: [ADR-010](../decisions/ADR-010-transactional-outbox.md)(아웃박스·소비자 멱등),
  [[TS-020]](독성 메시지 → DLT), [[TS-024]](같은 계열의 결함 — 확인 전 상태 확정)
- 상태: **해결** — 재발행도 브로커 확인 후에만 마킹하도록 맞췄다. 회귀 테스트 2건 추가

## 0. 무엇이 문제였나

이 프로젝트의 메시지 신뢰성 설계는 이렇게 이어진다.

```
결제 tx ─┬─ Order/Payment/Seat
         └─ OutboxEvent(PENDING)      ← 같은 커밋
              ↓ COMMIT
         OutboxRelay ── send().get() ── 브로커 ACK ── PUBLISHED
              ↓ 소비 실패
         300ms × 2 재시도 → order-events.DLT
              ↓
         DlqConsumer → dlq_messages(PENDING)
              ↓
         운영자 판단 → POST /admin/dlq/{id}/retry → 원본 토픽 재발행 → RETRIED
```

**마지막 화살표 하나만 "확인 없이 확정"이었다.**

```java
// AdminDlqService.retry() — 수정 전
kafkaTemplate.send(message.getTopic(), String.valueOf(event.orderId()), event);  // 비동기
message.markRetried();
```

`OutboxRelay`는 처음부터 이렇게 하고 있었다.

```java
kafkaTemplate.send(...).get(sendTimeoutMs, TimeUnit.MILLISECONDS); // 브로커 확인 후에만 마킹
row.markPublished();
```

**같은 문제(발행 성공을 어떻게 아는가)에 대한 처리가 코드베이스 안에서 갈려 있었다.**

## 1. 왜 나쁜가 — 세 겹이다

**① 비동기 전송을 기다리지 않는다.** `send()`는 즉시 반환하고 실제 전송은 나중이다.

**② `@Transactional`이라 DB 커밋이 발행보다 먼저 확정된다.** `markRetried()`는 JPA 더티체킹이라
메서드 끝에 커밋된다. 순서가 이렇게 된다.

```
send() 호출(아직 안 나감)
markRetried()
트랜잭션 COMMIT        ← DB엔 이미 RETRIED
...그 뒤에 브로커 실패 응답 도착
```

실패 콜백을 아무도 보지 않으므로 **조용히 사라진다.**

**③ 그리고 이 실패는 DLT로도 안 들어간다.** DLT는 `DefaultErrorHandler`가 **소비** 실패를
처리하는 장치다. **발행** 실패는 그 경로에 없다.

세 겹이 겹치면 결과는 이렇다 — **DLQ 메시지가 "재처리했다"고 표시된 채 실제로는 유실된다.**
원본은 이미 DLT에서 DB로 옮겨왔고 상태가 `RETRIED`로 바뀌었으니 운영자 목록에서도 사라진다.
**"실패 메시지를 버리지 않는다"는 이 설계의 전제가 마지막 한 걸음에서 깨진다.**

⚠️ **운영에서 이 유실이 실제로 발생했다는 증거는 없다.** 코드 경로로 확인한 것이고,
`RETRIED`인데 소비되지 않은 사례를 찾아본 것은 아니다.

## 2. 부수적으로 드러난 것 — 실패 두 종류가 뭉뚱그려져 있었다

```java
try {
    OrderEvent event = objectMapper.readValue(message.getPayload(), OrderEvent.class);
    kafkaTemplate.send(...);
} catch (Exception e) {
    throw new BusinessException(ErrorCode.VALIDATION_ERROR, e);   // 둘 다 400
}
```

**페이로드 파손과 발행 실패는 운영 판단이 정반대다.**

| 실패 | 성격 | 운영자가 할 일 |
|---|---|---|
| 페이로드 파손 | 영구적 — 몇 번을 눌러도 실패한다 | **폐기**(discard) |
| 발행 실패 | 일시적 — 브로커가 돌아오면 된다 | **나중에 다시 시도** |

둘 다 400으로 나가면 운영자는 이 구분을 할 수 없다. 특히 [[TS-020]]의 독성 메시지가 여기로
오는데(역직렬화 자체가 안 되는 바이트가 `payload`에 그대로 보존돼 있다), 그건 재시도로
절대 해결되지 않는 유형이다.

## 3. 조치

### 3-1. 브로커 확인 후에만 마킹

```java
try {
    kafkaTemplate.send(message.getTopic(), String.valueOf(event.orderId()), event)
            .get(sendTimeoutMs, TimeUnit.MILLISECONDS); // 브로커 확인 후에만 마킹
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    throw new BusinessException(ErrorCode.INTERNAL_ERROR, e);
} catch (Exception e) {
    log.warn("[dlq] 재발행 실패 id={} topic={}: {}", id, message.getTopic(), e.toString());
    throw new BusinessException(ErrorCode.INTERNAL_ERROR, e);
}
message.markRetried();
```

**`@Transactional`을 그대로 둔 것이 여기서는 오히려 맞다.** 발행이 실패하면 예외로 롤백돼
상태가 `PENDING`으로 남고, 운영자가 다시 시도할 수 있다. 확인을 넣는 순간 트랜잭션 경계가
**옳은 의미**를 갖게 됐다.

타임아웃은 `dlq.send-timeout-ms`(기본 3000)로 분리했다. 아웃박스와 기본값은 같지만 축이 다르다 —
아웃박스는 스케줄 틱을 바운드하는 값이고, 이쪽은 관리자 요청의 응답 시간이다.

### 3-2. 실패 두 종류를 갈랐다

파싱은 `VALIDATION_ERROR`(400), 발행은 `INTERNAL_ERROR`(500). 400이면 폐기 대상, 500이면
나중에 다시 누르면 된다는 뜻이 된다.

### 3-3. 회귀 테스트 2건

| 테스트 | 무엇을 고정하나 |
|---|---|
| `재발행이_실패하면_RETRIED로_바꾸지_않는다` | 발행 실패 시 상태가 `PENDING`으로 남는다 |
| `깨진_페이로드는_발행을_시도하지_않고_400으로_구분된다` | 두 실패의 코드가 갈린다 |

발행 실패를 **결정적으로** 만들기 위해 Kafka 토픽명 규칙을 위반한 이름(`invalid topic name!`)을
쓴다 — 허용 문자는 `[a-zA-Z0-9._-]`다. 브로커를 죽이는 것보다 재현이 확실하고 빠르다.

## 4. 남은 것 / 하지 않은 것

- ⚠️ **발행 성공 후 커밋이 실패하면 중복 발행이 된다.** 컨슈머가 `eventId` 멱등을 갖고 있어
  (ADR-010) 흡수되지만, 이 경로는 **아웃박스와 같은 at-least-once 교환**이다. exactly-once가
  아니다.
- ⚠️ **DB 트랜잭션이 열린 채로 최대 3초 외부 호출을 기다린다.** 관리자 API라 빈도가 낮아
  받아들였다. 결제 경로(`PaymentService`)에도 같은 구조가 있고 그쪽은 부하가 실리는 자리라
  성격이 다르다 — 별도 판단 대상이다.
- **DLQ 자동 배치 재처리는 여전히 없다.** 현재는 운영자가 목록을 보고 건별로 누르는 구조다.
  자동화하려면 "무엇이 재시도 가능한 실패인가"를 먼저 분류해야 하는데(§2가 그 첫걸음이다),
  그 판단 없이 일괄 재시도하면 독성 메시지를 무한히 되돌린다.

## 5. 배운 점

**[[TS-024]]와 같은 계열이다.** 거기서는 승격 커밋이 Lua 밖으로 새어 "확인 전 상태 확정"이
일어났고, 여기서는 Kafka 발행이 확인 전에 확정됐다. 층위는 다르지만 형태가 같다.

> **외부 시스템에 맡긴 일은, 그 시스템이 받았다고 말하기 전까지 우리 상태를 바꾸지 않는다.**

그리고 이번 건은 **한쪽에 이미 정답이 있었다.** `OutboxRelay`가 처음부터 `.get()`으로 확인하고
있었으니, 새 개념을 배워야 했던 게 아니라 **같은 규율을 다른 경로에 적용하지 않은 것**뿐이다.
코드베이스 안에서 같은 문제에 대한 처리가 갈리는지 보는 것이 새 결함을 찾는 것보다 싸다.

## 6. 이 흐름을 뭐라고 말할 수 있나 (정직한 표현)

리뷰에서 표현 하나를 교정받았다.

- ❌ "DLQ에 쌓인 실패 메시지를 **스케줄러가 배치로 자동 재처리**했습니다" — 그런 코드는 없다.
- ✅ "이벤트 발행 유실 방지를 위해 **Transactional Outbox**를 적용하고 미발행 이벤트를 배치로
  재처리했으며, 처리 실패 메시지는 재시도 후 **DLQ로 격리하고 DB에 보관**해, 운영자가 원인을
  확인한 뒤 Admin API로 **선택적으로 재처리하거나 폐기**할 수 있도록 구성했습니다."

"배치 재처리"는 **아웃박스 미발행분**에 해당하는 말이고, **DLQ 재처리**는 수동이다.
둘을 섞으면 없는 기능을 말하게 된다.
