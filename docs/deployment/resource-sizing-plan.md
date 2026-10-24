# 자원 산정·측정 계획 — Kafka 제거 뒤 B₁·B′·가용성 G1 (후보·계획만, 최종 수치는 측정 뒤 확정)

- 상태: **계획(측정 전)**. 이 문서는 **후보와 측정 절차**만 정한다. 최종 requests·Hikari 풀·HPA·PDB·노드 수는 B₁·B′ 반복과 가용성 G1 시험을 거친 뒤 확정한다(사용자 결정 2026-10-14 — [kafka-removal-design.md](kafka-removal-design.md) §6 진행 순서 3).
- 날짜: 2026-10-15
- 관련: [operating-requirements.md](operating-requirements.md) §5(결정: G1·평시 B 2+2 출발 후보·L1+L3·B 실측 임시 기준), [kafka-removal-design.md](kafka-removal-design.md) §6.1·§6.2·§6.3·§7·§8, [[ADR-025]](api 1100m·HPA·사전 확장), [[ADR-026]](피크 여유·배치 정책 — 제안), [[ADR-027]](대기열 전용 Deployment), [[ADR-028]](Kafka 제거), [[IMP-033]](B₀ 실측), [[TS-021]](DB 커넥션 상한)
- 표기: **사실**(출처 있음) / **계산**(사실에서 산술로 낸 값 — 측정 아님) / **추론** / **미확인**(세션에서 확인). 계산값이 든 표는 열 이름에 "계산"을 붙인다.

## 0. 이 문서가 답하는 것과 답하지 않는 것
- 답한다: B′에서 바꿔 볼 후보(무엇을, 왜, 어떤 제약 안에서), 각 후보가 지켜야 할 계산상 제약(DB 커넥션·노드 자리·가용성 G1), 측정 세션의 순서·기록·사전 등록 항목, 세션 전에 사용자가 정할 것.
- 답하지 않는다: 최종 수치. 후보 수치는 B₁에서 잰 값(파드별 CPU-ms/req·Hikari·메모리·노드별 남은 자리)을 보고 사전 등록 때 고른다.

## 1. 현재 사실(B₁의 출발점)
| 항목 | 값 | 출처 |
|---|---|---|
| 노드(측정 프로파일) | m6i.large, AZ 3곳 × 노드 그룹 min 1·max 3 → 최대 9대. allocatable CPU 1,930m | `loadtest.tfvars`, `environments/demo/variables.tf`, [[ADR-025]] |
| 노드 allocatable 메모리 | 6.94GiB(m6i.large — 2026-10-20 기존 세션 원자료로 확인, [optimization-targets.md](optimization-targets.md) §2) | 세션 20261015-nok·20261017-b100k `node_allocatable` |
| api | requests 1,100m / 1,536Mi(limit 2Gi), HPA 3~9(CPU 60%), PDB minAvailable 2, maxSurge 1·maxUnavailable 0, AZ 분산 `ScheduleAnyway` | `k8s/base/api-*.yaml` |
| 대기열(B, `k8s/components/queue-split`) | api와 같은 사양, HPA 2~6, PDB minAvailable 1. component가 api HPA 상한을 3으로 낮춘다(api 3 고정) | `k8s/components/queue-split/*` |
| web | requests 300m / 256Mi, HPA 2~4, PDB minAvailable 1, 낮은 우선순위 | `k8s/base/web-*.yaml` |
| Hikari 풀 | 파드당 5(`DB_POOL_MAX` 기본값). 대기열 Deployment는 따로 덮어쓰지 않는다 | `application.yml:20`, `queue-deployment.yaml` |
| DB 커넥션 예산 | db.t4g.micro `max_connections` 79(2026-08-10 pg_settings 실측) − reserved 3 − 앱 외 여유 20 = **56** | 하네스 backend 규칙 17(`harness/backend/check.mjs`) |
| 사전 확장 | (2026-10-17 갱신) B가 운영 기본값이 되어 `event-prescale.yaml`이 대기열 HPA 하한을 올린다(평소 2, 오픈 전 6). 이전(작성 당시)에는 api HPA 대상이라 B용 사전 확장이 없었다 | [[ADR-025]], [operating-requirements.md](operating-requirements.md) §5 |
| 노드별 flowticket 밖 CPU 요청 | A' 평시 410·600·750m(세션 20261007-e2e, 레포 밖 실측 — Kafka 네임스페이스 몫이 들어 있었을 것으로 본다, 추론) | [[ADR-025]] 계산 근거 |
| Kafka 제거 뒤 노드별 flowticket 밖 요청 | 0.10~0.58코어(IMP-034 8 run), 0.18~0.66코어(IMP-035) — 2026-10-20 기존 세션 원자료로 계산(계산 — [optimization-targets.md](optimization-targets.md) §2) | 세션 원자료 `node_cpu_requests` − flowticket 파드 요청 |
| B₀ 실측(Kafka 있음) | 시작 노드 6·5, 예매 p95 ②(폭증 중) hold 256·272ms·주문 260·211ms·결제 224·184ms, 폭증 상태 조회 p95 259·523ms, 대기열 HPA 상한 6 도달, 노드-시간 3.17·3.45(계산) | [[IMP-033]] v2(B 2회 관측) |

