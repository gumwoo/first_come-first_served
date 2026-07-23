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
