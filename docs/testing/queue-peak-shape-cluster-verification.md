# 대기열 평시·피크 모양 전환 — 클러스터 검증 계획(사전 등록 초안)

- 작성: 2026-10-27
- 상태: 실행함(2026-10-27, 세션 `20261027-qpeak`). 결과는 [[IMP-050]]. 확정한 판정 기준·run 횟수와 세션 중 바꾼 것(V2b 추가, V4 예매 ① 시각 T0 − 180초 → T0 − 120초와 예매 ② 실행 시작, V5 10분)은 세션 폴더의 사전 등록 파일과 [[IMP-050]] §3에 있다.
- 대상: [[ADR-031]](평시 3대·피크 9대 전환), [[IMP-049]](kwok 시뮬레이션·로컬 측정으로 확인한 범위)
- 관련: `docs/testing/loadtest-diag-runbook.md`(§0 자동 동기화, §2b 전환 절차, §5b 결제 mock, §6 run 규칙, §7 세션 끝), `docs/testing/traffic-profile.md`(가정 부하), [[IMP-035]](B 10만 진입 판정), [[IMP-033]](폭증 중 예매 지연 비교 방법)

## 1. 목적
[[IMP-049]]는 배치를 kwok(실제 kube-scheduler, CA는 흉내)으로, 평시 동거를 로컬 상대 비교로 확인했다. 실제 클러스터에서만 확인할 수 있는 것을 잰다.
1. 실제 Cluster Autoscaler가 평시 3대로 줄이고, 피크 중 api 노드를 지우지 않는가.
2. 사전 확장 전환(대기열 롤링 + 노드 추가)이 오픈 전 여유(15분) 안에 끝나는가.
3. 평시 동거의 EKS 값이 로컬 판정과 같은 방향인가.
4. 예매 api 200m에서 오픈 부하 중 예매 지연이 1100m 때와 비슷한가(이번 변경에서 성능 쪽으로 재지 않은 유일한 차이).

## 2. 준비
### 2.1 클러스터 없이
- 사전 등록 파일: 아래 기준·run 횟수·순서·무효 조건을 측정 전에 적고 시각을 `date`로 남긴다.
- 전환 기록 스크립트: Pod(Pending·Running·Ready)·노드(추가·Ready·삭제) 이벤트와 ArgoCD 동기화 시작·완료·리비전을 1초 단위로 남긴다. 전환 시간은 이 기록으로만 잰다.
- 전환 PR 3개를 미리 만들어 둔다: ① queue-peak 켜기 + 대기열 하한 6 + event·off-by, ② 하한 2, ③ queue-peak 끄기 + event·off-by `-`.
- 부하 도구(있음): 평시 조회 `infra/k6/browse-mix.js`, 예매 묶음 `infra/k6/booking-e2e.js`, 진입 `infra/loadgen/run-entry.sh`(도착 집계 `entry-arrivals.mjs`), 폴링 `infra/loadgen/poll-hold.mjs`,
  정합성 `scripts/loadtest/check-correctness.sh`·`check-booking.sh`.

### 2.2 자동 동기화와 결제 mock(V2~V7 구간)
예매 묶음(`booking-e2e.js`)은 결제 mock이 필요하다(`check-booking.sh`의 `approved_not_mock`). mock은 자동 동기화가 꺼져 있어야 켤 수 있고 api를 롤아웃한다(런북 §5b).
피크 모양에서는 api 롤아웃이 노드 상한 9에 막히므로(아래 2.3) mock은 평시 모양에서 켜고 끈다.
- V1이 끝나면(평시 모양) 자동 동기화를 끄고(런북 §0) `diag.sh pay-mock-on` → `pay-status`로 ConfigMap·파드 값 확인.
- V3·V6·V7의 PR은 머지한 뒤 **수동 동기화**로 반영한다. 수동 동기화는 Git에 있는 필드를 Git 값으로 되돌리므로(런북 §0) ConfigMap `PAYMENT_GATEWAY`가 `toss`로 돌아간다(추론).
  동기화 직후 재시작 없이 ConfigMap만 mock으로 다시 걸고(`kubectl -n flowticket patch configmap flowticket-api-config --type merge -p '{"data":{"PAYMENT_GATEWAY":"mock"}}'`),
  `pay-status`로 ConfigMap과 파드 값을 확인한다. 이미 뜬 파드는 기동 때 값을 쓰지만, 이후 새로 뜨는 api 파드(2.3의 전환)가 ConfigMap을 읽기 때문이다.
