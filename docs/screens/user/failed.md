# U-FAILED · 예매 실패

- ref: `assets/screens/사용자_예매 실패.png`
- route: `/orders/:id/failed`
- slice: S05

## 목적
결제 실패(승인거절/네트워크/시간초과 등)로 예매가 완료되지 못했을 때 안내 + 재시도.

## 상태(states)
- `failed` — 사유 메시지 표시(`PAYMENT_FAILED` 등)

## 사용 API
- `GET /orders/:id` (실패 사유)
- 재시도 시 `POST /orders/:id/payments`

## 화면 요소 (DoD 체크리스트)
- [ ] "예매를 완료하지 못했습니다" + 실패 아이콘/사유
- [ ] 공연 요약 카드 + 결제 금액
- [ ] CTA: 다시 시도 / 다른 결제수단 선택 / 이벤트 상세로
- [ ] 안내 사항(좌석 선점 해제 가능성 등)
- [ ] 환불/재시도/고객센터 보조 카드

## 연결
- 다시 시도 → /orders/:id/pay · 좌석 만료 시 → /queue/expired
