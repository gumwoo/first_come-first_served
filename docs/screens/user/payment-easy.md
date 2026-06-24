# U-PAYMENT-EASY · 결제 (간편결제)

- ref: `assets/screens/사용자_결제 진행 간편결제.png`
- route: `/orders/:id/pay` (간편결제 탭)
- slice: S05

## 목적
카카오페이/네이버페이/토스/페이코 등 간편결제 분기.

## 상태(states)
- `default` (간편결제 탭)
- `processing` / `expired`

## 사용 API
- `POST /orders/:id/payments` (method=easy, provider=kakaopay|naverpay|toss|payco)

## 화면 요소 (DoD 체크리스트)
- [ ] 결제수단 탭 중 "간편결제" 활성
- [ ] 간편결제 provider 선택(카카오/네이버/토스/페이코 로고)
- [ ] 약관 동의
- [ ] 우측 주문 요약 + 결제 제한시간 카운트
- [ ] 간편결제 진행(primary) / 예약 취소

## 연결
- 성공 → /orders/:id/complete · 실패 → /orders/:id/failed
