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
| [TS-024](TS-024-queue-admit-visibility-gap.md) | 진입하자마자 "만료" — 승격 커밋이 Lua 밖으로 새어 ① 상태가 `EXPIRED`로 보이는 창 ② Pod 크래시 시 **정원 영구 누수** | S10 | 동시성(원자 경계) | 해결(승격 커밋 원자화 + 판정 규칙 통일, 회귀 3건) — 스파이크 재측정 대기 |
| [TS-025](TS-025-docs-drift-harness.md) | 하네스가 코드↔계약은 잡는데 문서↔현재 상태는 못 잡았다 — "아직 없는 것" 네 항목이 전부 실존, 한 절 안에서 자기모순, 참조 상태 표기 낡음 | 전반(문서 규율) | 프로세스(규칙 사각지대) | 해결(드리프트 4건 수정 + 하네스 규칙 ⑯·⑰, 초안 오탐 2회 정정) |
| [TS-026](TS-026-dlq-retry-unconfirmed-publish.md) | DLQ 재처리가 브로커 확인 없이 RETRIED로 기록 — 같은 문제를 OutboxRelay는 `.get()`으로 확인하고 있었다 | S07 | 메시지 신뢰성(확인 전 상태 확정) | 해결(ACK 확인 후 마킹 + 실패 종류 분리, 회귀 2건) |
| [TS-027](TS-027-ci-nondeterministic-install.md) | 결정론을 내세우면서 CI 의존성 설치에는 fallback이 있었다 — `npm ci` 실패를 뒤의 `npm install`이 흡수해 초록불의 의미가 사라졌다 | 전반(CI) | 프로세스(검증의 토대) | 해결(fallback 6곳 제거, lockfile 3개 정상 확인) |
| [TS-028](TS-028-payment-gateway-no-timeout.md) | 결제 PG 호출에 타임아웃이 없었다 — KOPIS는 걸어 두고 결제만 안 걸어, 응답 없는 PG가 Hikari 커넥션(파드당 5)까지 묶을 수 있었다 | S05 | 가용성(외부 호출 경계) | 해결(타임아웃 + 하네스 규칙 ⑱ — 규칙이 자기 수정을 못 보던 false negative를 리뷰에서 잡아 두 형태 모두 검사) |
| [TS-029](TS-029-ci-concurrency-stale-deploy.md) | 오래된 커밋이 최신 배포를 덮을 수 있었다 — concurrency는 동시 실행만 막고 순서는 보장하지 않아, 조상 관계를 직접 보는 4-상태 가드가 필요했다 | 전반(CI/CD) | 배포 정확성 | 해결(CI/배포 concurrency 분리 + main 포함 검사 ⓪ + 4상태 조상 가드. 초안은 최신 main에서 딴 feature가 ③으로 통과하던 구멍이 있었고 git 시뮬레이션으로 재현) |
| [TS-030](TS-030-refund-idempotency-concurrent-loser.md) | 환불이 동시 더블클릭에 멱등이 아니었다 — 멱등키 UNIQUE보다 상태 전이 가드가 앞서 있어 동시 패자가 UNIQUE에 도달하지 못하고 REFUND_NOT_ALLOWED를 받았다 | S06(환불) | 동시성 정확성 | 해결(전이 가드에서 멱등키 재조회 후 기존 결과 반환. 기존 테스트가 catch (Exception ignored)로 예외를 삼켜 최종 상태만 봤기 때문에 가려져 있었다) |
| [TS-031](TS-031-kopis-sync-outlives-alb-timeout.md) | KOPIS 수동 동기화가 ALB idle timeout(60초)보다 오래(3분 45초) 걸려 좌석 0을 실패로 오해했다 — 완료 시에만 로그를 남겨 '진행 중'과 '0건 완료'가 구분되지 않았다 | S04·S09 | 운영 절차 | 원인 확인(구조는 유지하고 관측 방법을 기록. 비동기 전환·진행 로그는 의도적으로 하지 않음) |
| [TS-032](TS-032-outbox-poison-row-head-of-line.md) | 아웃박스 행 하나가 모든 주문 이벤트를 영구 정지시킬 수 있었다 — 역직렬화 실패와 브로커 실패를 같은 catch로 묶어, 재시도해도 결과가 같은 행이 정렬 선두를 영원히 차지했다 | S08 | 가용성(실패 분류 누락) | 해결(DEAD 격리 + 차단 범위를 해당 aggregate로 한정, RED→GREEN CI로 증명. 소비자 DLQ에는 있던 원칙이 생산자에는 없었다 — TS-026의 정반대 방향) |
| [TS-033](TS-033-proxy-metric-detail-sync.md) | 대리값으로 진행을 셌더니 완료를 못 알아봤다 — runningTime은 조건부로만 갱신되고 detailSyncedAt은 무조건 갱신돼, 카운터가 영원히 0에 도달하지 못했다 | S02·횡단 | 관측 지표 오선정 | 해결(진실원을 내보내는 읽기 전용 엔드포인트 + 의미 테스트. 재실행 25분 → 16초, 거짓 경고 53건 → 0) |
| [TS-034](TS-034-loadtest-path-generator-node-contention.md) | knee를 다시 재려다 세 번 어긋났다 — ① api Service로 직접 쏴 ALB·web을 우회(ALB RequestCount 5건) ② 전 경로로 바꾸자 k6가 OOMKilled ③ 발생기가 측정 대상과 노드 CPU를 나눠 써 정점 93% | S10 | 측정 방법론 실패 회고 | **미해결**(재측정 못 함 — 통제해야 할 것만 확정. ADR-012가 이미 부하테스트용 인스턴스를 명시했는데 그대로 쟀다) |
| [TS-035](TS-035-rolling-deregistration-race.md) ⚠️ §9 | 롤링 배포 중 5xx의 원인은 이미지 변경도 동시 롤링도 아니었다 — preStop(5s)이 등록 해제가 ALB에 반영되는 전파 시간을 덮지 못해, 컨테이너가 먼저 죽은 구간에서 ALB가 아직 정상으로 아는 파드 IP로 보냈다(target-type: ip). 네 번의 측정에서 Target_5XX가 계속 0이었던 것이 신호 | S09 | 가용성(종료 순서 경쟁) | **해결**(preStop 25s + grace 60s → 실제 배포 경로에서 6,001건 전량 2xx. IMP-015 §5의 두 가설을 요인 분리로 배제. 다만 옛 측정이 왜 0이었는지는 미해결 §6) |
| [TS-036](TS-036-measurement-tooling-false-failures.md) | 측정을 세 번 막은 것은 시스템이 아니라 측정 도구였다 — ① 로그 문자열("Registering Node Group")로 CA 상태를 판정해 정상 CA를 고장으로 오판 ② 같은 결함이 형제 스크립트에 남아 재발 ③ `curl \|\| echo 000`이 "000000"을 만들어 폴백을 건너뜀 | S09·S10 | 측정 도구 오탐 회고 | **해결**(판정을 공개 인터페이스로 — 상태 ConfigMap·종료 코드 분리. 폴백을 강제로 태워 검증. 같은 세션에서 "공허한 통과" 계열을 5건 잡았다) |
| [TS-037](TS-037-rds-redis-failover-app-behavior.md) | ADR-012가 A(실증)로 약속한 RDS·Redis Multi-AZ 페일오버를 실제로 눌렀다 — 둘 다 파드 재시작 없이 자동 복구했고 대기열 상태도 유실 0이었으나, **RDS는 Hikari connection-timeout이 설정돼 있지 않아 기본 30초를 다 쓴 뒤 500**(풀 timeout 57건), Redis는 p95가 19.5초까지 상승 | S09 | 장애 주입 실증 | **실증 완료**(RDS 93/8,817 · Redis 263/5,287) + 결함 후보 1건. 개선 재측정은 별도 IMP로 분리 — 설정을 바꾸면 기준선이 달라진다 |
