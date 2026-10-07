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
| [IMP-015](IMP-015-rolling-zero-downtime.md) | 롤링 배포 무중단 실측(EKS) | 인프라 | web 티어 롤링·25 rps에서 6,001건 5xx **0건** — 수정 전 4/4 실행 실패 → 수정 후 2/2 무결(§9), 원인 규명 [TS-035](../troubleshooting/TS-035-rolling-deregistration-race.md) | 완료 |
| [IMP-016](IMP-016-kafka-broker-failover.md) | Kafka 브로커 1대 장애 실증 | 인프라 | ISR 3→2, 리더 자동 선출, acks=all 쓰기 지속 — 앱 경로는 미검증 | 부분 |
| [IMP-017](IMP-017-pdb-node-drain.md) | 노드 드레인 중 무중단(PDB) | 인프라 | 2,400건 5xx **0건** — 파드 5개 여유 조건 | 완료 |
| [IMP-018](IMP-018-kopis-detail-hotpath-removal.md) | 사용자 요청이 외부 API 호출을 증폭시키던 구조 제거 | S02/S09 | 부하 중 외부 호출 **약 22,000→0건**(제한 34배 초과 해소) · 응답시간은 측정 환경 변동으로 미주장 | 완료 |
| [IMP-019](IMP-019-ci-backend-context-boot.md) | CI 백엔드 느림 — 컨텍스트 부트를 붙잡던 KafkaAdmin 토픽 생성 | 전반(CI) | backend 잡 9m46s→3m08s (-68%) | 완료 |
| [IMP-020](IMP-020-seat-map-cache.md) | 좌석맵 조회 캐시 — 그리고 캐시만으로는 커넥션이 줄지 않았다 | S04·S10 | api CPU 2.55→1.56 ms/req(1.63배), node 5.39→3.89(1.38배), 서버 p99 24.5→9.7ms, 커넥션/요청 1.005→0.025 | 측정 완료 · **운영 미적용**(무효화 미구현) |
| [IMP-021](IMP-021-cluster-autoscaler-node-scaling.md) | Cluster Autoscaler 실증(노드 확장·축소) | 인프라 | Pending 4개 → 노드 **3→4(110초)** → 해소, 부하 제거 후 **4→3(739초)**. ⚠️ HPA 상한을 일부러 올린 조건 | 완료 |
| [IMP-022](IMP-022-rds-connection-timeout.md) | Hikari `connection-timeout` 30초 → 3초 (RDS 페일오버 AS-IS/TO-BE) | 인프라 | **실패 요청의** 대기 총합 **4,514초 → 1,341초(−70%)**, 30초 부근 실패 **146건 → 3건**. ⚠️ 실패 건수는 **181 → 400(2.16% → 4.66%)** — 개선이 아니라 트레이드오프. ⚠️ 성공 요청의 대기 시간은 기록하지 않아 30초 대기의 효용은 판정 불가 | **측정 완료 · 채택 보류**(n=1) |
| [IMP-023](IMP-023-api-memory-sse-connections.md) | api 메모리 한도 1Gi → 2Gi(SSE 대기 연결) | 인프라 | before: 단일 Pod SSE 3,000 연결 working set **1,009MiB**·약 3,300~3,500 연결 시점 **OOMKilled**, S-10K에서 api 3개 중 **2개 OOMKilled** → 사후 정합성 판정 불가. after(2Gi, 같은 workload, 1회): OOM **0**, liveness 실패 뒤 api **3개 재시작**(before에도 liveness 시간 초과 있었음), 진입 p95 14.26s → 23.04s(배치·HPA 차이로 원인 미분리) — 단계 6 판정 불가 **그대로** | **측정 완료 · 판정 장애 해소 안 됨** |
| [IMP-024](IMP-024-api-liveness-under-thread-saturation.md) | api liveness 실패 허용 3회 → 12회(요청 스레드 포화 중 재시작 완화) | 인프라 | before(2Gi, S-10K): liveness 시간 초과로 api **3개 모두 재시작**(첫 실패 후 약 20초). 진단 덤프: Redis 응답 대기·Hikari(풀 5) 대기·톰캣 등록 락 경합(재시작된 파드)이 함께 관측 — 원인 미확정. after(1회): 재시작 **3 → 0**, 단계 6 사후 정합성 판정 불가 → **0(위반 없음)**. 대신 진입 오류 6.48%(504 640 — ALB→web 연결 실패 — · 502 8)가 새로 나타남 | 측정 완료 · 새 오류 원인 미확정 |
| [IMP-025](IMP-025-queue-path-next-hop-bypass.md) | 대기열 진입·상태 경로 Next 홉 우회(ALB URL rewrite, ADR-024) | S10 | 공개 ALB 1,000/s·60초 판정 3회 중앙값: 진입 CPU(web+api) **3.037 → 1.078 ms/req(−64.5%)**, p95 157.5 → 15ms, 오류·dropped 0, 정합성 위반 0 — 사전 등록 합격식 통과 | 측정 완료 |
| [IMP-026](IMP-026-queue-entry-db-redis-cost.md) | 대기열 진입 1건의 DB 조회·Redis 왕복 줄이기(판매 상태 캐시 #345 · 발급 Lua 순번 반환 #346) | S10 | 공개 ALB·api 3·노드 3, 칸별 **1회**: DB 획득 **1.01 → 0.011/req**, Redis 명령 **7.03 → 2.03/req**, Redis 메인 스레드 0.172 → 0.098 ms/req(4,000/s), 파드당 처리 한계(비포화 최고 rate ÷ 3, 램프 1,000/s 간격의 하한값) **667 → 1,333 req/s**(두 변경 누적, 칸 조건 다름 — 본문 §7). ⚠️ api CPU 변화(−10~−26%)는 1회 비교라 참고값 | 측정 완료(1회) |
| [IMP-027](IMP-027-entry-burst-10k-capacity.md) | 대기열 진입 10,000/s × 10초 burst 용량 검증(api 9·노드 9, 세션 한정 사전 확장) | S10 | 판정 3회: 99,992~99,994건 **전부 200·dropped 0·응답 p95 중앙 194 ms(k6, 연결 수립 제외)·정합성 위반 0** → 사전 등록 합격식 충족(구성 방법은 사전 등록과 다름 — 세션 한정 확장). ⚠️ 발생기 새 연결 지연으로 첫 1초 송신 추정 약 2천(연결 미리 맺기 #347 뒤 1회: 매초 약 1만·p95 331 ms·오류 0). Redis 메인 스레드 0.86~1.02코어 — 다음 한계 후보. "10만 대응" 아님(시험 ⑤ 전) | 측정 완료 · 최종 시험 전 |
| [IMP-028](IMP-028-queue-status-lua.md) | 대기 상태 조회 Redis 왕복 6 → 2(STATUS Lua, #349) | S10 | 공개 ALB 3,000/s × 45초 판정 3회 중앙값: api CPU **0.635 → 0.482 ms/req(−24.1%)**, Redis 메인 스레드 0.115 → 0.071 ms/req, Redis 명령 5.99 → 2.04/req, p95 11.1 → 6.0 ms — 사전 등록 합격식 통과. ⚠️ after 파드는 새로 떠 예열 조건이 다름, after r3 무효(순간 지연) → r4 대체(사전 등록에 없던 사후 절차 — 결과는 r4와 무관하게 합격, 계산) | 측정 완료 |
| [IMP-029](IMP-029-final-100k-10s-queue.md) | 최종 시험 — 100,000명 / 10초 대기열 진입 + 대기(폴링) + 입장 제어(세션 한정 사전 확장 api 9·노드 9) | S10 | 3차 사전 등록 판정 3회: ALB 창 [T0, T0+10s) 진입 **100,000 × 3·전부 200**, 폴링 약 127만 건/run 오류 0, over-admit·정합성 위반 0, 발생기 CPU < 80% — 6축 충족. Platform은 별도 시연(HPA 9→10·CA 노드 추가·축소 관찰, 세션 한정 설정). ⚠️ §8 기준 결론은 "100K를 목표로 측정"(Platform 미증명) · 세 번째 시도(1·2차 미충족과 바뀐 기준 §5) · 좌석·주문·결제 범위 밖 | 측정 완료 |
| [IMP-030](IMP-030-booking-e2e-correctness.md) | Downstream E2E(입장자) 시험 — 입장 100명 동시 선점·주문·결제, 역할 6개(정상·경합·더블클릭·실패 후 재시도·실패 후 포기·1인 한도) | S10·S04/S05 | 판정 3회: 클라이언트 기대값·DB 기대값 13개·기존 사후 검사(이벤트 유실 0 — 발행 59 = 소비 59) **전부 통과**. ⚠️ 정합성 시험(처리량 아님), run당 입장자 100명, 결제는 세션 한정 mock | 측정 완료 |

## 누적 지표 보드
프로젝트 전체에서 모은 정량 성과 요약: [METRICS.md](METRICS.md)
