# Troubleshooting Log (트러블슈팅 — 증상→근본원인→해결→재발방지)

실사용/수동 검증 중 발견한 결함과 인프라 문제를 **증상에서 근본 원인까지 추적한 기록**.
IMP(정량 before/after 개선)와 달리, 여기 문서는 "무엇이 왜 깨졌고, 어떻게 원인을 좁혔는지"를 남긴다.
(측정 수치가 핵심이 아니라 **디버깅 과정과 재발 방지책**이 핵심)

## 작성 규칙
1. `docs/troubleshooting/TS-XXX-<slug>.md` 한 문서에 한 사건.
2. 순서: **증상 → 재현 → 조사(관측 로그/명령) → 근본 원인 → 해결 → 재발 방지**.
3. 관측한 로그·명령어·수치를 그대로 박제(끝나면 재현 불가한 경우가 많다).
4. 코드 수정이 있으면 관련 커밋/PR, 없으면(인프라 등) 조치 내용을 남긴다.

## 목록
| ID | 제목 | 슬라이스 | 유형 | 상태 |
|----|------|----------|------|------|
| [TS-001](TS-001-queue-reentry-after-expiry.md) | 입장 만료 후 재예매 불가 — 죽은 토큰이 1인1토큰을 점유 | S03 | 정합성 버그(코드) | 해결 |
| [TS-002](TS-002-local-redis-version-zpopmin.md) | 대기열 승격 매 틱 실패 — 로컬 구버전 Redis(ZPOPMIN 미지원) | S03 | 인프라(환경) | 해결 |
| [TS-003](TS-003-queue-status-auth-poll-rtr-storm.md) | 대기 상태 폴링이 토큰 회전 폭주 유발 — 인증 경계 오배치 | S03/S01 | 인증 경계(코드) | 해결 |
| [TS-004](TS-004-oauth-empty-client-id-context-fail.md) | 키 없는 환경 부팅 실패 — OAuth2 client-id 빈 문자열 | S01 | 설정/환경 | 해결 |
| [TS-005](TS-005-s04-exception-flow-verification.md) | S04 예외 흐름 실검증 — 매진 트리거 + 대기만료 범위 | S04 | 검증 로그 | 완료 |
| [TS-006](TS-006-seat-hold-error-not-surfaced.md) | 좌석 선점 실패가 화면에 안 뜸 — MAX_PER_USER 등 무음 처리 | S04 | UX 결함(FE) | 해결 |
| [TS-007](TS-007-release-hold-status-lost-context-clear.md) | 선점 해제 시 좌석만 풀리고 홀드는 HELD로 남음 — 벌크 UPDATE 컨텍스트 클리어 | S04 | 정합성 버그(코드) | 해결 |
| [TS-008](TS-008-hold-cross-event-seat-ownership.md) | 좌석 선점 시 seatIds의 이벤트 소속 미검증 — 교차 이벤트 선점(IDOR) | S04 | 정합성/보안(코드) | 해결 |
| [TS-009](TS-009-why-e2e-adoption.md) | 단위/통합은 초록인데 버그는 수동으로만 잡힘 — E2E(Playwright) 도입 | 횡단 | 검증 전략/회고 | 완료 |
| [TS-010](TS-010-vbank-secret-lost-context-clear.md) | 가상계좌 발급 정보(secret/계좌/기한)가 DB에 안 남음 — 벌크 UPDATE 컨텍스트 클리어(TS-007 재발) | S05 | 정합성 버그(코드) | 해결 |
| [TS-011](TS-011-expiry-sweep-overwrites-paid-seat.md) | 만료 sweep이 결제된 SOLD 좌석을 되돌릴 수 있음 — 무가드 복구 UPDATE(형제 경로 재발) | S04/S05 | 정합성 버그(코드) | 해결 |
| [TS-012](TS-012-sse-reconnect-gap-no-resync.md) | SSE 구독 공백의 이벤트를 영영 놓침 — "폴링이 커버한다"는 주석만 있고 폴링이 없음 | S04/S05 | 상태 최신성(FE) | 해결 |
| [TS-013](TS-013-per-user-quota-aggregate-invariant.md) | 1인 구매 한도가 뚫림 — 조건부 UPDATE로는 못 지키는 집계 불변식 | S04 | 정합성 버그(코드) | 해결 |
| [TS-014](TS-014-order-create-idempotency-race.md) | 같은 hold로 동시 주문 시 둘 다 생성 — 앱의 "찾고→없으면 생성"이 뚫림 | S05 | 정합성 버그(코드) | 해결 |
| [TS-015](TS-015-truncate-scheduler-deadlock.md) | CI 6회 실패 — "꺼 뒀다고 믿은" 스케줄러가 TRUNCATE와 데드락 | 횡단 | 테스트 인프라/진단 회고 | 기전 차단 |
| [TS-016](TS-016-kafka-required-at-startup.md) | readiness에서 Kafka를 뺐는데도 Pod가 못 뜸 — 기동 의존과 트래픽 의존은 다르다 | S09 | 배포/기동(설정) | 해결(구조적 문제는 남음) |
| [TS-017](TS-017-oauth-callback-not-proxied.md) | 소셜 로그인 콜백 404 — 시작 경로만 프록시하고 콜백을 빠뜨림(로컬 미재현) | S01/S09 | 배포 환경(설정) | 해결 |
| [TS-018](TS-018-alb-idle-timeout-sync-endpoint.md) | 동기화가 500인데 데이터는 다 들어옴 — ALB idle timeout에 끊긴 장시간 요청 | S02/S09 | 배포 환경(API 설계) | 원인 규명(수정 보류) |
| [TS-019](TS-019-hpa-without-metrics-server.md) | HPA를 선언했는데 스케일 판단 불가 — metrics-server 없음 | S09 | 배포 환경(누락) | 해결 → **2026-08-11 재발**(설치가 IaC 밖이었다) → EKS Add-ons(community add-on)로 못박음 |
| [TS-020](TS-020-kafka-poison-pill-infinite-retry.md) | DLQ가 있는데 독성 메시지는 DLQ로 못 감 — 역직렬화 실패는 리스너에 도달하지 않음 | S08/S09 | 정합성·가용성(설정) | 해결 |
| [TS-021](TS-021-hpa-exhausts-rds-connections.md) | 파드를 늘렸더니 DB가 한계 — HPA 최대치 × 커넥션 풀 > RDS 슬롯 | S09 | 용량(설정) | 해결(풀 10→5 + 하네스 강제, 9파드 실기동 검증) |
| [TS-022](TS-022-argocd-respectignoredifferences-ineffective.md) | 리뷰대로 고쳤는데 안 먹음 — `RespectIgnoreDifferences=true`인데 sync가 replicas를 덮어씀(UI는 Synced), ArgoCD v3.5.0 구성 실측 | S09 | 배포 도구 동작 불일치(설정) | 해결(필드 제거 + 하네스 규칙 ⑧, 통제 실험으로 검증) |
| [TS-023](TS-023-multipod-failover-verification.md) | 다중 Pod·페일오버 실증 — ShedLock 단일 실행 / SSE cross-Pod 팬아웃 / 브로커 1대 소실 / Lag 회복 | S09 | 검증 로그 | 완료(A1은 대조군이 초기 결론을 정정) |
| [TS-024](TS-024-queue-admit-visibility-gap.md) | 진입하자마자 "만료" — 승격의 원자 구간이 `ZPOPMIN`에서 끝나고 `admit` 키 생성은 Lua 밖이라 그 창에서 상태가 `EXPIRED`로 보임 | S10 | 동시성(상태 가시성) | **원인 확정, 수정 보류** |
