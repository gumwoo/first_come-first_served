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
| [ADR-009](ADR-009-gitops-cd-argocd.md) | GitOps CD — ArgoCD 채택(Terraform=인프라/ArgoCD=앱) | Proposed |
| [ADR-010](ADR-010-transactional-outbox.md) | 트랜잭셔널 아웃박스(DB↔Kafka exactly-once) + 정산 분리 | Accepted |
| [ADR-011](ADR-011-payment-reconciliation.md) | 결제 정산·보상 — 외부 PG↔내부 주문 불일치(미아 승인 취소) | Accepted (구현 완료 / 실 PG 실증 미실시) |
| [ADR-012](ADR-012-3az-eks-infra-cost-control.md) | 3AZ EKS 인프라 — 이중화 범위와 비용 통제(apply/destroy) | Proposed |
| [ADR-013](ADR-013-iac-ownership-secrets-access.md) | IaC 소유 경계와 비밀·접근 통제 — data/import 기준, state에 비밀 금지, 열어 둔 곳 3개 | Proposed |
| [ADR-014](ADR-014-modular-monolith-layering.md) | 모듈러 모놀리스와 계층 경계 — DDD 전술 패턴은 선택적으로만(사후 정리) | Accepted |
| [ADR-015](ADR-015-queue-status-sync-polling-cost.md) | 대기열 상태 동기화 — onopen 재동기화로 복구를 만들고 폴링을 저빈도 안전망으로 전환. 폴링이 지금은 유일한 복구 경로라 먼저 걷어내면 정확성이 무너진다(주기는 측정 후 결정) | **Proposed**(① 구현, 회귀 검증 실패 — 조사 중) |
