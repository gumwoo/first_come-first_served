# 측정 세션 runbook — 세션 한정 변경과 진단 절차

[loadtest-100k-plan](loadtest-100k-plan.md)과 [ADR-023](../decisions/ADR-023-burst-entry-and-waiting-two-axes.md) 실행 순서를
따라 측정할 때, **운영 매니페스트(Git)를 바꾸지 않고 세션 동안만 바꾸는 것**과 그 원복 명령을 한곳에 둔다.
원칙: 바꾸면 그 시각·명령을 `artifacts/loadtest/<session>/session.log`에 남기고, 세션이 끝나기 전에 원복한다.

## 0. 전제 — ArgoCD 자동 동기화

세션 한정 변경(아래 1~5)은 ArgoCD가 Git 상태로 되돌리지 않게 **자동 동기화를 끈 상태**에서만 유지된다.

| 동작 | 명령 |
|---|---|
| 끄기 | `kubectl patch application flowticket -n argocd --type merge -p '{"spec":{"syncPolicy":{"automated":null}}}'` |
| 수동 동기화(머지된 PR 반영) | `kubectl patch application flowticket -n argocd --type merge -p '{"operation":{"initiatedBy":{"username":"loadtest-session"},"sync":{"revision":"main","prune":true}}}'` |
| 켜기(세션 끝) | `kubectl apply -f k8s/argocd/application.yaml` |

- 끈 동안에는 **머지된 PR이 클러스터에 반영되지 않는다.** PR을 머지한 뒤 "반영" 단계는 수동 동기화 → `status.sync.revision`이 머지 커밋인지,
  배포의 이미지 태그(`kube_pod_container_info` 또는 `kubectl get deploy -o jsonpath`)가 기대값인지 확인하는 데까지다.
- 수동 동기화는 아래 세션 한정 변경(HPA 고정, Ingress 진단 경로 등)을 **Git 상태로 되돌린다.** 동기화 뒤 필요한 변경을 다시 건다.
- 시연(측정 세션 20261005-1440): 켜기 → 약 1.5분 뒤 `Synced`·`Healthy`(api·web을 Git의 이미지 태그로 롤아웃, 롤아웃 중 `Degraded` 표시 1회) → 다시 끄기.

## 1. 파드 수 고정(HPA)

| 동작 | 명령 |
|---|---|
| 고정 | `kubectl patch hpa flowticket-api -n flowticket --type merge -p '{"spec":{"minReplicas":N,"maxReplicas":N}}'` (web도 같은 방식) |
| 원복 | 수동 동기화(위 0) — 매니페스트 값(api 3–7, web 2–4)으로 돌아간다 |

## 2. 노드 수 고정(Cluster Autoscaler·노드 그룹)

| 동작 | 명령 |
|---|---|
| 노드 늘리기 | `aws eks update-nodegroup-config --cluster-name flowticket --nodegroup-name flowticket-ap-northeast-2X --scaling-config minSize=1,maxSize=3,desiredSize=K` |
| 축소 막기 | `kubectl annotate node <노드> cluster-autoscaler.kubernetes.io/scale-down-disabled=true --overwrite` |
| 원복 | 표시 제거 `kubectl annotate node <노드> cluster-autoscaler.kubernetes.io/scale-down-disabled-`, desiredSize를 원래 값으로 |

- 리셋에서 파드를 다시 만들면 메모리·CPU 부족으로 Pending이 생겨 CA가 노드를 바꿀 수 있다(측정 세션 20261005-1440에서 반복).
  run마다 노드 수와 앱 파드 배치를 기록한다 — `export-prom.mjs`가 `app_pod_nodes`·`app_images`를 남긴다.

## 3. 진단 경로(web 경유 vs api 직접) — `infra/loadgen/diag/diag.sh`

| 동작 | 명령 |
|---|---|
| 만들기 | `infra/loadgen/diag/diag.sh nlb-up` → `api-direct=http://…`, `web-proxy=http://…/api` |
| 확인 | 발생기에서 `curl http://<api-direct>/actuator/health/liveness`, `curl http://<web-proxy>/events` 가 200 |
| 지우기 | `infra/loadgen/diag/diag.sh nlb-down` |

- 두 경로 모두 내부 NLB·평문 HTTP·파드 IP 타깃이라 차이는 web(Next rewrite) 홉뿐이다. 원본은 발생기 서브넷(퍼블릭 /24 셋)으로만 연다.
- 공개 ALB 경로(TLS 종단)와 다르므로 **진단 run**이다. 같은 세션에 "ALB → web" 연결 칸을 하나 넣어 NLB 결과가 ALB 결과와 같은 영역인지 확인한 뒤 해석한다.
- api 직접 경로에서는 `/actuator/prometheus`(permitAll)도 발생기에 열린다 — 세션 끝에 반드시 지운다.
- 주소가 나온 뒤에도 연결까지 수 분 걸린다(시연: 발생기에서 15초 간격 17번째 시도에 둘 다 200). 로드 밸런서 컨트롤러가 만든 프런트 SG는
  80번을 발생기 /24 셋에만 연다. 시연에서 로컬(VPC 밖) 접근은 000(내부 NLB라 사설 IP로만 풀린다).
- 이 Service들은 Git(ArgoCD 추적 대상)에 없어서 자동 동기화·prune으로 지워지지 않는다(시연에서 확인) — 반드시 `nlb-down`으로 지운다.

## 4. JFR(api CPU 프로파일) — `infra/loadgen/diag/diag.sh`

