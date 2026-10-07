# 측정 세션 runbook — 세션 한정 변경과 진단 절차

[loadtest-100k-plan](loadtest-100k-plan.md)과 [ADR-023](../decisions/ADR-023-burst-entry-and-waiting-two-axes.md) 실행 순서를
따라 측정할 때, **운영 매니페스트(Git)를 바꾸지 않고 세션 동안만 바꾸는 것**과 그 원복 명령을 한곳에 둔다.
원칙: 바꾸면 그 시각·명령을 `artifacts/loadtest/<session>/session.log`에 남기고, 세션이 끝나기 전에 원복한다.

## 0. 전제 — ArgoCD 자동 동기화

세션 한정 변경(아래 1~5)은 ArgoCD가 Git 상태로 되돌릴 수 있으므로 **자동 동기화를 끈 상태**에서만 건다.

| 동작 | 명령 |
|---|---|
| 끄기 | `kubectl patch application flowticket -n argocd --type merge -p '{"spec":{"syncPolicy":{"automated":null}}}'` |
| 수동 동기화(머지된 PR 반영) | `kubectl patch application flowticket -n argocd --type merge -p '{"operation":{"initiatedBy":{"username":"loadtest-session"},"sync":{"revision":"main","prune":true}}}'` |
| 켜기(세션 끝) | `kubectl apply -f k8s/argocd/application.yaml` |

- 끈 동안에는 **머지된 PR이 클러스터에 반영되지 않는다.** PR을 머지한 뒤 "반영" 단계는 수동 동기화 → `status.sync.revision`이 머지 커밋인지,
  배포의 이미지 태그(`kube_pod_container_info` 또는 `kubectl get deploy -o jsonpath`)가 기대값인지 확인하는 데까지다.
- 수동 동기화는 Git에 있는 필드를 Git 값으로 되돌린다 — HPA min/max는 동기화로 복원되는 것을 확인했다(측정 세션 20261005-1440, 여러 번).
  동기화 뒤 필요한 변경을 다시 건다. 단, 이 Application은 `ServerSideApply=true`라 **Git에 없는 필드**(kubectl이 더한 env 항목·어노테이션)는
  동기화로 지워지지 않을 수 있다 — kubectl로 더한 Ingress 어노테이션(ALB access log)은 자동 동기화를 다시 켜 Synced가 된 뒤에도 남아 있는 것을
  조회로 확인했다(측정 세션 20261005-1440 끝, `session.log`의 "보충 기록" 줄). 그래서 JFR env·Ingress 어노테이션·결제 mock은 동기화에 기대지 않고 아래 각 절의 명시 명령으로 원복한다.
- 시연(측정 세션 20261005-1440): 켜기 → 약 1.5분 뒤 `Synced`·`Healthy`(api·web을 Git의 이미지 태그로 롤아웃, 롤아웃 중 `Degraded` 표시 1회) → 다시 끄기.

## 1. 파드 수 고정(HPA)

| 동작 | 명령 |
|---|---|
| 고정 | `kubectl patch hpa flowticket-api -n flowticket --type merge -p '{"spec":{"minReplicas":N,"maxReplicas":N}}'` (web도 같은 방식) |
| 원복 | 수동 동기화(위 0) — 매니페스트 값(api 3–9 — ADR-025, web 2–4)으로 돌아간다 |

## 2. 노드 수 고정(Cluster Autoscaler·노드 그룹)

| 동작 | 명령 |
|---|---|
| 노드 늘리기 | `aws eks update-nodegroup-config --cluster-name flowticket --nodegroup-name flowticket-ap-northeast-2X --scaling-config minSize=1,maxSize=3,desiredSize=K` |
| 축소 막기 | `kubectl annotate node <노드> cluster-autoscaler.kubernetes.io/scale-down-disabled=true --overwrite` |
| 원복 | 표시 제거 `kubectl annotate node <노드> cluster-autoscaler.kubernetes.io/scale-down-disabled-`, desiredSize를 원래 값으로 |

### 2b. 오픈 전 사전 확장 → 오픈 뒤 정책에 넘기기(ADR-025)

| 동작 | 명령 |
|---|---|
| 사전 확장(오픈 전, 노드 Ready까지 CA 반응 시간 이상 앞서) | `kubectl patch hpa flowticket-api -n flowticket --type merge -p '{"spec":{"minReplicas":9}}'` + 노드 그룹 3개 `--scaling-config minSize=3,maxSize=3,desiredSize=3` |
| 정책에 넘기기(오픈 뒤) | `kubectl patch hpa flowticket-api -n flowticket --type merge -p '{"spec":{"minReplicas":3}}'` + 노드 그룹 3개 `minSize=1,maxSize=3`(desiredSize는 그대로 — 이후는 CA가 줄인다) |

