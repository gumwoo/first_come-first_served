# Domain · S03 대기열

- 한 사용자는 이벤트당 활성 대기 토큰 1개. user 키를 **SET NX로 원자 예약**해 동시 요청
  (더블클릭)에도 토큰이 하나만 생긴다(멱등: 재요청 시 기존 토큰 반환). [T]
- 이탈(`DELETE /queue/token`): 대기 중이면 wait ZSet에서 제거, 입장 상태면 슬롯 반환
  (admitCount 감소). 나가기 버튼이 실제로 정리한다. [T]
- 토큰 상태: `WAITING → ADMITTED → (좌석선택)` / TTL 만료 시 `EXPIRED`. [T]
- ADMITTED 안 된 토큰으로 좌석/주문 접근 금지 → `QUEUE_NOT_ADMITTED`. (실차단은 S04 좌석 API)
- 대기 만료 → `QUEUE_EXPIRED`.

## 구현 (Redis, ADR-002)
- 순서: `queue:wait:{eventId}` ZSet(score=진입 seq). rank=ZRANK+1, total=ZCARD.
- **정원 승격은 Redis Lua로 원자화** — 정원 확인+head pop(ZPOPMIN)+카운트 증가 단일 실행.
  비원자면 동시 실행 시 정원 초과(over-admit) → 통합 테스트로 재현·방지([[IMP-004-queue-admission]]). [T]
- 승격 워커 `@Scheduled`(queue.admit-interval-ms). 입장창(`queue.admit-ttl`) 만료 토큰은
  만료 ZSet sweep으로 회수해 슬롯 반환. [T]
- 인증: 발급은 회원(Bearer), status/SSE는 토큰으로 접근(EventSource 헤더 제약).

## 실시간
- 입장 허용/만료는 SSE(`/sse/queue/{token}`)로 push: 승격 시 `queue.admitted`(redirect 포함),
  만료 시 `queue.expired` 후 스트림 종료. 폴링(`/queue/status`)은 폴백. (횡단: domain-rules.md)
- 전송 실패(느린/끊긴 클라이언트)는 emitter 제거로 격리. SSE는 단일 서버 가정
  (다중=Redis Pub/Sub, 후속). [ADR-002]
