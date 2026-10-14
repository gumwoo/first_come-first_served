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
| 노드 allocatable 메모리 | **미확인** — 세션에서 `kubectl get node -o jsonpath='{..allocatable}'`(아래 §4 `node_allocatable`) | — |
| api | requests 1,100m / 1,536Mi(limit 2Gi), HPA 3~9(CPU 60%), PDB minAvailable 2, maxSurge 1·maxUnavailable 0, AZ 분산 `ScheduleAnyway` | `k8s/base/api-*.yaml` |
| 대기열(B, `k8s/components/queue-split`) | api와 같은 사양, HPA 2~6, PDB minAvailable 1. component가 api HPA 상한을 3으로 낮춘다(api 3 고정) | `k8s/components/queue-split/*` |
| web | requests 300m / 256Mi, HPA 2~4, PDB minAvailable 1, 낮은 우선순위 | `k8s/base/web-*.yaml` |
| Hikari 풀 | 파드당 5(`DB_POOL_MAX` 기본값). 대기열 Deployment는 따로 덮어쓰지 않는다 | `application.yml:20`, `queue-deployment.yaml` |
| DB 커넥션 예산 | db.t4g.micro `max_connections` 79(2026-08-10 pg_settings 실측) − reserved 3 − 앱 외 여유 20 = **56** | 하네스 backend 규칙 17(`harness/backend/check.mjs`) |
| 사전 확장 | `event-prescale.yaml`이 api HPA 하한을 올린다(평소 3). B와 같이 켜면 api 하한 9 > 상한 3이 되어 하네스 k8s 규칙 11이 막는다 — **B용 사전 확장은 없다** | [[ADR-025]], [operating-requirements.md](operating-requirements.md) §5 |
| 노드별 flowticket 밖 CPU 요청 | A' 평시 410·600·750m(세션 20261007-e2e, 레포 밖 실측 — Kafka 네임스페이스 몫이 들어 있었을 것으로 본다, 추론) | [[ADR-025]] 계산 근거 |
| Kafka 제거 뒤 노드별 flowticket 밖 요청 | **미확인** — §6.2 삭제 전후 기록에서 처음 잰다 | [kafka-removal-design.md](kafka-removal-design.md) §6.2 |
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
- 대기열 풀을 줄여도 되는지는 **대기열 파드의 Hikari active·pending을 재야 안다**(미측정 — [operating-requirements.md](operating-requirements.md) §4). 근거 후보(사실): 진입 경로의 DB 커넥션 획득은 진입 요청당 0.012~0.021(판매 상태 캐시 TTL 1초, [[IMP-033]] §8 — 세션 조회, 원시 응답 미보존). 상태 조회 경로는 DB를 쓰지 않는다고 본다(추론 — B₁에서 Hikari active로 확인). 대기열 파드에서도 스케줄러(아웃박스 릴레이 등)가 돌며 DB를 쓴다(사실: 같은 이미지이고 스케줄러는 `flowticket.scheduling.enabled`(기본 true) 하나로 켜지는데 대기열 Deployment는 이 값을 덮어쓰지 않는다 — [workload-responsibilities.md](workload-responsibilities.md)).
- api 풀(5)은 이 계획에서 줄이지 않는다 — 예매 경로가 DB를 쓰고, 1,800 rps 파드당 active 5·pending 116(초기 구성, [[TS-034]])의 기록이 있다.