- api 요청이 1000m라 api 하나가 노드 하나를 차지한다 — HPA 9는 노드 9다. 넘긴 뒤 HPA 축소는 안정화 300초, CA 노드 축소는 그 뒤 약 10분(IMP-029 시연 관찰 11.6분).
- 자동 동기화를 끈 상태에서 한다(위 0). 넘긴 뒤 HPA 값이 Git 값(3~9)과 같은지 확인한다.

- 리셋에서 파드를 다시 만들면 메모리·CPU 부족으로 Pending이 생겨 CA가 노드를 바꿀 수 있다(측정 세션 20261005-1440에서 반복).
  run마다 노드 수와 앱 파드 배치를 기록한다 — `export-prom.mjs`가 `app_pod_nodes`·`app_images`를 남긴다.

## 3. 진단 경로(web 경유 vs api 직접) — `infra/loadgen/diag/diag.sh`

| 동작 | 명령 |
|---|---|
| 만들기 | `infra/loadgen/diag/diag.sh nlb-up` → `api-direct=http://…`, `web-proxy=http://…/api` |
| 확인 | 발생기에서 `curl http://<api-direct>/livez`, `curl http://<web-proxy>/events` 가 200 |
| 지우기 | `infra/loadgen/diag/diag.sh nlb-down` |

- 두 경로 모두 내부 NLB·평문 HTTP·파드 IP 타깃이라 차이는 web(Next rewrite) 홉뿐이다. 원본은 발생기 서브넷(퍼블릭 /24 셋)으로만 연다.
- 공개 ALB 경로(TLS 종단)와 다르므로 **진단 run**이다. 같은 세션에 "ALB → web" 연결 칸을 하나 넣어 NLB 결과가 ALB 결과와 같은 영역인지 확인한 뒤 해석한다.
- api 직접 경로는 api 메인 포트(8080)의 permitAll 경로를 발생기에 연다 — 세션 끝에 반드시 지운다. actuator는 관리 포트(8081)로 분리돼
  이 경로로 닿지 않는다(TS-041 — 그 전에는 공개 ALB의 `/api/actuator/prometheus`로도 200이 났다).
- 주소가 나온 뒤에도 연결까지 수 분 걸린다(시연: 발생기에서 15초 간격 폴링 — 1차 17번째, 최종 스크립트 재시연 7번째 시도(`nlb-up` 후 약 2.5분)에 둘 다 200). 로드 밸런서 컨트롤러가 만든 프런트 SG는
  80번을 발생기 /24 셋에만 연다. 시연에서 로컬(VPC 밖) 접근은 000(내부 NLB라 사설 IP로만 풀린다).
- 이 Service들은 Git(ArgoCD 추적 대상)에 없어서 자동 동기화·prune으로 지워지지 않는다(시연에서 확인) — 반드시 `nlb-down`으로 지운다.

## 4. JFR(api CPU 프로파일) — `infra/loadgen/diag/diag.sh`

| 동작 | 명령 |
|---|---|
| 켜기 | `infra/loadgen/diag/diag.sh jfr-on <초> [지연초]` (롤아웃 — 파드가 새로 뜬다) |
| 회수 | `infra/loadgen/diag/diag.sh jfr-collect <run 디렉터리>/jfr` (녹화 시간이 끝난 뒤) |
| 끄기 | `infra/loadgen/diag/diag.sh jfr-off` (롤아웃) — 동기화가 아니라 이 명령으로 원복한다(위 0의 SSA 주의) |

- `JAVA_OPTS`가 아니라 `JDK_JAVA_OPTIONS`로 넣는다 — 이미지의 `JAVA_OPTS`(`-XX:MaxRAMPercentage=75`)를 덮지 않는다. 런처는 이를 명령줄 **앞**에 붙인다
  (회수한 JFR의 `jvmArguments`로 확인) — 같은 옵션이 겹치면 뒤의 `JAVA_OPTS`가 우선이다. `jfr-on`이 파드마다 힙 설정(cmdline)과 JFR 로그(`jfr,startup`)를
  보여 준다. `JDK_JAVA_OPTIONS`는 런처가 환경변수에서 읽으므로 cmdline에는 안 나온다.
- 녹화는 **JVM 기동 + 지연초**부터 `<초>` 동안이다. 지연이 0이면 부팅·워밍업이 섞인다 — 측정 run 구간만 보려면 지연을 `rollout 완료 → run 시작` 사이 시간에 맞춘다
  (JDK 17의 `jfr summary`·`jfr print`에는 시간 구간 필터가 없다). `0`초 녹화는 JFR에서 "끝없이"라 거부한다.