- 매 run 전에 `pay-status`를 확인한다.
- V7이 끝나면(평시 모양) `pay-mock-off` → 자동 동기화 켜기 → Synced·리비전 확인(런북 §7).
- 전환 시간은 수동 동기화 경로로 잰다. 시작점은 PR `mergedAt`(`gh pr view --json mergedAt`)과 수동 동기화 시작을 둘 다 기록하고, 판정은 동기화 시작부터 잰다. 운영 경로(자동 동기화)의 폴링 지연은 이 시간에 들어가지 않는다.

### 2.3 V4의 api requests 전환(피크 모양에서)
피크 모양에서 api requests만 바꾸면 롤링 surge Pod가 10번째 노드를 요구해 멈춘다. api는 노드마다 하나(hostname 분산, 옛·새 Pod를 함께 센다)이고 대기열 노드에는 required 회피로 못 들어가며, 노드는 최대 9대다(런북 §2b와 같은 이유).
- 전환은 run 밖에서만 한다. 세션 한정으로 api Deployment의 strategy를 `maxSurge 0`·`maxUnavailable 1`로 바꾸고 requests를 바꾼다(Pod를 하나씩 지우고 그 자리에 새로 띄운다). 전환 중 api는 2개다.
- 1100m로 바꾸기 전에 `kubectl describe node`로 api 노드마다 900m 이상 남는지 확인한다. 모자라면 그 조건은 무효로 기록한다.
- 전환 뒤 예매 묶음 워밍업 1회(판정 제외)를 하고 run을 시작한다.
- 끝나면 requests를 200m로(같은 strategy로), 그다음 strategy를 `maxSurge 1`·`maxUnavailable 0`으로 되돌리고 기록한다(strategy만 바꾸면 롤아웃은 일어나지 않는다).

## 3. 단계와 판정 기준(후보)

| 단계 | 하는 일 | 확인 항목 | 통과 기준(후보) |
|---|---|---|---|
| V1 평시 배치 | 클러스터를 올리고 CA 축소(10분 대기)가 끝날 때까지 둔다 | 노드 수, 노드별 Pod, Pending | 노드 3, api 노드당 1, 대기열 노드당 1, Pending 0 |
| V2 평시 동거 | 평시 조회 100 req/s(web 경유) + 예매 묶음 100명, run 3회 | api Pod별 서버 p95(`http_server_requests`, Pod·URI 라벨) | 아래 판정식, 5xx 0 |
| V3 사전 확장 전환 | 평시 부하를 건 채 PR ① 머지·수동 동기화 | 동기화 시작 → 대기열 6 Ready·노드 9까지 시간, 동거, web Ready, ALB 5xx | 15분 안, 동거 0, web Ready ≥ 1, 5xx 0 |
| V4 오픈 부하 | 진입 10,000/s × 10초 + 대기자 폴링 + 예매 묶음(① 오픈 전, ② 폴링 중), run 4회 | 진입·대기열은 [[IMP-035]] 6축, 예매 p95 ② | 6축 충족, 아래 판정식 |
| V5 피크 유지 | 오픈 뒤 피크 모양으로 15분 이상 | 실제 CA의 api 노드 축소 여부 | 노드 9 유지, api 노드당 1 |
| V6 되돌림 1 | PR ② 머지·수동 동기화 | HPA 축소(안정화 300초)·CA 축소 시간 | 노드 5, 동거 0 |
| V7 되돌림 2 | 대기자 0 확인 뒤 PR ③ 머지·수동 동기화 | 평시 모양 롤링, CA 축소 시간, 롤링 직후 HPA 일시 증가 | 노드 3, Pending 0, api 노드당 1 |

