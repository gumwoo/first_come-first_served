# Domain · S05 주문 / 결제

S04에서 잡아둔 **좌석 선점(hold)** 을 **주문(order)** 으로 바꾸고, 결제 성공 시 표로 확정하는 단계.
핵심 불변식: **돈은 한 번만 받고, 표(QR)도 한 번만 발급한다.**

## 상태 전이 ★ (enums.yaml `OrderStatus.transitions` 와 일치)
```
PENDING ─┬─> PAID              결제 성공(카드/간편 즉시, vbank는 입금 후)
         ├─> EXPIRED           결제 제한시간 초과 / 사용자 포기
         ├─> FAILED            종료성(비재시도) 실패 — 초기엔 미사용
         └─> VBANK_WAITING ─┬─> PAID      입금 확인(웹훅)
                            └─> EXPIRED   입금 기한 초과
PAID → CANCELLED → REFUNDED    (취소/환불은 S06, 여기선 전이 정의만)
```
허용 외 전이 = `INVALID_STATE_TRANSITION`. [T]

- **PAID 외 상태에서 티켓(QR) 발급 금지.** [T]
- 결제 성공 시에만 `order.paid` 발행. [T]
- 상태 변경은 **조건부 UPDATE로 원자화**(동시 승인 vs 만료 sweep 경합 방어, [[ADR-006]]).

### 결제 실패 처리 (결정)
승인 거절/오류는 **재시도 가능**해야 하므로 order를 FAILED로 바꾸지 않는다.
계약상 `FAILED → PAID` 전이가 없어, order=FAILED면 재시도가 막히기 때문.
```
승인 거절/오류(재시도 가능):
  payment = FAILED (레코드), order = PENDING 유지, seat/hold = HELD 유지
  → hold 잔여 TTL 안에서 다른 수단으로 재시도(새 payment 시도)
제한시간 초과 / 포기:
  order = EXPIRED, hold = EXPIRED, seat = AVAILABLE (만료 sweep)
최종 성공:
  order = PAID, seat = SOLD, hold = CONVERTED, QR 발급
```
- `order=FAILED`는 **종료성(비재시도) 실패 전용**(초기엔 미사용, 포기는 EXPIRED로 흡수).
- `/orders/:id/failed` 화면은 order 상태가 아니라 **"마지막 payment 시도 FAILED"** 로 렌더 + 재시도.

## 라이프사이클 (hold ↔ order ↔ seat)
```
POST /orders(holdId)
  → hold 검증(HELD·소유자·미만료) → order(PENDING) + 금액/좌석 스냅샷 → order.created
결제 승인(PAID)
  → orders PENDING→PAID(조건부) → seats HELD→SOLD, hold HELD→CONVERTED → order.paid(SSE)
```
- **결제 제한시간 = hold 잔여 TTL 내.** 좌석 예약과 결제창을 일치(별도 연장 없음).
- hold 검증 실패(남의 hold / 만료) 시 주문 생성 거부. [T]

## 구매 제한
- 1인 1주문 좌석 수 제한(이벤트별 maxPerUser) → 초과 시 `MAX_PER_USER_EXCEEDED`. [T]
- 동일 회차 중복 예매 제한 → `DUPLICATE_BOOKING`. [T]

## 정합성 핵심 — 멱등성
결제 버튼 더블클릭 / PG 웹훅 재전송에도 **결제·티켓·이벤트가 한 번만** 처리돼야 한다.
- `payments.idempotency_key` **UNIQUE** — 같은 시도의 중복 생성 차단.
- 주문 상태 전이는 조건부 UPDATE(`... WHERE status='PENDING'`) — 최초 1회만 성공, 이후는 영향 0행.
- 재시도(사용자 의도)는 **새 idempotencyKey = 새 payment row**로 허용(멱등은 "같은 시도"만 막음).
- 측정: naive(중복 PAID) 재현 → idempotency_key + 조건부 전이 → 이중 PAID 0 (대표 IMP).

## 가격 스냅샷
- `order_items.price` = **주문 시점 가격 스냅샷**. 이후 `event_seat_prices`가 바뀌어도 주문 금액 불변([[ADR-004]] 연장).

## 결제 게이트웨이
- 외부 PG 의존을 **포트-어댑터로 격리**([[ADR-005]]): `PaymentGateway` 인터페이스 + `Mock`(테스트/E2E) / `Toss`(테스트모드) 어댑터.
- 비밀키(TOSS_SECRET_KEY 등)는 **환경변수로만**.
- 웹훅(`POST /webhooks/payments`)은 **서명 검증** 후에만 신뢰. vbank 입금·비동기 승인을 반영.

## 티켓(QR)
- **PAID 시에만** 발급. QR = `orderId + HMAC(secret)` 서명 토큰(위조 방지). 입장 검증 = 서명 재계산.

## 실시간
- 결제 완료/실패/입금확인은 SSE(`/sse/orders/{id}`)로 push + 폴링 폴백(`GET /orders/{id}`).
- 이벤트: `order.created` / `order.paid` / `order.failed` / `payment.vbank.deposited`.