## 2. 계산상 제약 — 후보가 먼저 통과해야 하는 것
### 2.1 DB 커넥션(하네스 규칙 17이 CI에서 검사)
식: `(api 상한 + surge) × api 풀 + (대기열 상한 + surge) × 대기열 풀 ≤ 56`.
| 구성 | 식(계산) | 합(계산) | 예산 56 대비(계산) |
|---|---|---|---|
| 현재 B(api 3, 대기열 2~6, 풀 5·5) | (3+1)×5 + (6+1)×5 | 55 | 여유 1 |
| 대기열 풀 3, 대기열 상한 q | 20 + (q+1)×3 | q = 11이면 56 | 대기열 상한 최대 11 |
| 대기열 풀 2, 대기열 상한 q | 20 + (q+1)×2 | q = 17이면 56 | 대기열 상한 최대 17 |
- **결론(계산):** 지금 B는 예산에 붙어 있다. 풀 5를 유지한 채로는 대기열 상한을 하나도 늘릴 수 없다. 대기열 상한을 늘리려면 **대기열 풀을 줄이는 것이 먼저**다([[IMP-033]] §9 후보).
- 대기열 풀을 줄여도 되는지는 **대기열 파드의 Hikari active·pending을 재야 안다**(미측정 — [operating-requirements.md](operating-requirements.md) §4). 근거 후보(사실): 진입 경로의 DB 커넥션 획득은 진입 요청당 0.012~0.021(판매 상태 캐시 TTL 1초, [[IMP-033]] §8 — v1의 세 run(A′ 2·B 1), 스케줄러 사용 포함, 세션 조회·원시 응답 미보존). 이 값은 **획득 횟수의 비율**이지 동시에 쥔 커넥션 수(active)가 아니다 — 풀 크기 판단에는 active·pending이 필요하다. 상태 조회 경로는 DB를 쓰지 않는다고 본다(추론 — B₁에서 Hikari active로 확인). 대기열 파드에서도 스케줄러(아웃박스 릴레이 등)가 돌며 DB를 쓴다(사실: 같은 이미지이고 스케줄러는 `flowticket.scheduling.enabled`(기본 true) 하나로 켜지는데 대기열 Deployment는 이 값을 덮어쓰지 않는다 — [workload-responsibilities.md](workload-responsibilities.md)).
- api 풀(5)은 이 계획에서 줄이지 않는다 — 예매 경로가 DB를 쓰고, 1,800 rps 파드당 active 5·pending 116(초기 구성, [[TS-034]])의 기록이 있다(그 구간은 노드 CPU 83%로 오염됐고, TS-034도 "풀이 병목"이라는 해석을 정황이지 확정이 아니라고 적었다).

### 2.2 노드 자리(CPU requests 기준)
- api·대기열 1,100m는 노드당 하나만 들어간다(2 × 1,100 > 1,930 — 계산). 그래서 api 계열 파드 수 = 최소 노드 수다.
| 구성 | api 계열 파드(계산) | CPU requests 하한 노드 수(계산) | 노드 그룹 최대 9 대비(계산) |
|---|---|---|---|
| 평시 A′(통합 — api 3, 대기열 Deployment 없음) | 3 | 3 | 남는 자리 6 |
| 현재 B 평시(api 3 + 대기열 2) | 5 | 5 | 남는 자리 4 |
| 현재 B 대기열 상한(api 3 + 대기열 6) | 9 | 9 | 0 — 이 상태에서 롤링 surge(+1,100m)는 들어갈 노드가 없다([[ADR-025]] 결과·대가와 같은 구조) |
| 2+2 출발 후보(api 2 + 대기열 2) | 4 | 4 | 남는 자리 5 |
- 하한은 실제 대수가 아니다. web 2개(300m)와 flowticket 밖 요청이 api 옆 남은 자리(1,930 − 1,100 − 밖 요청)에 들어가지 못하면 노드가 더 붙는다. B₀은 5·6대, 같은 세션의 A′는 4대에서 안정됐다([[IMP-033]] 실측 — Kafka 있음).
- [operating-requirements.md](operating-requirements.md) §5(평시 운영 방식)는 "평시 A′"와 "평시 B 2+2"의 노드 수를 함께 계산해 차이가 크면 B 상시 결정을 다시 검토하라고 했다. 하한(계산)은 A′ 3대·B 2+2 4대다. 실제 대수는 Kafka 제거 뒤 밖 요청을 알아야 낼 수 있어 B₁의 노드별 기록으로 계산하고, 비교와 재검토 여부는 확정판에서 적는다.
- **대기열 requests를 줄여 노드당 2개를 넣으려면(계산):** `2 × r + 밖 요청 ≤ 1,930`. 밖 요청이 Kafka 제거 전 A′ 평시 노드 값(410~750m)이면 r ≤ 590~760m, 사전 확장으로 새로 붙은 노드처럼 밖 요청이 약 180m(계산 — [[ADR-025]] 보충)이면 r ≤ 875m로, 노드마다 다르다. Kafka 제거 뒤 밖 요청은 작성 당시 미확인이었다 — 2026-10-20 기존 세션 원자료로 0.10~0.58코어(IMP-034)·0.18~0.66코어(IMP-035)를 계산했다([optimization-targets.md](optimization-targets.md) §2). **r의 후보는 이 값으로 다시 계산한다**.
- requests를 줄이면 HPA 기준도 바뀐다 — CPU 60% 목표는 requests 기준이라 r이 작아지면 같은 부하에서 더 일찍, 더 많이 확장한다. 그 파드 수가 §2.1 DB 예산과 §2.2 노드 상한 안에 들어야 한다.

