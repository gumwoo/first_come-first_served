# U-COMPLETE · 예매 완료

- ref: `assets/screens/사용자_예매 완료.png`
- route: `/orders/:id/complete`
- slice: S05

## 목적
결제 성공 후 예매 확정 + QR 모바일 티켓 발급.

## 상태(states)
- `confirmed`

## 사용 API
- `GET /orders/:id` (확정 상태/티켓)

## 화면 요소 (DoD 체크리스트)
- [ ] "예매가 완료되었습니다" + 단계 스테퍼 전체 완료
- [ ] 예매 정보(예매번호/공연/일시/좌석/금액)
- [ ] QR 모바일 티켓 + 입장 안내
- [ ] 티켓 이용 안내 / 취소·환불 안내
- [ ] CTA: 모바일 티켓 보기 / 예매 내역 / 메인으로

## 연결
- 예매 내역 → /me/orders
