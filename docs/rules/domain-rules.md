# Domain Rules (인덱스 + 횡단 불변식)

선착순 티켓팅 시스템의 도메인 불변식. 화면/구현이 바뀌어도 유지되는 업무 규칙.
정적으로 판별 가능한 것은 `contracts/`+하네스로, 실행으로만 확인되는 것은 테스트로.

도메인별 규칙은 슬라이스 단위로 분리한다. 작업 시 **해당 슬라이스 파일만** 읽는다.

## 도메인 규칙 인덱스
| 슬라이스 | 파일 | 범위 |
|---|---|---|
| S01 | [domain/auth.md](domain/auth.md) | 계정/가입, 휴대폰 인증, 토큰 |
| S02 | [domain/event.md](domain/event.md) | 공연 조회 / KOPIS 동기화 |
| S03 | [domain/queue.md](domain/queue.md) | 대기열 토큰 |
| S04 | [domain/seat.md](domain/seat.md) | 재고/좌석/등급/HOLD |
| S05 | [domain/order-payment.md](domain/order-payment.md) | 주문·결제 상태전이, 구매제한 |
| S06 | [domain/refund.md](domain/refund.md) | 취소/환불 |
| S07 | (예정) | 운영/DLQ/이벤트 상태전이 |

---

## 횡단 불변식 (여러 도메인에 걸침)

### 재고 정합성
- **총좌석 = AVAILABLE + HELD + SOLD** 이어야 함(어느 상태로도 누락/중복 없음). 어긋나면 운영 알림. [T]
- 좌석 선점은 조건부 UPDATE로 원자화 → 초과판매 0. ([ADR-003], [[seat]])

### enum 등록
- 모든 도메인 enum 값은 `contracts/enums.yaml`에 등록되어야 함 ★ (하네스 검사)

### 실시간 전송 (SSE)
- 대기열 입장/만료, 좌석 선점/해제/만료, 결제 완료/실패는 SSE로 프론트에 push.
- `events.yaml`의 `required_fe_subscribes`는 SSE로 반드시 전달되어야 하는 이벤트.
- 전송 채널: `GET /sse/queue/{token}`, `GET /sse/events/{id}/seats`, `GET /sse/orders/{id}` (api-contract.md)