### 2.3 가용성 G1 — 노드 1대가 갑자기 사라져도 핵심 경로가 계속 도는가
- 지금은 **1,100m라서** api 계열 파드가 서로 다른 노드에 놓인다(자원 때문에 생긴 성질이지 규칙이 아니다). requests를 줄이면 **같은 Deployment의 파드 둘이 한 노드에 놓일 수 있고, 그 노드를 잃으면 둘 다 사라진다** — 대기열 2개가 한 노드에 있으면 새 진입이 멈춘다(G1 위반). 그래서 requests를 줄이는 후보는 **노드 단위 분산 규칙과 한 쌍**이다. 규칙은 hostname `topologySpreadConstraints`(maxSkew 1, **`whenUnsatisfiable: DoNotSchedule`**)로 둔다 — 같은 Deployment 파드가 노드마다 고르게 퍼지되 한 노드에 둘 이상도 허용한다. `ScheduleAnyway`(지금 zone 분산의 방식)는 선호일 뿐이라 대기열 둘이 한 노드에 놓이는 것을 막지 못한다. required anti-affinity는 한 노드에 둘을 아예 막아 P3의 목표(노드당 2개)와 맞지 않는다.
  - **한계 — 축소 뒤에는 분산이 보장되지 않는다.** topologySpread는 스케줄 시점에만 적용되고, HPA가 파드를 줄일 때 다시 맞추지 않는다(Kubernetes 문서의 알려진 한계 — 이 클러스터에서 재현하지 않음). 예: 노드 3대에 2·2·2로 퍼진 대기열이 평시 2개로 줄면 남은 둘이 같은 노드에 있을 수 있고, 그 노드를 잃으면 새 진입이 멈춘다(G1 위반). required anti-affinity는 처음부터 한 노드에 하나라 축소 뒤에도 분리가 유지된다 — 즉 "노드당 2개(P3의 자리 이득)"와 "축소 뒤에도 분리 보장(G1)"은 맞바꾸는 관계다. 축소 뒤 재배치(descheduler 등)는 새 의존성이라 후보로만 적는다. 그래서 P3를 채택하려면 아래 시험 전 계산에 **폭증 뒤 최소 파드 수로 줄어든 상태의 배치**를 넣어 확인한다. [[ADR-026]] B1은 "노드당 하나"를 명시하는 안이라 목표가 다르다(B1은 requests와 배치를 떼어 내는 것, P3은 노드당 자리를 늘리는 것). maxSkew 계산에서 대기열 파드가 0개인 노드(api만 있는 노드 등)가 도메인으로 세어지면 노드당 2개가 기대만큼 생기지 않을 수 있다(추론, 미확인 — §7).
- PDB: api `minAvailable 2`는 api 2개 구성에서 드레인을 막는다(비자발적 장애에는 PDB가 작동하지 않지만, 노드 작업·CA 축소가 막힌다). 2+2 후보는 api PDB를 1로(또는 `maxUnavailable 1`) 함께 바꾼다([operating-requirements.md](operating-requirements.md) §5 단서).
- **시험 전 계산(사전 등록에 넣는다):** 최종 B′ 후보의 실제 배치(`kubectl get pod -o wide`)와 노드별 남은 자리(`node_allocatable` − `node_cpu_requests`·`node_memory_requests`, §4)로, 노드마다 "이 노드를 잃으면 그 위의 파드가 남은 노드에 들어가는가"를 표로 만든다. 배치는 두 번 본다 — 시험 시작 상태와, **폭증 뒤 HPA가 최소 파드 수로 줄인 상태**(분산 규칙이 축소 뒤를 보장하지 않으므로 — 위 한계). 들어가지 않으면 CA 증설(실측 110초, [[IMP-021]]) + 파드 기동·Ready + 워밍업이 끝날 때까지 남은 복제본만으로 버텨야 한다 — 그 시간 동안 Deployment마다 Ready 1개 이상이 남는지가 G1의 최소 조건이다. 남지 않으면 시험 전에 후보를 고친다.

