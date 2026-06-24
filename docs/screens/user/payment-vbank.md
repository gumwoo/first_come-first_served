# U-PAYMENT-VBANK · 입금 대기 (무통장입금)

- ref: `assets/screens/사용자_입금 대기.png`
- route: `/orders/:id/pay` (무통장입금 → 입금대기)
- slice: S05

## 목적
가상계좌 발급 후 입금 대기. 입금 기한 내 입금 확인 시 예매 확정.

## 상태(states)
- `vbank_waiting` — 가상계좌 발급, 입금 기한 카운트
- `paid` — 입금 확인 → 완료
- `expired` — 입금 기한 초과 → 자동 취소

## 사용 API
- `POST /orders/:id/payments` (method=vbank) → 가상계좌/입금기한
- `GET /orders/:id` (입금확인 폴링/웹훅)

## 화면 요소 (DoD 체크리스트)
- [ ] 단계 스테퍼(입금 대기 단계 강조)
- [ ] 가상계좌(은행/계좌번호/예금주/입금액)
- [ ] 입금 기한 일시(예: 2026.07.01 23:59) + 카운트
- [ ] 입금 안내/유의사항
- [ ] "입금 완료 확인" / 예약 취소

## 연결
- 입금확인 → /orders/:id/complete · 기한초과 → 자동취소