- `jfr-collect`는 api 컨테이너 기동 + 지연 + duration(+10초)이 지나지 않은 파드를 건너뛰고(빈·덜 쓴 파일 방지), 빈 파일은 지우고 실패(종료 코드 1)로 낸다.
  종료 중인 이전 파드는 대상에서 뺀다. 분석은 로컬 JDK 17의 `jfr summary`·`jfr print --events jdk.ExecutionSample`(이미지 런타임이 17).
- Windows Git Bash에서는 MSYS가 인자 안의 `/tmp/…`를 Windows 경로로 바꾼다 — `diag.sh`가 `MSYS_NO_PATHCONV=1`로 막는다. 이 스크립트 밖에서
  kubectl에 컨테이너 경로를 넘길 때도 같은 처리가 필요하다(바뀐 경로로 JVM이 녹화 파일을 못 만들어 새 파드가 CrashLoopBackOff — 롤링이라 기존 파드는 남는다).
  반대로 변환을 끄면 로컬 절대 경로(`/c/Users/…`)도 그대로 넘어가 kubectl이 못 찾거나 `C:\c\…`에 쓴다 — 그래서 매니페스트는 stdin, 회수는 상대 경로로 넘긴다.
- 시연(측정 세션 20261005-1440): 3파드 `MaxRAMPercentage=75` 유지·녹화 시작, 회수 3개(4.5~4.8MB, 90초), `jfr summary` 성공(`jdk.ExecutionSample` 589~698건),
  `jfr-off` 뒤 원래 ReplicaSet으로 복귀. 최종 스크립트 `jfr-on 60 40`: 로그 `Recording 1 scheduled to start in 40 s`, 녹화 중 `jfr-collect`는 3파드 건너뜀(종료 1·파일 0),
  끝난 뒤 회수한 파일의 Start = 컨테이너 기동 + 40~41초, Duration 60초. 유휴 60초의 `jdk.ExecutionSample`은 38~45건 — 지연 없이 부팅을 포함한 위 589~698건과 비교하면
  지연 없는 녹화는 부팅이 대부분을 차지한다(부하 없는 상태의 관측).
- JFR run은 **판정 run과 분리**한다(관측 오버헤드). 지표(CPU-ms/req, p95)를 비교하는 판정 run에는 JFR을 켜지 않는다.
- 켜고 끌 때 롤아웃이 일어나 파드가 차갑게 뜬다 — 같은 rate 사전 run 1회(워밍업, 판정 제외)를 다시 건다.

## 5. Ingress 진단 경로(ALB 고정 응답) — 발생기 상한 측정용

| 동작 | 명령 |
|---|---|
| 추가 | `kubectl patch ingress flowticket -n flowticket --type json -p '[{"op":"add","path":"/metadata/annotations/alb.ingress.kubernetes.io~1actions.loadgen-probe","value":"{\"type\":\"fixed-response\",\"fixedResponseConfig\":{\"contentType\":\"application/json\",\"statusCode\":\"200\",\"messageBody\":\"{\\\"data\\\":{\\\"status\\\":\\\"WAITING\\\",\\\"token\\\":\\\"probe\\\"}}\"}}"},{"op":"add","path":"/spec/rules/0/http/paths/0","value":{"path":"/__loadgen-probe","pathType":"Prefix","backend":{"service":{"name":"loadgen-probe","port":{"name":"use-annotation"}}}}}]'` |
| 제거 | 위 두 항목을 `remove`(`--type json -p '[{"op":"remove","path":"/spec/rules/0/http/paths/0"},{"op":"remove","path":"/metadata/annotations/alb.ingress.kubernetes.io~1actions.loadgen-probe"}]'`) — 동기화에 기대지 않는다(위 0의 SSA 주의) |

- 1b(발생기 상한 측정)에서 쓴 경로. 위 명령은 `--dry-run=server`로 검증했다(경로 `/__loadgen-probe`가 `/` 앞에 들어감).
- ALB가 백엔드 없이 응답하므로 앱 부하가 0이다. ALB 쪽 수는 `RequestCount`가 아니라 `HTTP_Fixed_Response_Count`에 잡힌다.

## 5b. 결제 게이트웨이 mock — Downstream E2E(입장자) 시험용

| 동작 | 명령 |
|---|---|
| 켜기 | `infra/loadgen/diag/diag.sh pay-mock-on` — ConfigMap `PAYMENT_GATEWAY=mock` + api 롤아웃, 파드마다 환경변수 확인. 자동 동기화가 켜져 있으면 거부한다 |
| 확인 | `infra/loadgen/diag/diag.sh pay-status` |
| 끄기 | `infra/loadgen/diag/diag.sh pay-mock-off` — Git 값 `toss`로 되돌리고 롤아웃. 그 뒤 자동 동기화를 켠다(위 0) |