## 3. B′ 후보 — 무엇을 바꿔 보나(수치는 B₁ 뒤 사전 등록 때)
각 후보는 독립 변수 하나씩 바꾸는 것을 원칙으로 하고, 한 PR(→ CI·하네스 → G1 리뷰 → 머지 → ArgoCD 동기화·이미지/설정 확인)으로 적용한다. k8s만 바뀌는 조정은 이미지를 다시 빌드하지 않는다(`image.yml` 변경 판별에 `k8s/`가 없음 — [[ADR-025]]).
| 후보 | 바꾸는 것 | 근거 | 먼저 필요한 측정·작업 | 제약 |
|---|---|---|---|---|
| **P1 대기열 풀 축소** | 대기열 Deployment에 `DB_POOL_MAX` env(예: 2~3) | DB 예산이 대기열 상한을 묶고 있다(§2.1) | B₁의 대기열 파드 Hikari active·pending 최댓값. **하네스 준비됨**: k8s 규칙 ⑩-b(대기열 파드 사양 = api 파드 사양)에 허용 목록을 두었다 — 대기열 `DB_POOL_MAX` env(대기열 쪽만 — api Deployment에 두면 규칙 17이 읽지 못하므로 따로 실패, 위반 fixture `k8s-api-pool-env`), `resources.requests.cpu`, hostname `topologySpreadConstraints`만 비교에서 뺀다(2026-10-24에 대기열 readiness env `HEALTH_READINESS_INCLUDE`를 더함, ADR-030). 목록 밖 차이는 그대로 실패한다(위반 fixture `k8s-queue-spec-drift`(메모리 차이)·`k8s-queue-allowed-plus-drift`(허용 차이 + 종료 유예 차이)) | 규칙 17은 `queue-deployment.yaml` 원문의 `DB_POOL_MAX`를 읽는다 — env를 component 패치로 넣으면 ⑩-b는 피하지만 규칙 17이 풀을 5로 계산한다(실패 쪽으로 틀린다). 그래서 원문에 두고 ⑩-b를 고치는 쪽을 권한다 |
| **P2 대기열 상한 조정** | 대기열 HPA `maxReplicas` | B₀에서 상한 6 도달·폭증 상태 조회 p95 259·523ms | P1(풀 — 상한을 올리면 DB 예산을 넘는다) **그리고** P3(requests 축소) 또는 노드 예산 변경. 1,100m에서는 api 3 + 대기열 6이 이미 노드 9대를 채운다(§2.2) — **1,100m에서 P2는 6을 넘길 수 없다** | 노드 그룹 최대 9(§2.2). 상한을 AZ당 4로 올리면 노드 12 × 2 vCPU + 발생기 12 vCPU = 36 > 쿼터 32(계산 — `loadtest.tfvars`의 쿼터 식, [[ADR-025]]) |
| **P3 대기열 requests 축소 + 노드 단위 분산** | 대기열 `requests.cpu`(r), hostname 분산 규칙 | 노드당 2개 → 같은 노드 수에서 대기열 처리 자리 증가([[IMP-033]] §9) | B₁의 노드별 밖 요청·대기열 파드 CPU 사용(`app_cpu_by_pod`)·상태 조회 처리량. ⑩-b 허용 목록(준비됨 — requests·hostname 분산). 짝 조건도 하네스가 본다: 대기열 CPU 요청이 api와 다르면 대기열 파드만 고르는 hostname 분산(maxSkew 1·`DoNotSchedule`, selector가 대기열 파드 라벨의 부분집합이고 `app: flowticket-api-queue` 포함)이 있어야 한다(위반 fixture `k8s-queue-cpu-no-spread`(ScheduleAnyway)·`k8s-queue-spread-wrong-selector`(api 파드 selector)·`k8s-queue-spread-skew2`(maxSkew 2)) | §2.2 r 범위, §2.3 분산 규칙(topologySpread maxSkew 1), HPA 기준 변화 |
| **P4 대기열 축소 정책** | 대기열 HPA `behavior.scaleDown`(안정화 창 등) | v1 b1r에서 폭증 시작 22초 뒤 대기열 파드가 3→2로 줄었다([[IMP-033]] §4 — 판정에 쓰지 않은 v1 run의 1회 관측, 축소가 폭증 지연의 주원인이라는 것은 추론) | — | 오픈이 아닌 시간의 비용과 맞바꿈 |
| **P5 평시 2+2** | api HPA 하한 2·PDB 1, 대기열 2 | G1 출발 후보([operating-requirements.md](operating-requirements.md) §5) | B₁의 api 파드 예매 경로 CPU·지연(api 3개가 예매를 전부 받는다 — [[IMP-033]] §6) | §2.3 PDB, 남은 1개가 평시 부하를 감당하는지(G1). **적용 위치는 queue-split component 패치**로 한다 — base `api-hpa.yaml` 하한을 2로 바꾸면 A′에도 영향이 간다(작성 당시에는 `event-prescale.yaml`(api 하한 3)이 규칙 11에 걸렸다 — 2026-10-17부터 사전 확장 대상은 대기열 HPA) |
| **P6 B용 사전 확장(설계만 — 2026-10-17 구현: 대기열 HPA 2→6, 규칙 11 대상별 base, [[ADR-027]])** | 오픈 전 대기열 하한 상향(api는 고정) | L3(예정된 오픈 뒤 추가 폭증)와 평시 B 상시 + 사전 확장 결정 | 하네스 k8s 규칙 11을 component 기준으로 바꾸는 작업(+ 위반 fixture) | ADR-025 사전 확장과 동시에 켤 수 없다 |
- 순서 제안(추론 — 의존 관계): P1 → P3 → P2(P1·P3 둘 다 필요), P4는 독립, P5는 마지막. P1·P3에 필요한 ⑩-b 하네스 수정은 준비됐다(허용 목록·짝 조건). P6은 B′ 측정 대상이 아니라 운영 정책 설계로 따로 진행한다.
- **시도한 후보를 모두 기록한다**(가장 잘 나온 회차만 고르지 않는다 — [kafka-removal-design.md](kafka-removal-design.md) §7.5).

