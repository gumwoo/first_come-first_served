# U-WAIT-EXPIRED · 대기시간 만료

- ref: `assets/screens/사용자_대기시간 만료.png`
- route: `/queue/expired`
- slice: S04

## 목적
대기열 토큰 또는 좌석 선점 시간이 만료되어 흐름이 끊겼을 때 안내 + 재진입 유도.

## 상태(states)
- `expired`

## 트리거
- `QUEUE_EXPIRED` 또는 `HOLD_EXPIRED`

## 사용 API
- (없음 / 재진입 시 `POST /events/:id/queue/token`)

## 화면 요소 (DoD 체크리스트)
- [ ] "대기시간이 만료되었습니다" 헤드라인 + 시계 아이콘
- [ ] 단계 진행바(만료 지점 표시)
- [ ] 공연 요약 카드
- [ ] 좌석 선점 해제 안내 + 재진입 안내(카운트)
- [ ] CTA: 다시 대기열 진입 / 이벤트 상세 / 다른 공연

## 연결
- 다시 대기열 진입 → /events/:id/queue
