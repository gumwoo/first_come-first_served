# METRICS — 누적 정량 성과 보드

프로젝트 전체에서 모은 before/after 수치 요약. 각 행은 개선 일지로 링크.
면접/포트폴리오에서 "정량적 성과"를 물으면 이 표 + 커밋된 벤치 JSON을 제시.

## 성능 / 동시성

### 자동 전사 (k6 결과 → scripts/collect-metrics.mjs 가 갱신)
아래 블록은 `node scripts/collect-metrics.mjs`가 `benchmarks/*.json`을 읽어 갱신한다.
**이 마커 사이는 손으로 수정하지 말 것**(자동 덮어쓰기됨).
<!-- AUTO:PERF:START -->
_아직 측정 결과 없음 — 성능 슬라이스에서 k6 실행 후 자동 채워짐._
<!-- AUTO:PERF:END -->

### 설계 개선 (정성 사례)
| 사례 | before | after | 근거 |
|------|--------|-------|------|
| RTR 멀티탭(서버) | 탭 2개=로그아웃 | grace로 세션 유지 | [IMP-001](IMP-001-rtr-multitab-grace.md) |
| 멀티탭 로그아웃(프론트) | 다른 탭 행동 전까지 로그인된 듯 | 즉시 비로그인 전환 | [IMP-002](IMP-002-multitab-logout-sync.md) |

### 서사/요약 (사람이 작성)
| 지표 | before | after | 변화 | 근거 |
|------|--------|-------|------|------|
| 초과판매(동시 20, SQL) | 19건 | 0건 | -100% | [IMP-003](IMP-003-oversell.md) |
| 대기열 정원 초과(동시 승격) | 7건 | 0건 | -100% | [IMP-004](IMP-004-queue-admission.md) |
| 1인1토큰 중복 발급(동시 20) | 19건 | 0건 | -100% | [IMP-007](IMP-007-token-issue-dedup.md) |
| 결제 이중 PAID(동시 10 더블클릭) | 7건 | 0건 | -100% | [IMP-008](IMP-008-payment-idempotency.md) |
| 환불 이중 처리(동시 10 더블클릭) | 7건 | 0건 | -100% | [IMP-009](IMP-009-refund-idempotency.md) |
| 좌석 만료↔결제 양방향 레이스 불일치(10시행×2방향) | 20건 | 0건 | -100% | [IMP-010](IMP-010-seat-payment-race.md) |
| 사용자 부하 중 외부 API 호출(50 VU, k6 구간 약 64초) | 약 22,000건 | **0건** | -100% | [IMP-018](IMP-018-kopis-detail-hotpath-removal.md) |
| 좌석맵 조회 API CPU(400 rps·90초) | 2.55 ms/req | **1.56 ms/req** | -39% | [IMP-020](IMP-020-seat-map-cache.md) · [요약](../../benchmarks/cache-experiment/RESULT.md) |
| 좌석맵 조회 서버 p99(같은 구간) | 24.5 ms | **9.7 ms** | -60% | 〃 |
| 캐시 hit 경로 트랜잭션 경계 개선 — DB 커넥션/요청<br>(둘 다 캐시 ON. tx 안 → tx 밖) | 1.005 회 | **0.025 회** | -97% | 〃 |
| 같은 구간 제한 허용량(약 640건) 대비 | **약 34배 초과** | 위반 없음 | — | [IMP-018](IMP-018-kopis-detail-hotpath-removal.md) |
| 대기열 진입 CPU(web+api, 공개 ALB 1,000/s·60초 판정 3회 중앙값) | 3.04 ms/req | **1.08 ms/req** | -64.5% | [IMP-025](IMP-025-queue-path-next-hop-bypass.md) |
| 대기열 진입 p95(IMP-025와 같은 구간 — before 편차 97~694ms라 참고값) | 157.5 ms | **15 ms** | -90% | [IMP-025](IMP-025-queue-path-next-hop-bypass.md) |
| 대기열 진입 DB 커넥션 획득/req(공개 ALB 2,000·3,000/s, 칸별 1회) | 1.01 회 | **0.011~0.013 회** | -99% | [IMP-026](IMP-026-queue-entry-db-redis-cost.md) |
| 대기열 신규 진입 Redis 명령/req(4,000/s, 1회) | 7.03 | **2.03** | -71% | 〃 |
| 대기 상태 조회 api CPU(공개 ALB 3,000/s·45초 판정 3회 중앙값) | 0.635 ms/req | **0.482 ms/req** | -24.1% | [IMP-028](IMP-028-queue-status-lua.md) |
| 대기 상태 조회 Redis 명령/req(같은 구간) | 5.99 | **2.04** | -66% | 〃 |
| 대기열 진입 ALB 창 [T0, T0+10s) 도착·200 응답(100,000명 / 10초, 세션 한정 사전 확장 api 9·노드 9, 3차 시도 판정 3회) | — | **100,000 / 100,000 × 3** | — | [IMP-029](IMP-029-final-100k-10s-queue.md) |
| 같은 시험 대기자 폴링 조회(420초, run당) 오류 | — | **0 / 약 1,266,000** | — | 〃 |
| 입장자 100명 동시 예매(경합·더블클릭·결제 실패·1인 한도 겹침, mock 결제) 정합성 위반 — DB 기대값 13개 + 기존 사후 검사(판정 3회) | — | **0 × 3** | — | [IMP-030](IMP-030-booking-e2e-correctness.md) |

<!--
표 서식 예시(측정값 아님). 실제 수치가 아니므로 표 안에 두지 않는다 —
프로젝트 규칙: "예측치는 표에 넣지 않는다. 표·코드블록에 넣는 순간 실측으로 읽힌다."
아래는 새 행을 추가할 때 참고할 서식일 뿐이다.

  | 예매 API p95 | 850ms | 210ms | -75% | IMP-00x |
  | 처리량 TPS   | 120   | 1,400 | +11.6x | IMP-00x |
-->

## 하네스 / 품질 게이트 (이미 측정 가능)
| 지표 | 값 | 근거 |
|------|----|----|
| 하네스가 차단하는 위반 유형 | 14종 | harness/meta-test.mjs |
| 계약 검증 항목 | enum/api/event/error/stack/layer/schema/yml-secret/table-doc | contracts/ + harness/ |
| CI 게이트 | meta → backend → frontend | .github/workflows/ci.yml |
| 개발 중 CI가 사전 차단한 위반 | (누적 기록) | PR/CI 로그 |

## 측정 도구
- 부하/성능: k6 (`infra/k6/`, 결과는 `benchmarks/`)
- 동시성: JUnit 동시성 테스트
- 쿼리: Hibernate statistics / p6spy
- 품질: 하네스 메타테스트 + CI 로그