### 2.2 노드 자리(CPU requests 기준)
- api·대기열 1,100m는 노드당 하나만 들어간다(2 × 1,100 > 1,930 — 계산). 그래서 api 계열 파드 수 = 최소 노드 수다.
| 구성 | api 계열 파드(계산) | CPU requests 하한 노드 수(계산) | 노드 그룹 최대 9 대비(계산) |
|---|---|---|---|
| 현재 B 평시(api 3 + 대기열 2) | 5 | 5 | 남는 자리 4 |
| 현재 B 대기열 상한(api 3 + 대기열 6) | 9 | 9 | 0 — 이 상태에서 롤링 surge(+1,100m)는 들어갈 노드가 없다([[ADR-025]] 결과·대가와 같은 구조) |
| 2+2 출발 후보(api 2 + 대기열 2) | 4 | 4 | 남는 자리 5 |
- 하한은 실제 대수가 아니다. web 2개(300m)와 flowticket 밖 요청이 api 옆 남은 자리(1,930 − 1,100 − 밖 요청)에 들어가지 못하면 노드가 더 붙는다. B₀은 5·6대에서 안정됐다([[IMP-033]] 실측).
- **대기열 requests를 줄여 노드당 2개를 넣으려면(계산):** `2 × r + 밖 요청 ≤ 1,930`. 밖 요청이 Kafka 제거 전 값(410~750m)이면 r ≤ 590~760m. Kafka 제거 뒤 밖 요청은 미확인이라 **r의 후보는 B₁에서 노드별 요청을 잰 뒤 정한다**.
- requests를 줄이면 HPA 기준도 바뀐다 — CPU 60% 목표는 requests 기준이라 r이 작아지면 같은 부하에서 더 일찍, 더 많이 확장한다. 그 파드 수가 §2.1 DB 예산과 §2.2 노드 상한 안에 들어야 한다.

### 2.3 가용성 G1 — 노드 1대가 갑자기 사라져도 핵심 경로가 계속 도는가
- 지금은 **1,100m라서** api 계열 파드가 서로 다른 노드에 놓인다(자원 때문에 생긴 성질이지 규칙이 아니다). requests를 줄이면 **같은 Deployment의 파드 둘이 한 노드에 놓일 수 있고, 그 노드를 잃으면 둘 다 사라진다** — 대기열 2개가 한 노드에 있으면 새 진입이 멈춘다(G1 위반). 그래서 requests를 줄이는 후보는 **노드 단위 분산 규칙(hostname `topologySpreadConstraints` 또는 anti-affinity)과 한 쌍**이다([[ADR-026]] B1, 별도 브랜치 `perf/api-node-spread`의 결정과 연결).
- PDB: api `minAvailable 2`는 api 2개 구성에서 드레인을 막는다(비자발적 장애에는 PDB가 작동하지 않지만, 노드 작업·CA 축소가 막힌다). 2+2 후보는 api PDB를 1로(또는 `maxUnavailable 1`) 함께 바꾼다([operating-requirements.md](operating-requirements.md) §5 단서).
- **시험 전 계산(사전 등록에 넣는다):** 최종 B′ 후보의 실제 배치(`kubectl get pod -o wide`)와 노드별 남은 자리(`node_allocatable` − `node_cpu_requests`·`node_memory_requests`, §4)로, 노드마다 "이 노드를 잃으면 그 위의 파드가 남은 노드에 들어가는가"를 표로 만든다. 들어가지 않으면 CA 증설(실측 110초, [[IMP-021]]) + 파드 기동·Ready + 워밍업이 끝날 때까지 남은 복제본만으로 버텨야 한다 — 그 시간 동안 Deployment마다 Ready 1개 이상이 남는지가 G1의 최소 조건이다. 남지 않으면 시험 전에 후보를 고친다.