## 4. 측정 — 무엇을 기록하나
B₀([[IMP-033]] v2)과 같은 기록에 아래를 더한다. `export-prom.mjs`에 질의를 추가했다(이 PR).
| 기록 | 질의 이름(`prom/<이름>.json`) | 쓰는 곳 |
|---|---|---|
| Hikari active·idle·total(파드별) | `hikari_active`·`hikari_idle`·`hikari_total`(기존 `hikari_pending`과 함께) | §2.1 P1 풀 축소 근거 |
| JIT 컴파일 누적 시간(파드별) | `jvm_compilation_ms` — 지표 이름은 Micrometer 기준, **존재 여부 미확인**(빈 결과면 이름부터 확인) | 워밍업 확인(B₀ 사전 등록이 보려다 못 본 값) |
| 컨테이너 메모리 working set(파드별) | `app_memory_working_set_by_pod` | 메모리 requests 판단(이번 후보에는 메모리 변경이 없다 — 근거만 모은다) |
| 노드별 requests 합(CPU·메모리, 모든 네임스페이스 Running 파드)·allocatable | `node_cpu_requests`·`node_memory_requests`·`node_allocatable` — `kube_pod_container_resource_requests`의 `node` 레이블에 기대는 질의다(**미확인** — 빈 결과면 `kubectl describe node`의 Allocated resources로 대신 기록) | §2.2 노드 자리, §2.3 G1 시험 전 계산 |
- 세션 기록(`session.log`)에 run마다: 시작 노드 수와 노드별 파드 배치(`app_pod_nodes`), 이미지 태그(`app_images`), queue-split 적용 방법(Git component 또는 세션 스크립트), CA 안정 상태, Kafka 유무.
- 예매 경로 CPU-ms/req는 파드별 CPU(`app_cpu_by_pod`)와 경로별 처리량(`http_rps_by_uri`)으로 **계산**한다 — 한 파드가 여러 경로를 받으므로 구간을 나눠 근사한다(정의는 사전 등록에 적는다).

