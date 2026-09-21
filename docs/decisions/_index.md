# Architecture Decision Records (ADR)

되돌리기 어렵거나 여러 곳에 영향을 주는 **설계 결정**의 근거를 남긴다.
IMP(개선 일지: 문제→측정→개선)와 구분 — ADR은 "무엇을·왜 골랐고·언제 뒤집나".

| ID | 결정 | 상태 |
|----|------|------|
| [ADR-001](ADR-001-pagination-offset.md) | 목록 페이지네이션 기본은 offset | Accepted |
| [ADR-002](ADR-002-queue-design.md) | 대기열 — Redis ZSet + 배치승격 + Lua 원자화 | Accepted |
| [ADR-003](ADR-003-inventory-atomicity.md) | 재고 원자성 — DB 조건부 UPDATE | Accepted |
| [ADR-004](ADR-004-pricing-source.md) | 가격 — 자체 event_seat_prices + 장르 티어 | Accepted |
| [ADR-005](ADR-005-payment-gateway-adapter.md) | 결제 게이트웨이 — 포트-어댑터(Mock/Toss 테스트) | Accepted |
| [ADR-006](ADR-006-order-state-transition-atomicity.md) | 주문 상태 전이 원자화 — 조건부 UPDATE + 멱등키 | Accepted |
| [ADR-007](ADR-007-admin-auth-bootstrap.md) | 관리자 인증 — /admin/** 단일 게이트 + env 부트스트랩 | Accepted |
| [ADR-008](ADR-008-kafka-event-backbone-dlq.md) | Kafka 이벤트 백본 + DLQ — SSE 라스트홉, AFTER_COMMIT | Accepted |
| [ADR-009](ADR-009-gitops-cd-argocd.md) | GitOps CD — ArgoCD 채택(Terraform=인프라/ArgoCD=앱) | Accepted |
| [ADR-010](ADR-010-transactional-outbox.md) | 트랜잭셔널 아웃박스(DB↔Kafka at-least-once 발행 + 소비자 멱등) + 정산 분리 | Accepted |
| [ADR-011](ADR-011-payment-reconciliation.md) | 결제 정산·보상 — 외부 PG↔내부 주문 불일치(미아 승인 취소 + 미아 취소 수렴) | Accepted (구현 완료 / 실 PG 실증 미실시) |
| [ADR-012](ADR-012-3az-eks-infra-cost-control.md) | 3AZ EKS 인프라 — 이중화 범위와 비용 통제(apply/destroy) | Accepted |
| [ADR-013](ADR-013-iac-ownership-secrets-access.md) | IaC 소유 경계와 비밀·접근 통제 — data/import 기준, state에 비밀 금지, 열어 둔 곳 3개 | Proposed |
| [ADR-014](ADR-014-modular-monolith-layering.md) | 모듈러 모놀리스와 계층 경계 — DDD 전술 패턴은 선택적으로만(사후 정리) | Accepted |
| [ADR-015](ADR-015-queue-status-sync-polling-cost.md) | 대기열 상태 동기화 — `onopen` 재동기화로 **복구를 먼저 만들고** 폴링을 줄인다. 폴링이 유일한 복구 경로라 먼저 걷어내면 정확성이 무너진다. ③은 고정 주기가 아니라 **SSE 구독이 성립한 동안에만 2s→15s 백오프**(미개통·끊김 시 2s 유지) — 로컬 90초당 **45→9건** | **Partially Accepted**(①③ 구현·검증 / ② 하트비트 미착수) |
| [ADR-016](ADR-016-redis-outage-auth-fail-open.md) | Redis 장애 시 토큰 블랙리스트는 fail-open — 확인 불가를 차단으로 바꾸지 않는다. 같은 장애에선 로그아웃 자체가 불가능하므로 fail-closed는 "취소 지연"을 "서비스 정지"로 바꿀 뿐이다(노출 상한 = access TTL 30분). 쓰기는 실패를 전파 | Accepted |
| [ADR-017](ADR-017-order-sse-subscription-ticket.md) | 주문 SSE 구독은 단명 티켓으로 인가 — `EventSource`는 헤더를 못 붙이고 fetch 스트림은 자동 재연결을 잃으므로 URL 티켓을 쓰되, 전용 타입·주문 하나·TTL 300초로 노출의 값을 낮춘다. TTL은 새 구독의 창이지 스트림 수명이 아니다 | Accepted |
| [ADR-018](ADR-018-clock-injection.md) | 시간의 출처를 주입한다 — 만료·환불 가능 시점·정산 창이 모두 "지금"으로 갈리는데 `LocalDateTime.now()` 직접 호출이라 판정을 테스트에서 고정할 수 없었다. 시계를 빈으로 두되 `systemDefaultZone()`(UTC를 쓰면 KST에서 9시간 과거로 판정, TS-039). 엔티티 타임스탬프는 범위 밖 | Accepted |
| [ADR-019](ADR-019-self-proxy-to-collaborator.md) | 자기 프록시 주입(`ObjectProvider<Self>`) 대신 협력자로 나눈다 — 프레임워크의 프록시 구현이 서비스 의존성에 드러나고 단위 테스트가 자기 자신을 배선해야 했다. 경계의 성격에 따라 쓰기 협력자·진입점 분리·조회/명령 분리로 나눈다. **락·트랜잭션의 위치는 옮기지 않는다** | Accepted(전 구간 완료 + 하네스 ㉑) |
| [ADR-020](ADR-020-payment-tx-boundary.md) | 결제 승인을 DB 트랜잭션 밖에서 한다 — PG 응답을 기다리는 동안 Hikari 커넥션(파드당 5)을 쥐고 있었다. TX1(READY 행) → PG → TX2(확정)로 나누고 보상 취소도 경계 밖으로. 동시 더블클릭이 승자의 최종 결과를 받는 IMP-008 보장은 짧은 재확인으로 유지 | Accepted(승인 경로 / 환불은 후속) |
| [ADR-021](ADR-021-refund-tx-boundary.md) | 환불도 PG 호출을 트랜잭션 밖에서 한다 — TX1(취소 전이) → PG → TX2(기록·좌석). TS-030의 "전이가 기록보다 먼저"는 유지하되, 둘이 다른 트랜잭션이 되면서 패자가 승자의 기록을 기다린다. 새로 생긴 CANCELLED 중간 상태는 정산이 이어서 끝내거나 되돌린다 | Accepted |
