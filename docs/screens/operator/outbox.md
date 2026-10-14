# O-OUTBOX · 아웃박스

- ref: 없음(DLQ 화면 패턴을 따른다 — `operator/dlq.md`)
- route: `/admin/outbox`
- slice: S08(아웃박스) · Kafka 제거 P2a([[ADR-028]], `docs/deployment/kafka-removal-design.md` §5.2)
- 참조: common/design-system.md, common/layout.md, common/api-contract.md

## 목적
결제와 같은 트랜잭션에 쌓인 알림 이벤트(`outbox_events`)를 본다. 재시도로 풀리지 않아 격리(DEAD)된 이벤트를 재발행 대기로 되돌리거나 폐기한다.
Kafka 제거 뒤에는 DLQ 화면을 대신한다 — 실패가 소비자(DLQ)가 아니라 릴레이(아웃박스 행)에 남기 때문이다.

## 상태(states)
- 기본 필터 `격리(DEAD)` — 운영자가 개입할 대상
- `미발행(PENDING)` — 릴레이가 곧 내보낼 행(조치 버튼 없음)
- `전체`
- 빈 목록 / 불러오기 실패

## 트리거 / 진입
- 사이드바 "아웃박스"
- 대시보드 경고 배너(격리 수 ≥ 임계치) → "아웃박스 확인"
- Prometheus 알림 `FlowticketOutboxDeadRows`의 안내 문구

## 사용 API
- `GET /admin/outbox?status=&page=&size=` — 목록(최신순)
- `POST /admin/outbox/{id}/requeue` — DEAD → PENDING(발행은 릴레이가 한다)
- `POST /admin/outbox/{id}/discard` — DEAD → DISCARDED(같은 aggregate의 후속 이벤트가 다시 흐른다)
- DEAD가 아닌 행에 조치하면 409(`INVALID_STATE_TRANSITION`)

## 화면 요소 (DoD 체크리스트)
- [x] 상태 필터(격리/미발행/전체)
- [x] 행: 상태 pill, 이벤트 타입, aggregate, 시도 횟수, 생성 시각, payload, 마지막 오류
- [x] DEAD 행에만 재발행·폐기 버튼
- [x] 페이지 이동

## 연결(이탈 경로)
- (자체 처리)
