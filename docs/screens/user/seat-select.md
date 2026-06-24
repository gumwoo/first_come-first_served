# U-SEAT-SELECT · 좌석 선택

- ref: `assets/screens/사용자_좌석 선택.png`
- route: `/events/:id/seats`
- slice: S04

## 목적
좌석맵에서 등급별 좌석 선택 → 선점(HOLD) → 결제 진입. 선점 타이머 동작.

## 상태(states)
- `default`
- `selecting` — 좌석 선택/해제
- `hold` — 선점 성공(holdId, ~5분 타이머)
- `sold-out` — 재고 0 → /events/:id/sold-out
- `hold-expired` — 선점 시간 만료 → /queue/expired

## 사용 API
- `GET /events/:id/seats` — 좌석맵/등급별 잔여
- `POST /events/:id/seats/hold` → holdId
- `DELETE /seats/hold/:holdId`

## 화면 요소 (DoD 체크리스트)
- [ ] 좌석맵(구역/STAGE, 등급별 색상: VIP/R/S 등)
- [ ] 회차/날짜 선택
- [ ] 좌석등급 범례 + 잔여 표시
- [ ] 선택 좌석 목록 + 합계 금액
- [ ] 결제 타이머(09:42 류)
- [ ] "좌석 선택 완료"(primary) / 이전으로

## 연결
- 선택 완료 → /orders/:id/pay (결제)
- 재고 0 → /events/:id/sold-out
