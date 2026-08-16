# Improvements Log (개선 일지 — before/after 정량 기록)

측정은 **사후가 아니라 개발과 동시에**. 각 개선마다 한 문서를 만들고,
naive → 측정 → 개선 → 재측정 순서로 수치를 **커밋으로 박제**한다.
(끝나고 나면 측정 불가 — 변화는 그 순간에만 잡힌다.)

## 작성 규칙
1. 개선 착수 전 현재 상태를 측정해 `benchmarks/`에 저장하고 커밋(= before 증거).
2. 개선 후 다시 측정해 저장하고 커밋(= after 증거).
3. `docs/improvements/IMP-XXX-<slug>.md`는 [`_TEMPLATE.md`](_TEMPLATE.md) 7단계로 기록:
   **문제 정의(계층 분류) → 증상(수치) → 가설 → 도구로 검증 → 해결 → 재측정 → 한계**.
4. 가능하면 "의도적 naive 버전"을 먼저 만들어 문제를 수치로 캡처한 뒤 개선한다.
5. 해결책(캐시/락/비동기)을 먼저 넣지 말 것 — 어디서 느린지/틀리는지 **구간을 나눠 측정**한 뒤 고른다.

## 개선 목록
| ID | 제목 | 슬라이스 | 핵심 | 상태 |
|----|------|----------|------|------|
| [IMP-001](IMP-001-rtr-multitab-grace.md) | RTR 멀티탭 세션 폭파 — grace 윈도 | S01 | 멀티탭 동시요청→세션유지 (정성) | 완료 |
| [IMP-002](IMP-002-multitab-logout-sync.md) | 멀티탭 로그아웃 즉시 동기화 — BroadcastChannel | S01 | 다른 탭 즉시 반영 (정성) | 완료 |
| [IMP-005](IMP-005-kopis-coverage.md) | KOPIS 동기화 커버리지 — 청크·페이지 전량 수집 | S02 | 100건→1,907건(약 19배) | 완료 |
| [IMP-006](IMP-006-xff-dedup-bypass.md) | 조회수 dedup 우회 — XFF 신뢰 경계 | S02 | 위조 100→1건 수렴 | 완료 |
| [IMP-007](IMP-007-token-issue-dedup.md) | 1인1토큰 중복 발급 — SET NX 원자화 | S03 | 중복 19→0(단일서버 실재) | 완료 |
| [IMP-003](IMP-003-oversell.md) | 재고 초과판매 — DB 조건부 UPDATE | S04 | oversell 19→0 | 완료 |
| [IMP-004](IMP-004-queue-admission.md) | 대기열 정원 초과 — 승격 Lua 원자화 | S03 | over-admit 7→0 | 완료 |
| [IMP-008](IMP-008-payment-idempotency.md) | 결제 이중 처리 — idempotency_key UNIQUE + 조건부 전이 | S05 | 이중 PAID 7→0 | 완료 |
| [IMP-009](IMP-009-refund-idempotency.md) | 환불 이중 처리 — refunds.idempotency_key UNIQUE + 조건부 전이 | S06 | 이중 환불 7→0 | 완료 |
| [IMP-010](IMP-010-seat-payment-race.md) | 좌석 만료↔결제 양방향 레이스 — 조건부 가드 + 영향행수 검증 | S04/S05 | 불일치 20→0 | 완료 |
| [IMP-011](IMP-011-outbox-delivery.md) | 브로커 장애 중 이벤트 유실 — 트랜잭셔널 아웃박스 | S08 | 유실 10→0 | 완료 |
| [IMP-012](IMP-012-kopis-upsert-batch.md) | KOPIS 건별 존재 확인 — 배치 조회 | S02 | 조회 200→1 | 완료 |
| [IMP-013](IMP-013-ci-shared-testcontainers.md) | CI 백엔드 잡 — 통합테스트 컨테이너·컨텍스트 공유 | 인프라 | 23.5→9.35분(-60%) | 완료 |
| [IMP-014](IMP-014-image-build-layer-cache.md) | 이미지 빌드 레이어 캐시(buildx + GHA) | 인프라 | 소스만 변경 98.5→**78초 중앙값(-21%)** — 다만 편차(63~103)가 개선 폭보다 큼, n=4 | 부분 |
| [IMP-015](IMP-015-rolling-zero-downtime.md) | 롤링 배포 무중단 실측(EKS) | 인프라 | 3,600건 중 5xx **0건** — 단 조건 한정(§5) | 부분 |
| [IMP-016](IMP-016-kafka-broker-failover.md) | Kafka 브로커 1대 장애 실증 | 인프라 | ISR 3→2, 리더 자동 선출, acks=all 쓰기 지속 — 앱 경로는 미검증 | 부분 |
| [IMP-017](IMP-017-pdb-node-drain.md) | 노드 드레인 중 무중단(PDB) | 인프라 | 2,400건 5xx **0건** — 파드 5개 여유 조건 | 완료 |
| [IMP-018](IMP-018-kopis-detail-hotpath-removal.md) | 사용자 요청이 외부 API 호출을 증폭시키던 구조 제거 | S02/S09 | 부하 중 외부 호출 **약 22,000→0건**(제한 34배 초과 해소) · 응답시간은 측정 환경 변동으로 미주장 | 완료 |
| [IMP-019](IMP-019-ci-backend-context-boot.md) | CI 백엔드 느림 — 컨텍스트 부트를 붙잡던 KafkaAdmin 토픽 생성 | 전반(CI) | backend 잡 9m46s→3m08s (-68%) | 완료 |

## 누적 지표 보드
프로젝트 전체에서 모은 정량 성과 요약: [METRICS.md](METRICS.md)