## 3. B′ 후보 — 무엇을 바꿔 보나(수치는 B₁ 뒤 사전 등록 때)
각 후보는 독립 변수 하나씩 바꾸는 것을 원칙으로 하고, 한 PR(→ CI·하네스 → G1 리뷰 → 머지 → ArgoCD 동기화·이미지/설정 확인)으로 적용한다. k8s만 바뀌는 조정은 이미지를 다시 빌드하지 않는다(`image.yml` 변경 판별에 `k8s/`가 없음 — [[ADR-025]]).
| 후보 | 바꾸는 것 | 근거 | 먼저 필요한 측정·작업 | 제약 |
|---|---|---|---|---|
| **P1 대기열 풀 축소** | 대기열 Deployment에 `DB_POOL_MAX` env(예: 2~3) | DB 예산이 대기열 상한을 묶고 있다(§2.1) | B₁의 대기열 파드 Hikari active·pending 최댓값 | 하네스 규칙 17은 이미 대기열 `DB_POOL_MAX`를 읽는다(`check.mjs` B 분기) |
| **P2 대기열 상한 조정** | 대기열 HPA `maxReplicas` | B₀에서 상한 6 도달·폭증 상태 조회 p95 259·523ms | P1(풀) — 상한을 올리면 DB 예산을 넘는다 | 노드 그룹 최대 9(§2.2), vCPU 쿼터 32(발생기 3 × 4 포함 — [[ADR-025]]) |
| **P3 대기열 requests 축소 + 노드 단위 분산** | 대기열 `requests.cpu`(r), hostname 분산 규칙 | 노드당 2개 → 같은 노드 수에서 대기열 처리 자리 증가([[IMP-033]] §9) | B₁의 노드별 밖 요청·대기열 파드 CPU 사용(`app_cpu_by_pod`)·상태 조회 처리량 | §2.2 r 범위, §2.3 분산 규칙, HPA 기준 변화 |
| **P4 대기열 축소 정책** | 대기열 HPA `behavior.scaleDown`(안정화 창 등) | b1r에서 폭증 직전 축소(3→2)가 폭증과 겹쳤다([[IMP-033]] §4 — 1회 관측, 원인 귀속은 추론) | — | 오픈이 아닌 시간의 비용과 맞바꿈 |
| **P5 평시 2+2** | api HPA 하한 2·PDB 1, 대기열 2 | G1 출발 후보([operating-requirements.md](operating-requirements.md) §5) | B₁의 api 파드 예매 경로 CPU·지연(api 3개가 예매를 전부 받는다 — [[IMP-033]] §6) | §2.3 PDB, 남은 1개가 평시 부하를 감당하는지(G1) |
| **P6 B용 사전 확장(설계만)** | 오픈 전 대기열 하한 상향(api는 고정) | L3(예정된 오픈 뒤 추가 폭증)와 평시 B 상시 + 사전 확장 결정 | 하네스 k8s 규칙 11을 component 기준으로 바꾸는 작업(+ 위반 fixture) | ADR-025 사전 확장과 동시에 켤 수 없다 |
- 순서 제안(추론 — 효과가 큰 쪽과 의존 관계): P1 → P2 → (P3 또는 P4) → P5. P6은 B′ 측정 대상이 아니라 운영 정책 설계로 따로 진행한다.
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
1. **세션 전(저장소)**: P3a(`SKIP_KAFKA`·표식 가드) 머지, 이 문서·질의 추가 머지, B₁ 사전 등록 초안(B₀ v2 사전 등록을 바탕으로 조건 차이만 바꾼다), B′ 후보 PR 초안(머지하지 않음).
2. **bring-up(기본 — Kafka 설치)** → **N 게이트**(§6.1).
3. **Kafka 운영 삭제 게이트**(§6.2) — 삭제 전 기록(Strimzi Pod 실제 requests, 노드별 requests 합, kafka PV·EBS), 삭제, 표식 생성·가드 확인, 삭제 후 기록(노드 수·노드별 requests — CA가 안정된 뒤).
4. **B₁** — 기존 B 설정 그대로(§1 표), B₀ v2와 같은 부하 시나리오·워밍업·시작 조건(최소 파드 Ready·HPA 최소·CA `NoCandidates`·`NoActivity` 3분 유지)·판정 정의. 조건 차이(세션 날짜·시간대, AMI·배치, 시작 노드 수, 이미지 N, Kafka 없음, 발생기 위치)를 결과에 함께 적는다. **B₀ 대비 차이를 Kafka 제거의 효과로 쓰지 않는다**(기준선 (a)).
5. **B′ 사전 등록**(B₁ 결과를 본 뒤, 첫 B′ run 전) → 후보마다 PR → 반영 확인 → run. 판정식·반복 횟수는 사전 등록에 고정한다.
6. **가용성 G1 시험** — 최종 B′ 후보에서. §2.3 계산을 먼저 하고, 허용 기준(아래 §6 D4)을 사전 등록한 뒤 시험한다.
7. 관찰 기간(§6.3) 항목은 4~6 내내 본다: 아웃박스 PENDING·DEAD·발행 지연, 구독자 0 발행(`outbox-no-receivers.mjs`), 결제 E2E의 `order.paid` 수신.
- 정합성 판정은 B₀과 같은 `check-correctness.sh`·check-booking이다. Kafka 제거로 3단계가 "구독자 부재"로 바뀌었다(#412) — B₀의 "이벤트 유실"과 판정 대상이 다르다는 것을 조건 차이에 적는다.
- **한 세션 안에 끝난다고 전제하지 않는다.** 세션이 나뉘면 B′와의 같은 세션 비교를 위해 B₁을 다시 잴지 사전 등록 때 정한다(D5).

## 6. 세션 전에 정할 것(사용자 결정 — 권장안 포함)
| # | 정할 것 | 권장안 | 이유 |
|---|---|---|---|
| D1 | B₁ 반복 횟수 | 2회 | B₀과 같은 횟수라 조건 차이 외의 비교 틀이 같다. 통계적 반복이 아니라는 한계는 B₀과 같다 |
| D2 | 폭증 부하 모델 | 지금의 `retryAfterMs` 무시 최악 가정(고정 도착률)을 유지, 지시를 따르는 폴링은 이번에 넣지 않는다 | B₀·B₁·B′의 비교 틀을 유지한다. 폴링 모델은 별도 과제(운영 요구사항 §5 "미정") |
| D3 | B′ 1차 판정식 | 예매 p95 ②(hold·주문·결제) ≤ B₀ 최댓값(hold 272·주문 260·결제 224ms), 폭증 상태 조회 p95 < 523ms, 정합성 위반 0·예매 오류 0, DB 예산 안(하네스) — 노드-시간은 2차 관찰 | [operating-requirements.md](operating-requirements.md) §5 지연 목표("B 실측을 임시 기준") — 어느 값을 쓸지는 그 문서가 사전 등록으로 미뤘다 |
| D4 | 가용성 G1 허용 기준 | 장애 동안 Deployment마다 Ready ≥ 1 유지(최소 조건) + 오류율·지연·복구 시간 상한은 B′ 결과를 본 뒤 G1 시험 전에 정한다. 장애 대상은 api 파드가 있는 노드 1회·대기열 파드가 있는 노드 1회(관측) | 운영 요구사항 §5가 "장애 직후 허용할 오류율·지연·복구 시간은 따로 정한다"고 했다. 10만/10초 처리량 유지를 가정하지 않는다 |
| D5 | 세션이 나뉠 때 B₁ 재측정 | 다시 잰다(1회 이상) | B′는 같은 세션 비교가 원칙이다([kafka-removal-design.md](kafka-removal-design.md) §7.5) |
- 장애 주입 방법(후보): 노드의 EC2 인스턴스 종료(`aws ec2 terminate-instances`) — 비자발적 장애에 가장 가깝다. ASG가 대체 노드를 띄우고 CA도 반응한다 — 둘의 동작을 함께 기록한다. 드레인(`kubectl drain`)은 자발적 중단이라 G1 시험이 아니다.

## 7. 확인하지 못한 것과 확인 장소
| 항목 | 장소 |
|---|---|
| Kafka 제거 뒤 노드별 flowticket 밖 요청, Strimzi Pod의 실제 requests | 세션 §6.2 삭제 전후 기록 |
| 노드 allocatable 메모리, `node` 레이블 질의가 값을 내는지 | 세션 bring-up 직후 |
| 대기열 파드의 Hikari active·pending(풀 축소 가능 여부) | B₁ |
| 예매 경로 CPU-ms/req(분리 뒤 api 1,100m 재산정 근거) | B₁ |
| `jvm_compilation_time_ms_total` 존재 여부 | 세션 첫 run 뒤 |
| 운영 프로파일(t3.large, max 2/AZ)에서의 배치·allocatable — 측정은 m6i.large로만 한다 | 확정판에서(위 3 AZ 재평가와 함께). t3.large allocatable은 [kafka-removal-design.md](kafka-removal-design.md) §8의 미확인 항목 |
| 3 AZ·NAT·RDS Multi-AZ 재평가 | [kafka-removal-design.md](kafka-removal-design.md) §7.4가 자원 산정으로 넘긴 과제다. 비용과 가용성(G1·G2) 결과가 있어야 판단할 수 있어 이 계획(측정 전)에서는 다루지 않고, B′·가용성 G1 결과 뒤 이 문서의 확정판에서 다룬다 |
