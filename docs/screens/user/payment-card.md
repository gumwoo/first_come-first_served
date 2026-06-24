# U-PAYMENT-CARD · 결제 (카드)

- ref: `assets/screens/사용자_결제 방법 선택.png`
- route: `/orders/:id/pay`
- slice: S05

## 목적
선점 좌석에 대한 결제. 카드 결제 기본 탭. 결제 제한시간 카운트다운.

## 상태(states)
- `default` (카드 탭)
- `processing` — 결제 진행
- `expired` — 결제시간 만료

## 사용 API
- `POST /orders` (holdId → orderId)
- `POST /orders/:id/payments` (method=card)
- `GET /orders/:id`

## 화면 요소 (DoD 체크리스트)
- [ ] 단계 스테퍼(좌석→결제→완료)
- [ ] 결제수단 탭: 카드 / 간편결제 / 무통장입금
- [ ] 카드 결제 폼(약관 동의)
- [ ] 우측 주문 요약(공연/좌석/금액/총 결제금액)
- [ ] 결제 제한시간 카운트(09:42)
- [ ] 결제 진행(primary) / 예약 취소

## 연결
- 성공 → /orders/:id/complete · 실패 → /orders/:id/failed
- 간편결제 탭 → payment-easy / 무통장 탭 → payment-vbank
