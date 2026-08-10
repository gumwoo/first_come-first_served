# Screens Index — 작업 큐 (단일 진실원)

작업 단위는 **기능 슬라이스**(DB→API→화면→검증 세로 관통).
에이전트는 `status: todo`인 첫 슬라이스를 잡아 끝까지 구현 후 `done`으로 바꾼다.
status: `todo | doing | done | blocked`

## 슬라이스 진행 현황
| id | 슬라이스 | 선행 | status | note |
|----|----------|------|--------|------|
| S01 | 인증(로그인/회원가입) | - | done | BE+FE 구현, 단위테스트. 통합/소셜연동은 후속 |
| S02 | 공연조회(메인/검색/상세) + KOPIS 동기화 | S01 | done | BE+FE+KOPIS 실연동, 단위/통합테스트. 카테고리탭·뷰토글·관심 등 부가요소는 후속 |
| S03 | 대기열 | S02 | done | 발급/승격(정원 Lua)/SSE/UI + IMP-004. QUEUE_NOT_ADMITTED 실차단은 S04 |
| S04 | 좌석·재고(좌석선택/매진/대기만료) | S03 | done | BE(원자 선점·자동시딩·SSE·만료 sweep)+FE(극장식 좌석/매진/만료) + IMP-003 + 상태기계 통합테스트 + TS-001~004. 결제 연결은 S05 |
| S05 | 주문·결제(결제/완료/실패/입금대기) | S04 | done | 주문 생성·Mock 결제 승인+조건부 전이·가상계좌·결제 만료 sweep + 멱등(IMP-008) + 결제 FE/QR + E2E(해피+예외) + Toss 실 PG 연동(카드 결제창·confirm) + 가상계좌 입금 웹훅(secret 대조·멱등) + TS-009/TS-010 |
| S06 | 마이페이지·취소/환불 | S05 | done | 마이페이지 목록(탭·페이징)·상세 + 취소/환불(상태 3분리 CANCELLED/REFUNDED)·기간별 수수료 + 멱등(IMP-009) + 마이페이지 리디자인 + 환불 E2E |
| S07 | 운영(대시보드/내역/이벤트/DLQ/알림) | S05 | done | 관리자 인증(ADR-007)·대시보드·주문 조회·공연 CRUD·Kafka 이벤트 백본+DLQ(ADR-008)·알림 임계치·admin E2E. 계약 100% 충족 |
| S08 | 정합성 보강 — 아웃박스(1단계) + PG 정산·보상(2단계) | S07 | done | **우선순위 1**. 설계 [ADR-010](../decisions/ADR-010-transactional-outbox.md). **1단계 아웃박스**: `order.paid`가 AFTER_COMMIT best-effort라 "커밋 직후·발행 전 크래시" 시 유실 가능(ADR-008·TS-011 §한계) → 같은 tx에 이벤트 적재→폴링 릴레이(ShedLock) 발행→Redis SETNX 멱등으로 **exactly-once**. 유실 **10→0**([IMP-011](../improvements/IMP-011-outbox-delivery.md), PR #138·#139·#140). **2단계 정산**([ADR-011](../decisions/ADR-011-payment-reconciliation.md)): PG 승인 후 크래시로 롤백돼 DB에 흔적이 없는 미아 승인(아웃박스로 못 닫는 클래스)을 주문 기준 후보→PG 조회→취소(void)로 회수. 감사 테이블은 범위 밖(로그). |
| S09 | 배포·운영 준비(컨테이너화·EKS·Strimzi·멀티팟·ArgoCD) | S07 | **done(정량 부하만 S10)** | (구 S08) 멀티팟 준비 **코드 선반영 완료**(①SSE Redis pub/sub 팬아웃·②ShedLock·③PG 보상·④정확한 알림). 2026-08-10 기준 **컨테이너화·ECR/CI·Terraform(EKS)·Strimzi 멀티브로커·ArgoCD(GitOps, 자동 동기화)·관측(Prometheus/Grafana + Kafka)·①② cross-Pod 실증까지 완료**([[TS-022]], [[TS-023]]). 남은 것은 정량 부하 측정(S10)과 External Secrets. 상세: [docs/deployment/](../deployment/_index.md) |
| S10 | 부하테스트·모니터링 | S09 | todo | 배포 위 k6 실측·HPA 수평확장·브로커 페일오버·Consumer Lag. IMP 재측정(아웃박스 유실 0 포함) |

> **우선순위(작업 순서) — 재정렬**: 서비스 기업(대용량·정합성) 지향에 맞춰 **정합성 깊이를 배포보다 앞에** 둔다.
> **S08 아웃박스(정합성) → S09 배포(K8s·ArgoCD) → S10 부하·모니터링.** 근거: 아웃박스는 이 프로젝트 핵심
> 논지("정확히 한 번")를 발행 축까지 닫는, 코드가 이미 지목한 구멍(ADR-008·TS-011)이라 ROI가 가장 높다.
> 배포(K8s+ArgoCD)는 이미 "코드 선반영 완료" 상태로 충분 → **더 쌓지 않고 실증만** 하고 동결.

## 슬라이스 ↔ 화면 매핑
### S01 인증
- [login.md](user/login.md) · [signup.md](user/signup.md)

### S02 공연조회
- [main.md](user/main.md) · [search.md](user/search.md) · [event-detail.md](user/event-detail.md)

### S03 대기열
- [queue.md](user/queue.md)

### S04 좌석·재고
- [seat-select.md](user/seat-select.md) · [sold-out.md](user/sold-out.md) · [wait-expired.md](user/wait-expired.md)

### S05 주문·결제
- [payment-card.md](user/payment-card.md) · [payment-easy.md](user/payment-easy.md) · [payment-vbank.md](user/payment-vbank.md)
- [complete.md](user/complete.md) · [failed.md](user/failed.md)

### S06 마이/취소
- [mypage.md](user/mypage.md) · [refund.md](user/refund.md)

### S07 운영
- [dashboard.md](operator/dashboard.md) · [orders.md](operator/orders.md) · [events.md](operator/events.md)
- [event-detail.md](operator/event-detail.md) · [dlq.md](operator/dlq.md) · [alerts.md](operator/alerts.md)

### S08 아웃박스 + 정산(정합성 보강)
- 사용자 화면 없음(백엔드 트랙). 1단계 아웃박스(exactly-once 발행) / 2단계 PG 정산·보상. 설계: [ADR-010](../decisions/ADR-010-transactional-outbox.md). 실측은 IMP-011 + 통합테스트.

### S09 배포·운영 준비
- 사용자 화면 없음(인프라 트랙). 상세: [docs/deployment/_index.md](../deployment/_index.md)

### S10 부하/모니터링
- [loadtest-scenario.md](developer/loadtest-scenario.md) · [loadtest-running.md](developer/loadtest-running.md)
- [loadtest-result.md](developer/loadtest-result.md) · [loadtest-report.md](developer/loadtest-report.md)
- [monitoring.md](developer/monitoring.md)
