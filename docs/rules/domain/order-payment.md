# Domain · S05 주문 / 결제

## 상태 전이 ★ (enums.yaml transitions와 일치)
허용 전이만 가능(그 외 `INVALID_STATE_TRANSITION`):
```
PENDING → PAID
PENDING → FAILED
PENDING → VBANK_WAITING → PAID | EXPIRED
PAID    → CANCELLED → REFUNDED
PENDING → EXPIRED            (결제시간 초과)
```
- PAID 외 상태에서 티켓(QR) 발급 금지. [T]
- 결제 성공 시에만 `order.paid` 이벤트 발행. [T]
- 불법 전이 차단은 상태머신 + 전이 테스트로 검증. [T]

## 구매 제한
- 1인 1주문 좌석 수 제한(이벤트별 maxPerUser) → 초과 시 `MAX_PER_USER_EXCEEDED`. [T]
- 동일 회차 중복 예매 제한 → `DUPLICATE_BOOKING`. [T]

## 실시간
- 결제 완료/실패/입금확인은 SSE(`/sse/orders/{id}`)로 push. (횡단: domain-rules.md)
