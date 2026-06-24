# U-QUEUE · 예매 대기열

- ref: `assets/screens/사용자_예매 대기열.png`
- route: `/events/:id/queue`
- slice: S03

## 목적
선착순 트래픽 분산. 대기순번/예상시간 표시 후 입장 허용 시 좌석선택으로.

## 상태(states)
- `waiting` — 대기 중(순번/예상시간 폴링)
- `admitted` — 입장 허용 → 좌석선택 리다이렉트
- `expired` — 토큰 만료 → /queue/expired

## 사용 API
- `POST /events/:id/queue/token` — 진입 토큰 발급
- `GET /queue/status?token=` — `{ rank, total, etaSeconds, status }` 폴링

## 화면 요소 (DoD 체크리스트)
- [ ] 대형 대기순번 숫자(예: 3,421번)
- [ ] 전체 대기 인원 / 예상 대기시간
- [ ] 진행 바
- [ ] 자동 진행 안내(새로고침/이탈 금지 경고)
- [ ] 우측 이벤트 정보 요약
- [ ] 예매 취소 / 이전으로

## 연결
- admitted → /events/:id/seats
- expired → /queue/expired