| 동작 | 명령 |
|---|---|
| 켜기 | `infra/loadgen/diag/diag.sh jfr-on <초>` (롤아웃 — 파드가 새로 뜬다) |
| 회수 | `infra/loadgen/diag/diag.sh jfr-collect <run 디렉터리>/jfr` (녹화 시간이 끝난 뒤) |
| 끄기 | `infra/loadgen/diag/diag.sh jfr-off` (롤아웃) |

- `JAVA_OPTS`가 아니라 `JDK_JAVA_OPTIONS`로 넣는다 — 이미지의 `JAVA_OPTS`(`-XX:MaxRAMPercentage=75`)를 덮지 않는다. `jfr-on`이 파드마다
  힙 설정(cmdline)과 녹화 시작(로그 `Started recording`)을 보여 준다. `JDK_JAVA_OPTIONS`는 런처가 환경변수에서 읽으므로 cmdline에는 안 나온다.
- 녹화 시간은 **파드 기동 시각부터** 센다. `jfr-collect`는 기동 + duration이 지나지 않은 파드를 건너뛰고(빈·덜 쓴 파일 방지), 빈 파일이면 실패로 낸다.
  분석은 로컬 JDK 17의 `jfr summary`·`jfr print --events jdk.ExecutionSample`(이미지 런타임이 17).
- Windows Git Bash에서는 MSYS가 인자 안의 `/tmp/…`를 Windows 경로로 바꾼다 — `diag.sh`가 `MSYS_NO_PATHCONV=1`로 막는다. 이 스크립트 밖에서
  kubectl에 컨테이너 경로를 넘길 때도 같은 처리가 필요하다(바뀐 경로로 JVM이 녹화 파일을 못 만들어 새 파드가 CrashLoopBackOff — 롤링이라 기존 파드는 남는다).
- 시연(측정 세션 20261005-1440): 3파드 `MaxRAMPercentage=75` 유지·녹화 시작, 회수 3개(4.5~4.8MB, 90초), `jfr summary` 성공(`jdk.ExecutionSample` 589~698건),
  `jfr-off` 뒤 원래 ReplicaSet으로 복귀.
- JFR run은 **판정 run과 분리**한다(관측 오버헤드). 지표(CPU-ms/req, p95)를 비교하는 판정 run에는 JFR을 켜지 않는다.
- 켜고 끌 때 롤아웃이 일어나 파드가 차갑게 뜬다 — 같은 rate 사전 run 1회(워밍업, 판정 제외)를 다시 건다.

## 5. Ingress 진단 경로(ALB 고정 응답) — 발생기 상한 측정용

| 동작 | 명령 |
|---|---|
| 추가 | `kubectl patch ingress flowticket -n flowticket --type json -p '[{"op":"add","path":"/metadata/annotations/alb.ingress.kubernetes.io~1actions.loadgen-probe","value":"{\"type\":\"fixed-response\",\"fixedResponseConfig\":{\"contentType\":\"application/json\",\"statusCode\":\"200\",\"messageBody\":\"{\\\"data\\\":{\\\"status\\\":\\\"WAITING\\\",\\\"token\\\":\\\"probe\\\"}}\"}}"},{"op":"add","path":"/spec/rules/0/http/paths/0","value":{"path":"/__loadgen-probe","pathType":"Prefix","backend":{"service":{"name":"loadgen-probe","port":{"name":"use-annotation"}}}}}]'` |
| 제거 | 위 두 항목을 `remove`, 또는 수동 동기화 |

- 1b(발생기 상한 측정)에서 쓴 경로. 위 명령은 `--dry-run=server`로 검증했다(경로 `/__loadgen-probe`가 `/` 앞에 들어감).
- ALB가 백엔드 없이 응답하므로 앱 부하가 0이다. ALB 쪽 수는 `RequestCount`가 아니라 `HTTP_Fixed_Response_Count`에 잡힌다.

## 6. run 절차 규칙(결정 칸)

- **리셋**: 쓴 공연마다 대기열 키(`wait`·`admitcount`·`admitexp`·`seq`)를 한 번의 `DEL`로 지우고 활성 목록에서 뺀다. SSE 없는 run 사이에는 파드를
  다시 만들지 않는다(배치·워밍업이 바뀐다). 활성 공연이 남아 있으면 시작하지 않는다.
- **워밍업**: 각 칸에서 같은 rate 사전 run 1회(결과 제외) → 판정 run 3회. 경로가 여럿이면 첫 run 순서를 교차한다.
- **판정식 사전 등록**: run 전에 판정식과 임계치를 session.log에 적는다(결과를 본 뒤 정하지 않는다).
- **발생기**: 여러 대면 `run-entry.sh --gens G --gen $GEN --start-at <UTC>`(START_AT은 2분 이상 뒤), 집계는 `entry-arrivals.mjs --t0`.
- **사후 검사**: `export-prom.mjs --end`는 run 종료 + 45초 이상, `check-correctness.sh`로 판정.

## 7. 세션 끝 체크리스트

- [ ] `diag.sh nlb-down`, `diag.sh jfr-off`
- [ ] Ingress 진단 경로 제거
- [ ] 노드 표시 제거·desiredSize 원복, 수동 동기화로 HPA 원복
- [ ] ArgoCD 자동 동기화 켜기(또는 철거)
- [ ] 늘린 발생기 줄이기(Terraform `-var loadgen_instance_count=…`, plan에서 삭제 대상 확인)
