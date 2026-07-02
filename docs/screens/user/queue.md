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
- `POST /events/:id/queue/token` — 진입 토큰 발급(회원)
- `GET /queue/status?token=` — `{ rank, total, etaSeconds, status }` 폴링(폴백)
- `GET /sse/queue/:token` — 실시간 push(queue.admitted/queue.expired), 폴링과 이중화

## 화면 요소 (DoD 체크리스트)
- [x] 대형 대기순번 숫자(예: 3,421번)
- [x] 내 앞 대기 인원 / 예상 대기시간
- [x] 진행 바(최초 순번 기준 클라 계산)
- [x] 자동 진행 안내(이탈 금지)
- [x] 우측 이벤트 정보 요약 + 대기 안내
- [x] 예매 취소 / 이전으로
- [x] admitted(입장완료 임시 안내) / expired(만료 안내) 상태 처리

## 연결
- admitted → /events/:id/seats (S04 전까지 "입장 완료" 임시 안내)
- expired → 만료 안내(다시 대기/상세로). 풀 만료 화면(세션 타이머·좌석 단계)은 S04 wait-expired