## 5. 세션 순서와 각 단계의 통과 조건
[kafka-removal-design.md](kafka-removal-design.md) §6을 따른다. 아래는 그 순서에 이 문서의 기록을 붙인 것이다.
1. **세션 전(저장소)**: P3a(`SKIP_KAFKA`·표식 가드) 머지, 이 문서·질의 추가 머지, 저장소 P3 준비(#415 draft — 세션 검증 뒤 머지, 설계 §6 "세션 방식 변경"), B₁ 사전 등록 초안(B₀ v2 사전 등록을 바탕으로 조건 차이만 바꾼다), B′ 후보에 필요한 하네스 수정(⑩-b 허용 목록·짝 조건 — §3 P1·P3, 위반 fixture 포함 — 세션 전에 머지해도 동작이 바뀌지 않는다; **준비됨**), B′ 후보 PR 초안(머지하지 않음).
2. **기동 — Kafka 없이**(2026-10-15 사용자 결정, 설계 §6 "세션 방식 변경"): (세션 전) #415에 main 반영·CI·하네스 재통과 → 예약 철거 잔존 확인(`schedule-teardown.ps1 -Status`) → #415 체크아웃에서 `terraform init -backend-config=backend.hcl` → `terraform apply`(사용자 승인) → **#415 브랜치의 `bring-up.sh`**(Kafka 단계가 없는 스크립트 — 첫 기동이 곧 P3 스크립트의 실제 검증)로 기동, 쓴 커밋을 기록. 앱 매니페스트는 ArgoCD가 main에서 가져가고, 스크립트가 직접 적용하는 모니터링·helm values·`application.yaml`·ESO·시딩·Terraform 구성은 #415 체크아웃에서 온다 — 기동 전 `git diff main <#415 커밋> -- scripts k8s infra/terraform`이 P3 범위뿐인지 확인해 기록한다(설계 §6 "세션 방식 변경"). 기동 직후 노드별 requests·allocatable을 기록한다(Kafka 없는 밖 요청 — §2.2 r 후보의 근거). 원래 계획의 N 게이트(설계 §6.1)·Kafka 운영 삭제(§6.2)는 쓰지 않는다.
3. **운영 검증**(설계 §6.1 N 게이트를 대신한다): 모든 api·대기열 파드가 N 이미지, 카드 결제 뒤 사이트 경유(브라우저 → Next → api) EventSource의 `order.paid` 수신 + 그 아웃박스 행 `PUBLISHED`(이벤트 수신 자체로 판정 — 완료 화면 전이는 결제 응답·폴링으로도 뜨므로 SSE의 근거가 되지 않는다, [TS-052](../troubleshooting/TS-052-sse-buffered-by-next-compression.md)), 아웃박스 PENDING 0·DEAD 증가 없음·발행 실패 로그 연속 없음, 구독자 0 발행 카운터 증가 없음, **새로고침·SSE 재연결 뒤 주문 상태 복구**(SSE 재연결 `onopen` 재조회 — 709e976·29eda2e, 탭 복귀·PENDING 주기 재조회 — #409). 설계 §6.1의 Kafka와 무관한 항목(이전 ReplicaSet `READY 0`, `oldest_pending.age`, 발행 실패는 `attempts`·`lastError`로도, 재시도 경로는 클러스터 미검증으로 기록, 틱 예산 경고 반복 시 중단)도 그대로 본다. 실패하면 측정으로 넘어가지 않고 보고한다 — 세션 중 즉시 롤백 경로가 없다(코드 수정 재배포, 또는 Kafka 재설치(P3(#415) 머지 뒤 기준: #415 revert + P2b revert 뒤 `bring-up.sh` 기본 실행. 머지 전에는 #415 revert 없이 **main 체크아웃의** `bring-up.sh` 기본 실행 + P2b revert — 세션이 쓰는 #415 체크아웃의 스크립트는 Kafka를 설치하지 않는다. 어느 쪽이든 운영 삭제 표식이 있으면 멈추고 `FORCE_KAFKA=1`로 넘어간다)).
4. **B₁** — 기존 B 설정 그대로(§1 표), B₀ v2와 같은 부하 시나리오·워밍업·시작 조건(최소 파드 Ready·HPA 최소·CA `NoCandidates`·`NoActivity` 3분 유지)·판정 정의. 조건 차이(세션 날짜·시간대, AMI·배치, 시작 노드 수, 이미지 N, Kafka 없음, 발생기 위치)를 결과에 함께 적는다. **B₀ 대비 차이를 Kafka 제거의 효과로 쓰지 않는다**(기준선 (a)).
5. **B′ 사전 등록**(B₁ 결과를 본 뒤, 첫 B′ run 전) → 후보마다 PR → 반영 확인 → run. 판정식·반복 횟수는 사전 등록에 고정한다.
6. **가용성 G1 시험** — 최종 B′ 후보에서. §2.3 계산을 먼저 하고, 허용 기준(아래 §6 D4)을 사전 등록한 뒤 시험한다.
7. **알림 경로 관찰 항목**(원래 설계 §6.3의 관찰 항목 — #415 머지 조건 "알림 경로 이상 없음"의 판정 기준)은 3~6 내내 본다: 아웃박스 PENDING·DEAD·발행 지연, 구독자 0 발행(`outbox-no-receivers.mjs`), 결제 뒤 사이트 경유 EventSource의 `order.paid` 수신(3단계와 같은 방식).
8. **세션 뒤:** 원본 자료·결과 보존 → 보고 → 저장소 P3(#415) 검토·머지(조건: 설계 §6 "세션 방식 변경" — 세션 동안 알림 경로 이상 없음(7단계 항목), 세션에 쓴 #415 커밋과 머지 시점의 차이(스크립트와 스크립트가 읽는 경로) 확인, CI·하네스·G1 리뷰 PASS) → **철거 지시를 기다린다**. 원래 계획의 "철거 → 새 클러스터 신규 기동 검증 → P3 머지"는 첫 기동(2단계)으로 갈음한다(같은 결정 — 검증 범위 차이는 설계 §6에 적었다).

- 정합성 판정은 B₀과 같은 `check-correctness.sh`·check-booking이다. Kafka 제거로 3단계가 "구독자 부재"로 바뀌었다(#412) — B₀의 "이벤트 유실"과 판정 대상이 다르다는 것을 조건 차이에 적는다.
- **한 세션 안에 끝난다고 전제하지 않는다.** 세션이 나뉘면 B′와의 같은 세션 비교를 위해 B₁을 다시 잴지 사전 등록 때 정한다(D5).

### 5.1 비용 통제와 철거 — 검증을 끝까지 하고, 철거는 지시를 기다린다
**결정(2026-10-15 14:54 KST — `date`, 사용자):** 비용 상한·세션 최대 시간을 두지 않는다. 목표는 검증이므로 **검증을 끝까지 진행**한다(§5 순서 1~7). 검증이 끝나면 결과를 보고하고 **철거 지시를 기다린다**(철거는 사용자의 명시적 지시가 있을 때만). **예약 철거(`scripts/loadtest/schedule-teardown.ps1`·`scheduled-teardown.sh`)는 쓰지 않는다** — 정해진 시각에 지시 없이 철거하기 때문이다.
- 검증이 하루를 넘기면 클러스터를 그대로 두고 이어서 진행한다 — 중간에 철거·재기동하면 기동 시간이 더 들고 같은 세션 비교를 위해 B₁을 다시 재야 한다(D5).
- 유료 리소스는 승인 없이 삭제하거나 축소하지 않는다(노드 그룹 축소·발생기 종료도 승인 대상).
- **비용 지표는 셋으로 나눈다**(섞어 쓰지 않는다 — 세션 비용 견적은 저장소 밖 B₁ 사전 등록에 둔다, 참고용):
  | 지표 | 판단하는 것 | 성격 |
  |---|---|---|
  | B₁ 대비 B′ 노드-시간(같은 부하 조건) | 자원 최적화가 EC2(노드) 사용량을 줄였는가 — §6 D3 ④ | 노드 수(`nodes_ready`)는 실측, 노드-시간은 그 샘플을 `cost-summary.mjs`로 계단 적분한 **계산값**, 달러 환산도 계산 |
  | 세션 전체 AWS 예상 비용 | 이번 실험을 하는 데 얼마가 드는가 | 사전 견적(예측) — AWS Pricing API 단가 × 구성(Terraform + ALB 등 클러스터가 만드는 것) × 시간. 청구액과 같다는 보장은 없다 |
  | 시험 종료 뒤 시간당 유지 비용 | 철거 지시까지 시간당 얼마가 더 드는가 | 사전 견적(예측) |
- 노드-시간이 줄어도 청구액이 같은 비율로 줄지 않는다 — NAT·EKS 컨트롤 플레인·RDS·ElastiCache·ALB·인터페이스 엔드포인트·클러스터 밖 부하 발생기 EC2(c6i.xlarge × 3)·EIP·EBS 등은 노드 최적화와 별개로 든다.
- 견적에서 정확히 낼 수 없는 항목(NAT 데이터 처리량, ALB LCU, CloudWatch·S3 로그, 데이터 전송)은 추정 범위 또는 "추정 어려움"으로 명시한다. AWS Budgets(월 $50, `flowticket-monthly-budget` — [terraform-design.md](terraform-design.md))는 실시간 차단 장치가 아니다.

## 6. 세션 전에 정할 것(사용자 결정 — 권장안 포함)
> **시각 정정(2026-10-15 15:24 KST — `TZ=KST-9 date`):** 이 문서와 [kafka-removal-design.md](kafka-removal-design.md) §6 "세션 방식 변경"의 결정 시각은 처음에 UTC를 KST로 잘못 적었다(Git Bash가 `TZ=Asia/Seoul`을 인식하지 못해 UTC를 냈다). 9시간을 더해 실제 KST로 고쳤다 — 예: D1~D3 결정 "2026-10-14 23:31" → 2026-10-15 08:31, D3 수정 "05:41" → 14:41, 철거 정책 "05:54" → 14:54, 세션 방식 변경 "06:12" → 15:12. 근거: #418 머지 시각 14:58 +0900, 정정 기준 `TZ=KST-9 date`는 시스템 `date`와 같은 값을 낸다.
**결정(2026-10-15 08:31 KST — `date`로 찍은 시각, 사용자):** D1 = 2회, D2 = 최악 가정 유지, D3 = 권장안(① B₀ 최댓값 임시 기준 + ② B₁ 대비 악화 없음 + ③ 정합성·예매 오류 0 — 허용 폭과 "B₁이 ①을 넘을 때"의 처리는 B′ 사전 등록에서 — 뒤 처리는 아래 D3 수정으로 대체). **D3 수정(2026-10-15 14:41 KST — `date`, 사용자):** ①은 고정한다 — B₁·B′가 넘으면 기준을 옮기지 않고 "미달"로 기록한다. ④ 노드-시간 절감을 독립 판정으로 넣는다(아래 표). D4는 권장안대로 B′ 결과를 본 뒤 G1 시험 전에 정한다. D5는 세션이 실제로 나뉠 때 정한다(권장안은 "다시 잰다").

| # | 정할 것 | 권장안 | 이유 |
|---|---|---|---|
| D1 | B₁ 반복 횟수 | 2회 | B₀과 같은 횟수라 조건 차이 외의 비교 틀이 같다. 통계적 반복이 아니라는 한계는 B₀과 같다 |
| D2 | 폭증 부하 모델 | 지금의 `retryAfterMs` 무시 최악 가정(고정 도착률)을 유지, 지시를 따르는 폴링은 이번에 넣지 않는다 | B₀·B₁·B′의 비교 틀을 유지한다. 폴링 모델은 별도 과제(운영 요구사항 §5 "미정") |
| D3 | B′ 판정식(**확정** — 네 판정을 따로 적는다) | ① **B₀ 임시 목표(고정)**: 예매 p95 ②(hold·주문·결제) ≤ B₀ 최댓값(hold 272·주문 260·결제 224ms), 폭증 상태 조회 p95 < 523ms. B₁이나 B′가 넘어도 **기준을 옮기지 않고 "B₀ 목표 미달"로 기록**한다(B₀은 Kafka가 있던 세션의 역사적 참고값 — 넘은 이유를 Kafka 제거로 귀속하지 않는다) ② **B₁ 대비 악화 없음**: 같은 지표에서 B′ ≤ B₁(같은 세션) 최댓값 × (1 + 허용 폭) — 허용 폭은 B₁의 두 run 차이를 보고 B′ 사전 등록(첫 B′ run 전)에서 정한다 ③ 정합성 위반 0·예매 오류 0, DB 예산 안(하네스) ④ **노드-시간 절감(독립 판정)**: 같은 부하 조건에서 B′ run의 노드-시간이 B₁보다 줄었는가, 그리고 절감률이 목표 이상인가 — 절감률 목표는 B₁ 뒤 B′ 전에 정하고, 같은 사전 등록에서 run 집계 방법(B₁ 두 run의 평균·최솟값 또는 가장 보수적인 쌍)과 적분 구간 정의도 고정한다(B₀ 두 run만 해도 노드-시간 3.17·3.45 — 계산). 시작 노드 수·노드 수 변화·시작 조건을 함께 적는다. 결과는 판정별로 따로 적는다(예: "① 미달, ② 통과, ③ 통과, ④ 절감 X%(목표 미달)") — ①을 못 넘었다고 ②·④를 숨기지 않고, ④가 좋다고 ①·② 미달을 덮지 않는다 | [operating-requirements.md](operating-requirements.md) §5 지연 목표("B 실측을 임시 기준")와 목표 문장(노드 비용 개선). ①을 고정하는 이유는 결과를 본 뒤 기준을 옮기면 사전 등록의 의미가 없어지기 때문이다. ②는 B′의 목적이 B₁ 대비 자원 최적화이기 때문이다([kafka-removal-design.md](kafka-removal-design.md) §7.5). ④의 노드-시간은 실측 노드 수를 적분한 계산값이고(달러 환산도 계산), 청구액 절감과 같지 않다(§5.1) |
| D4 | 가용성 G1 허용 기준 | 장애 동안 Deployment마다 Ready ≥ 1 유지(최소 조건) + 오류율·지연·복구 시간 상한은 B′ 결과를 본 뒤 G1 시험 전에 정한다. 장애 대상은 api 파드가 있는 노드 1회·대기열 파드가 있는 노드 1회(관측) | 운영 요구사항 §5가 "장애 직후 허용할 오류율·지연·복구 시간은 따로 정한다"고 했다. 10만/10초 처리량 유지를 가정하지 않는다 |
| D5 | 세션이 나뉠 때 B₁ 재측정 | 다시 잰다(1회 이상) | B′는 같은 세션 비교가 원칙이다([kafka-removal-design.md](kafka-removal-design.md) §7.5) |
- 장애 주입 방법(후보): 노드의 EC2 인스턴스 종료(`aws ec2 terminate-instances`) — 비자발적 장애에 가장 가깝다. ASG가 대체 노드를 띄우고 CA도 반응한다 — 둘의 동작을 함께 기록한다. 드레인(`kubectl drain`)은 자발적 중단이라 G1 시험이 아니다.

## 6.1 결과(측정 세션 `20261015-nok` — [[IMP-034]])
- 결과 요약: P1(대기열 풀 3) 통과 → 최종 B′, P3(대기열 requests 650m + hostname 분산) 판정 ② 실패(p3-2 결제 p95 339 > 319ms)로 되돌림(#424), P2는 사전 등록 §1.1대로 돌리지 않음. ④ 노드-시간 목표(B₁ 평균의 90%)는 어느 후보도 미달(계산). 가용성 G1(D4 = 최소 조건 + 상한 — 사용자 결정)은 P1에서 통과(api 노드 상실 54초·대기열 노드 상실 82초 복구, 5xx ≤ 0.30%).
- 아래 §7의 "hostname topologySpread에서 대기열 0개 노드가 도메인으로 세어지는가"는 P3 두 run에서 관측했다: 대기열이 최대 6개일 때도 노드당 1개였고 "0개 노드가 있는데 2개 노드" 시점이 없었다(실측 — `app_pod_nodes`). 세어지는 쪽과 맞는다(추론).
- §2.3 시험 전 계산에서 web 2개가 한 노드에 있는 배치를 찾았다(web에 분산 규칙 없음 — 사실). G1 시험은 승인된 2회(api·대기열 노드)만 했고, 이 위험은 후보를 고치지 않고 보고했다(§2.3 "남지 않으면 후보를 고친다"와 다른 선택 — 후보 P1이 web과 무관해서, `prereg-g1.md` §5).

## 7. 확인하지 못한 것과 확인 장소
| 항목 | 장소 |
|---|---|
| Kafka 제거 뒤 노드별 flowticket 밖 요청 | 2026-10-20 확인 — 기존 세션 원자료로 계산(§1 표, [optimization-targets.md](optimization-targets.md) §2) |
| Strimzi Pod의 실제 requests(Kafka가 차지하던 몫) | 재지 않는다 — Kafka를 설치하지 않는다(2026-10-15). 매니페스트 기준 계산값만([kafka-removal-design.md](kafka-removal-design.md) §7.1) |
| 노드 allocatable 메모리, `node` 레이블 질의가 값을 내는지 | 2026-10-20 확인 — 6.94GiB, `node_allocatable`·`node_cpu_requests`가 노드 레이블로 값을 냈다(세션 20261015-nok·20261017-b100k 원자료) |
| 대기열 파드의 Hikari active·pending(풀 축소 가능 여부) | B₁ |
| 예매 경로 CPU-ms/req(분리 뒤 api 1,100m 재산정 근거) | B₁ |
| `jvm_compilation_time_ms_total` 존재 여부 | 세션 첫 run 뒤 |
| hostname topologySpread(maxSkew 1·`DoNotSchedule`)에서 대기열 파드가 0개인 노드가 도메인으로 세어지는지, 그래서 노드당 2개가 실제로 생기는지(P3) | P3 적용 run의 배치 기록(`app_pod_nodes`) |
| 축소 뒤 같은 Deployment 파드가 한 노드에 모이는지(P3 + topologySpread의 G1 위험) | P3 적용 run에서 폭증 뒤 최소 파드 수로 줄어든 시점의 배치(`app_pod_nodes`) |
| 평시 A′와 평시 B 2+2의 실제 노드 수 비교(운영 요구사항 §5 재검토 조건) | B₁의 노드별 기록으로 계산 → 확정판 |
| 운영 프로파일(t3.large, max 2/AZ)에서의 배치·allocatable — 측정은 m6i.large로만 한다 | 확정판에서(위 3 AZ 재평가와 함께). t3.large allocatable은 [kafka-removal-design.md](kafka-removal-design.md) §8의 미확인 항목 |
| 3 AZ·NAT·RDS Multi-AZ 재평가 | [kafka-removal-design.md](kafka-removal-design.md) §7.4가 자원 산정으로 넘긴 과제다. 비용과 가용성(G1·G2) 결과가 있어야 판단할 수 있어 이 계획(측정 전)에서는 다루지 않고, B′·가용성 G1 결과 뒤 이 문서의 확정판에서 다룬다 |