### 3.1 판정식(후보)
- **V2**: run마다 예매 URI(hold·주문·결제)와 조회 URI의 서버 p95를 Pod별로 구한다.
  대기열과 같은 노드의 api Pod 2개 중 큰 값 ÷ 혼자인 api Pod 값이 두 URI 묶음 모두 1.30 미만인 run이 3회 중 2회 이상이면 "차이 작음".
  교란 요인: AZ·DB 거리, web·모니터링 Pod 배치(노드마다 다르다). Pod 위치가 고정이라 순서를 바꿔 반복할 수 없다. 이 한계를 결과에 적는다.
- **V4**: 순서는 200m → 1100m → 1100m → 200m(전환은 2.3). run마다 예매 p95 ②(hold·주문·결제 p95의 중앙값)를 구한다.
  가장 보수적인 짝([[IMP-033]] v2와 짝 고르는 방식만 같다. 거기서는 run 안의 ②/① 비율을 비교했다) max(200m 2회) ÷ min(1100m 2회)가 1.30 미만이면 "차이 작음".
  두 조건 모두 queue-peak가 켜진 상태라 대기열 구성은 같다. 폴링은 traffic-profile §3 가정(3,200~3,500/s)으로 두고 폭증 시험(7,000/s)은 넣지 않는다.

### 3.2 run 조건·무효
- V4 첫 run 전에 새 대기열 Pod 예열: 예열 공연에 진입 3,000/s × 20초(판정 제외, [[IMP-035]] §3).
- 공연은 용도별로 따로 쓴다: 대기 인구용, 예매 묶음용(대기 인구 뒤에 줄 서지 않게), 예열용. run 사이 리셋은 런북 §6.
- 무효 조건(후보): 진입 `usersNotArrived` > 0, 연결 대기 > 100ms 비율 > 0.1%, 발생기 CPU 1초 최대 ≥ 80%, 진입 dropped > 0, `pay-status`가 mock이 아님, 측정 중 재시작, 도구 오류.
  무효 run은 같은 자리에서 다시 재고 기록을 남긴다.
- **대기자 0 확인(V7 전)**: V4에 쓴 공연마다 대기열 키를 지우고 활성 목록에서 뺀다(런북 §6). 폴링 발생기를 멈춘 뒤 대기열 상태 조회 요청률이 0에 가까운지 Prometheus로 확인한다. 대기자가 실제로 빠져 0이 되는 자연 소진은 재지 않는다.

## 4. 실패하면
- V1·V7에서 3대가 안 되면: CA 로그에서 축소를 막은 이유(PDB·사용률·시스템 Pod·safe-to-evict)를 확인한다. 시뮬레이션이 흉내 내지 못한 부분이면 IMP-049에 덧붙인다.
- V3이 15분을 넘거나 동거가 생기면: 사전 확장 시점을 앞당기거나 PR ①을 둘(queue-peak 먼저, 하한 다음)로 나누는 안을 시뮬레이션으로 다시 본다.
- V4에서 예매 p95가 기준을 넘으면: queue-peak가 켜질 때 예매 api requests도 올리는 안을 따로 검토한다. 그 안도 피크 모양에서 api 롤링이 필요해 2.3과 같은 문제를 풀어야 한다.
- 전체 되돌리기: #470을 revert하면 이전 구성(api·대기열 1100m, 평시 5대)으로 돌아간다.

## 5. 예상 시간·비용(예측)
- 시간: 올리기·안정화 약 30분, V1~V7 약 4시간(V4 4 run과 api 전환 2회·워밍업 포함).
- 비용: 시간당 $2.06(평시 5대·발생기 3대)~$2.55(노드 9대·발생기 3대)([[IMP-040]] 계산, 평시 3대면 조금 낮다) × 약 4.5시간 ≈ $9~11.5.
  세션 뒤 `scripts/loadtest/cost-summary.mjs`로 다시 계산한다.

## 6. 남길 기록
- 세션 폴더: 사전 등록, `session.log`(결정·시각·세션 한정 변경과 원복), 전환 기록, k6·ALB·Prometheus 내보내기, 정합성 결과.
- 결과 문서: IMP(실측) 하나로 묶고 [[IMP-049]]·[[ADR-031]]의 "확인하지 못한 것"을 갱신한다.