- 이유: `TossPaymentGateway.approve()`는 서버 단독 승인을 막고(`VALIDATION_ERROR`) `confirm()`은 결제창에서 받은 `paymentKey`가 필요해,
  k6 서버 부하로는 결제를 결정론적으로 만들 수 없다. `MockPaymentGateway`는 멱등 키가 `FAIL`로 시작하면 거절, 아니면 `MOCK-<키>`로 승인한다.
- 켜 둔 동안 공개 사이트의 결제도 mock으로 처리된다. 세션 동안만 켜고, 판정 SQL(`approved_not_mock`)로 mock이 실제로 걸렸는지 확인한다.

## 6. run 절차 규칙(결정 칸)

- **리셋**: 쓴 공연마다 대기열 키(`wait`·`admitcount`·`admitexp`·`seq`)를 한 번의 `DEL`로 지우고 활성 목록에서 뺀다. SSE 없는 run 사이에는 파드를
  다시 만들지 않는다(배치·워밍업이 바뀐다). 활성 공연이 남아 있으면 시작하지 않는다.
- **워밍업**: 각 칸에서 같은 rate 사전 run 1회(결과 제외) → 판정 run 3회. 경로가 여럿이면 첫 run 순서를 교차한다.
- **판정식 사전 등록**: run 전에 판정식과 임계치를 session.log에 적는다(결과를 본 뒤 정하지 않는다).
- **발생기**: 여러 대면 `run-entry.sh --gens G --gen $GEN --start-at <UTC>`(START_AT은 2분 이상 뒤), 집계는 `entry-arrivals.mjs --t0`.
  burst(수천/s 이상)는 `--warm-seconds 30`으로 T0 전에 VU 연결을 미리 맺는다 — 끄면 T0에 VU 수만큼 새 연결이 한꺼번에 열려
  실제 송신이 늦어진다(10,000/s에서 첫 1초 약 2천 건). `entry-arrivals.mjs`의 `entryConnectionWait.over100ms`가 0에 가까운지 확인한다
  (`window`는 iteration 시작 시각이라 연결 대기를 보지 못한다).
- **도착 창**: N명이 진입 시간 안에 모두 도착해야 하면 `--rate <발생기 한 대의 초당 도착>`을 몫 ÷ 진입 시간보다 조금 높게 준다
  (예: 3대 × 33,334명·10초 → `--rate 3350`). 사용자 기준 결과는 `entry-arrivals.mjs`의 `usersNotArrived`(0이어야 함).
- **대기자**: 대기 상태까지 재려면 `run-entry.sh --poll-hold <초>` — 대기 토큰마다 프론트와 같은 규칙으로 상태를 묻는다(`poll-<gen>/`).
  입장 인지 지연은 `scripts/loadtest/admit-latency.mjs --audit <api 로그> <run>/poll-*/tokens.jsonl`.
- **사후 검사**: `export-prom.mjs --end`는 run 종료 + 45초 이상, `check-correctness.sh`로 판정.
- **Downstream E2E(입장자)**: 새 공연(좌석 전부 AVAILABLE·주문 없음)마다 대기열 키를 리셋하고 발생기 한 대에서
  `run-booking.sh --session <s> --run <r> --gen g1 --base https://flow-ticket.com/api --event <id> --users <토큰> --start-at <UTC>`
  (사용자 100명이 T0 20초 전에 진입 → ADMITTED → T0에 함께 예매. 역할은 `infra/k6/booking-e2e.js` 머리말). 판정은 hold·주문 만료 회수가 끝난 뒤
  (T0 + 300초 + 60초 + 여유) `check-booking.sh --out <run> --event <id>`(역할별 기대값 — T0 + 360초 전이면 판정 불가로 멈춘다, 발생기 CPU 1초 최대 ≥ 80%면 무효)와
  `check-correctness.sh --generators 1`(기존 위반 검사). 진입 시험과 같이 run 동안 `watch-correctness.mjs`를 돌리고, 끝난 뒤 `loadgen.sh pull` →
  `export-prom.mjs`(run 시작 2분 전 ~ 종료 + 60초)를 먼저 한다. 시작 전에 `diag.sh pay-status`로 모든 api 파드가 mock인지 확인한다.

## 7. 세션 끝 체크리스트

- [ ] `diag.sh nlb-down`, `diag.sh jfr-off`, `diag.sh pay-mock-off`(결제 mock을 켰다면)
- [ ] Ingress 진단 경로 제거
- [ ] 노드 표시 제거·desiredSize 원복, 수동 동기화로 HPA 원복
- [ ] ArgoCD 자동 동기화 켜기
- [ ] 늘린 발생기 줄이기(Terraform `-var loadgen_instance_count=…`, plan에서 삭제 대상 확인)
