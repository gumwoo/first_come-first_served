# Domain · S03 대기열

- 한 사용자는 이벤트당 활성 대기 토큰 1개. [T]
- 토큰 상태: `WAITING → ADMITTED → (좌석선택)` / TTL 만료 시 `EXPIRED`. [T]
- ADMITTED 안 된 토큰으로 좌석/주문 접근 금지 → `QUEUE_NOT_ADMITTED`. [T]
- 대기 만료 → `QUEUE_EXPIRED`.

## 실시간
- 입장 허용/만료는 SSE(`/sse/queue/{token}`)로 push. (횡단: domain-rules.md)
