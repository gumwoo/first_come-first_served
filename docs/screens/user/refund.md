# U-REFUND · 예매 취소 / 환불 신청

- ref: `assets/screens/사용자_예매 취소 환불 신청.png`
- route: `/me/orders/:id/refund`
- slice: S06

## 목적
예매 취소 + 환불 수수료 계산 후 환불 예정 금액 확정.

## 상태(states)
- `default`
- `confirm` — 취소 확정 모달
- `done` — 취소 완료

## 사용 API
- `GET /me/orders/:id`
- `POST /me/orders/:id/refund`

## 화면 요소 (DoD 체크리스트)
- [ ] 예매 상세 요약(공연/좌석/결제금액)
- [ ] 취소 수수료 안내(기간별 수수료율 표)
- [ ] 환불 예정 금액 계산 박스
- [ ] 취소 사유 선택
- [ ] 유의사항 동의
- [ ] CTA: 취소 / 환불 신청(destructive) / 이전으로

## 연결
- 환불 신청 → 완료 후 /me/orders
