# TS-022 · `RespectIgnoreDifferences=true`를 넣었는데 sync가 replicas를 덮어씀 — sync를 건 `operation`에 옵션이 없었다 (ArgoCD v3.5.0, kind 재현)

- 슬라이스: S09(배포/운영)
- 날짜: 2026-08-10(사건), 2026-11-05(원인 재현)
- 유형: 배포 도구 사용 방식(설정) — **"spec에 넣은 옵션이 그 sync에 적용되지 않았다"**
- 관련: [[TS-021]](HPA 드리프트가 이 작업의 동기), `k8s/argocd/application.yaml`,
  `k8s/base/api-deployment.yaml`, `harness/k8s/check.mjs`
- 관련 PR: #204(도입) → #205(이 사건의 수정) → #206(자동화 활성화)
- 상태: **해결** — 필드를 매니페스트에서 제거하는 방식으로 전환, 실측으로 확인 후 자동 동기화 활성화.
  원인은 당시 `operation` 기록과 코드, §9의 재현으로 판단했다.

## 1. 배경 — 왜 ArgoCD를 붙였나

성능 재측정을 하면서 클러스터에 손으로 넣은 변경이 쌓였다. `kubectl set image`(구버전 롤백),
`kubectl patch hpa`(min=max=3 고정), `kubectl set env`(Flyway 우회). 이 중 **HPA 고정은 며칠간
그대로 남아 있었다**([[TS-021]] §6-1). Git의 매니페스트와 클러스터가 어긋난 채로 돌아갔고,
아무도 알려주지 않았다.

코드 계약은 하네스가 검사하는데 **클러스터 상태를 검사하는 주체가 없었다.** 그 자리를 채우려고
ArgoCD를 도입했다(#204).

## 2. 증상 — sync 한 번에 스케일아웃이 취소된다

ArgoCD가 Deployment를 관리하면 `spec.replicas`의 소유권이 문제가 된다. Git에 `replicas: 3`이
있고 HPA가 9로 늘려놓은 상태에서 sync가 돌면, ArgoCD가 Git의 3을 적용해 **부하가 걸린 상태에서
파드를 6개 죽인다.**

`selfHeal`을 켤 예정이었으므로 이 경로는 사람 손 없이 자동으로 도는 경로가 된다.

## 3. 첫 번째 시도 — 그리고 리뷰가 짚어준 보완

처음에는 `ignoreDifferences`만 넣었다.

```yaml
ignoreDifferences:
  - group: apps
    kind: Deployment
    jsonPointers: [/spec/replicas]
```

외부 리뷰가 이게 부족하다고 지적했다 — `ignoreDifferences`는 기본적으로 diff 계산에만 쓰이고,
sync에서 그 필드를 무시하려면 `RespectIgnoreDifferences=true`가 필요하다는 것이다. 맞는 지적이라
판단해 반영했다(#204).

```yaml
syncOptions:
  - RespectIgnoreDifferences=true
```

**여기까지가 "고쳤다"고 믿은 지점이다. 실제로 돌려보기 전이었다.**

## 4. 조사 — 실제로 돌려보니 안 막혔다

머지 후 Application을 적용하고 확인했다.

### 4-1. 첫 관측은 판정 불가였다

`flowticket-api`를 5로 스케일하고 sync를 걸었더니 5 → 4 → 3으로 내려갔다. 그런데 HPA 이벤트를
보니 이런 게 있었다.

```
Normal  SuccessfulRescale  60s (x2 over 5h54m)  horizontal-pod-autoscaler  New size: 4; reason: All metrics below target
```

**5→4는 HPA였다.** 어디까지가 HPA이고 어디부터 sync인지 가를 수 없으므로 이 관측은 근거로 쓰지
않았다. (여기서 "ArgoCD가 되돌렸다"고 단정했으면 틀린 원인을 잡을 뻔했다.)

### 4-2. HPA를 제거한 통제 실험

`flowticket-web`은 HPA가 없다. Git에 `replicas: 2`이고 스케일 결정 주체가 ArgoCD뿐이다.
sync는 두 행 모두 아래 `operation`을 `kubectl patch`로 넣어 걸었다.

```json
{"operation":{"initiatedBy":{"username":"manual-bootstrap"},"sync":{"prune":false,"syncStrategy":{"apply":{}}}}}
```

| Application spec의 `syncOptions` | sync 전 | sync 후 | 판정 |
|---|---|---|---|
| `ServerSideApply` + `RespectIgnoreDifferences` | 4 | **2** | 막지 못함 |
| `RespectIgnoreDifferences`만 (SSA 제거) | 4 | **2** | 막지 못함 |

위 `operation`에는 `syncOptions`가 없어 두 행의 실효 조건은 같았다. 따라서 이 표는 "ServerSideApply와의
상호작용" 가설을 판별하지 못한다(§9).

### 4-3. 소유권으로 교차 확인

`managedFields`를 보면 누가 그 필드를 적용했는지 남는다.

```
kubectl-client-side-apply    Update  -                                    
argocd-controller            Update  -       2026-08-10T12:06:49Z   <== spec.replicas 소유
kube-controller-manager      Update  status  2026-08-10T12:06:57Z   <== spec.replicas 소유
```

`argocd-controller`가 `spec.replicas`를 소유하고 있었다. 즉 ArgoCD가 그 필드를 실제로 apply했다.
이 출력은 `flowticket-api`의 것으로, §4-2 전 spec에 `ServerSideApply=true`가 있던 상태의 sync(12:06:49) 결과다.
그런데도 `managedFields`의 operation이 `Apply`가 아니라 `Update`인 것은 server-side apply가 적용되지 않았다는 신호다(§9). `kube-controller-manager`
항목은 subresource가 `status`이므로 spec이 아니라 `status.replicas`가 표시된 것으로 본다(추론).

### 4-4. 부분적으로는 동작했다

replicas가 4인데도 Application은 `Synced`로 표시됐다. **diff 단계의 무시는 정상 동작한다.**
`ignoreDifferences`는 Application spec에서 읽으므로 OutOfSync 표시는 없어졌지만, sync 단계의
옵션은 이 `operation`에 들어 있지 않았다.

이 사건에서 가장 위험한 성질이 이것이다 — **UI는 초록인데 실제로는 덮어쓰고 있었다.** 표시가
정상이므로 사람이 눈치챌 계기가 없다. 부하 시험 중이었다면 파드가 줄어든 것을 HPA의 판단으로
읽었을 가능성이 높다.

환경: ArgoCD v3.5.0 (Helm chart `argo/argo-cd`), EKS 1.3x, t3.large × 3.

## 5. 근본 원인

**직접 원인.** sync 실행 단계(`controller/sync.go`)는 `RespectIgnoreDifferences`와 `ServerSideApply`를
Application spec이 아니라 `operation.sync.syncOptions`로 판단한다. diff 계산처럼 spec의 옵션을 읽는 곳은 따로
있다. spec의 옵션을 `operation`으로 복사하는 것은 API 서버와 자동 sync이고, `kubectl patch`로 `operation`을
직접 넣으면 이 복사를 거치지 않는다. 그래서 spec에 두 옵션이 있어도 §4의 sync에는 적용되지 않은 것으로 판단한다.
근거는 §4-2의 `operation`, §4-3의 소유 방식, §9의 코드 위치와 재현 결과다.

더 깊은 원인은 따로 있다. **`replicas`를 Git에 둔 것 자체가 잘못이었다.** HPA가 붙는 순간 그 값은
실효를 잃고 "HPA가 뜨기 전 한 번만 의미 있는 값"이 된다. 그런 값을 매니페스트에 남겨두고 도구
옵션으로 무시하게 만드는 구조는, 옵션이 적용되지 않는 경로에서 그대로 무너진다.

## 6. 해결 — 필드를 없앤다

```yaml
spec:
  # replicas를 의도적으로 두지 않는다. 이 Deployment는 HPA가 소유한다(min 3 / max 9).
  selector:
```

desired state에 `replicas`가 없으면 apply가 그 필드를 건드리지 않는다(client-side apply로 §6-2에서 확인). 잃는 것도 없다 —
하한은 HPA `minReplicas: 3`이 정한다.

- `RespectIgnoreDifferences=true`는 **제거**했다. Git에 필드가 없으면 이 옵션에 기댈 일이 없다.
  옵션에 기대면 §9처럼 sync 경로에 따라 결과가 달라진다. 필드를 뺀 뒤 값이 유지되는 것은 §6-2의 sync와 §6-3 B의
  125초 관측으로 확인했고, §9에는 필드를 뺀 조건이 없다.
- `ignoreDifferences`는 **남기되 `name: flowticket-api`로 좁혔다.** Git에 `replicas`가 없으니
  클러스터의 실제 값이 늘 "추가된 필드"로 잡혀 diff 노이즈가 되는데, 그걸 죽이는 용도다.
  `web`은 HPA가 없어 Git이 replicas를 소유해야 하고, 거기서 드리프트가 나면 알아야 한다.

### 6-1. 전환 비용 — 첫 sync에서 파드가 잠깐 줄었다

이전 apply의 `last-applied-configuration`에 `replicas: 3`이 남아 있어, 필드가 사라지면 prune돼
k8s 기본값 1로 내려간다. 예측한 리스크였고 실제로 관측됐다.

```
t= 3s  spec.replicas=3   ready=1
t= 6s  spec.replicas=1   ready=1    <- last-applied에서 제거 → 기본값 1
t= 9s  spec.replicas=3   ready=1    <- HPA가 minReplicas로 복구
t=27s  spec.replicas=3   ready=3    <- 완전 회복
```

`spec.replicas`가 1이었던 구간은 약 3~6초, ready 파드 회복까지 약 27초. **필드를 뺄 때 한 번만
발생하는 비용**이다 — 이후에는 last-applied에 replicas가 없어 반복되지 않는다.

### 6-2. 수정 후 검증

`replicas: 6`으로 스케일한 상태에서 sync를 걸었다.

| 항목 | 값 |
|---|---|
| sync 전 `spec.replicas` | 6 (Git에는 필드 없음) |
| sync 실행 | `12:39:20Z → 12:39:22Z`, revision `e32a1c0e` |
| sync 후 36초 (3초 간격 12회) | 전부 **6** |
| Deployment 결과 메시지 | `deployment.apps/flowticket-api unchanged` |

`phase=Succeeded`만으로는 직전 sync의 잔상과 구분되지 않아 `startedAt`/`finishedAt`으로 대조했다.
`managedFields`에서도 `argocd-controller`가 더 이상 `spec.replicas`를 소유하지 않는다.
이 sync도 옵션 없는 `operation`으로 걸었고, 결과(`unchanged`, `Update`)로 보아 client-side apply였다.
필드를 뺀 상태의 server-side apply는 이 문서에서 따로 시험하지 않았다. 위 출력의 `kube-controller-manager` 표시는
§4-3과 같이 `status.replicas`로 본다(추론).

```
kubectl.exe               Update  scale   <== spec.replicas 소유 (수동 스케일 = HPA 자리)
argocd-controller         Update  -           ← 소유하지 않음
kube-controller-manager   Update  status  <== spec.replicas 소유
```

### 6-3. 자동 동기화 활성화 후 실증 (#206)

`automated: {selfHeal: true, prune: true}`를 켠 뒤 드리프트를 세 종류 넣어봤다.

| 실험 | 드리프트 | 결과 | 복구까지 |
|---|---|---|---|
| A (양성) | `web` replicas 2 → 4 | 복구됨 | **11초** |
| B (음성 대조군) | `api` replicas 3 → 6 (HPA 소유) | **되돌아오지 않음** (125초 관측) | — |
| C (양성) | `COOKIE_SECURE` true → false | 복구됨 | **2초** |

B가 이 문서의 핵심이다. A·C만 보면 "ArgoCD가 다 되돌린다"로 읽히지만, 실제로 원한 것은
**되돌릴 것과 놔둘 것을 구분하는 것**이다. selfHeal 반응 시간이 2~11초인데 125초를 기다려도
변하지 않았으므로 "아직 안 왔다"가 아니다.

실험 C의 ArgoCD 이벤트:

```
Initiated automated sync to '87324089...'
Updated sync status: Synced -> OutOfSync
Partial sync operation ... succeeded
Updated sync status: OutOfSync -> Synced
```

## 7. 재발 방지 — 하네스 규칙 ⑧

이 유형은 apply 전에 무증상이고, 되돌아온 뒤에도 "HPA가 줄인 것"과 구분되지 않는다([[TS-021]]의
수동 패치 드리프트와 같은 계열). 그래서 정적으로 못박았다.

`harness/k8s/check.mjs`가 HPA의 `scaleTargetRef`를 모아 그 이름의 Deployment에 `spec.replicas`가
있으면 실패시킨다. HPA가 없는 Deployment(web)는 대상이 아니다 — 그쪽은 Git이 소유해야 맞다.

위반 fixture `harness/fixtures/violations/k8s-hpa-replicas/`(Deployment + 그를 가리키는 HPA)를
만들어 `harness/meta-test.mjs`에 등록했다. 규칙이 실제로 잡는지는 메타테스트가 판정한다.

```
✓ meta: k8s-hpa-replicas → 하네스가 정상적으로 실패 감지
```

## 8. 교훈

**설정이 저장됐는지와 그 실행에 적용됐는지는 별개다.** 리뷰의 진단과 처방은 맞았다. 옵션은 spec에
들어갔지만 시험에 쓴 `operation`에는 없었다. sync 결과의 `status.operationState.operation.sync.syncOptions`를
봤다면 바로 알 수 있었다. 도구 동작을 "버그"나 "무효"로 결론 내리기 전에, 시험 자체가 그 옵션을 실행했는지
확인해야 한다.

**관측이 섞이면 판정하지 말고 실험을 다시 설계한다.** 5→4→3에서 5→4는 HPA였다. 그대로 결론을
냈으면 원인을 잘못 잡았을 것이다. HPA가 없는 `web`으로 옮긴 것이 사건 해결의 분기점이었다.

**"UI가 초록"은 안전의 증거가 아니다.** `ignoreDifferences`는 OutOfSync 표시만 없앴고, 뒤에서는
덮어쓰고 있었다. 상태 표시와 실제 동작이 다를 수 있다는 것을 `managedFields`로 확인했다.

**음성 대조군이 있어야 주장이 성립한다.** "self-heal이 드리프트를 복구한다"는 A·C로 보이지만,
그것만으로는 "HPA 결정까지 덮어쓰지는 않는다"를 말할 수 없다. B가 있어야 두 문장이 동시에 참이 된다.

**도구 옵션으로 우회하기 전에 값이 거기 있어야 하는지 묻는다.** `replicas`는 HPA가 붙는 순간
Git에서 의미를 잃는 값이었다. 처음부터 없었다면 이 사건 자체가 없었다.

## 9. 원인 재현 (2026-11-05)

§4의 시험을 sync 방식별로 나눠 kind에서 다시 쟀다.

**코드(v3.5.0).**

| 위치 | 동작 |
|---|---|
| `controller/sync.go:117,239,326` | `state.Operation.Sync`의 옵션으로 RespectIgnoreDifferences와 ServerSideApply를 판단한다 |
| `server/application/application.go:2123-2133` | API sync는 spec 옵션을 쓰고, 요청에 옵션이 있으면 그 값으로 대체한다 |
| `controller/appcontroller.go:2325` | 자동 sync는 spec 옵션을 `operation`에 넣는다 |

**실험.** ArgoCD v3.5.0(공식 매니페스트), kind v0.33.0(Kubernetes v1.37.0), 대상은
`argocd-example-apps`의 guestbook(HPA 없음, Git `replicas: 1`, 커밋 `8088f4c`)이다. run마다 Application과
네임스페이스를 새로 만들어 옵션 없는 `operation`으로 첫 배포를 한 뒤, `replicas`를 4로 올리고 sync했다.
`ignoreDifferences`는 §3과 같다. 각 조건의 예상 결과를 실행 전에 적어 두었고, 조건당 3회, 총 33회 모두
예상과 같았다.

| 조건 | sync 방식 | `operation`의 옵션 | sync 후 replicas | Deployment 결과 | replicas 소유자(`argocd-controller`) |
|---|---|---|---|---|---|
| spec RID+SSA (§4-2 1행) | patch, 옵션 없음 | 없음 | **1** | `configured` | Update |
| spec RID (§4-2 2행) | patch, 옵션 없음 | 없음 | **1** | `configured` | Update |
| spec 없음 (음성 대조) | patch, 옵션 없음 | 없음 | **1** | `configured` | Update |
| spec RID+SSA | patch, 옵션 포함 | RID, SSA | **4** | `serverside-applied` | Apply |
| spec RID | patch, 옵션 포함 | RID | **4** | `configured` | 소유 안 함 |
| spec 없음 | patch, 옵션 포함 | RID | **4** | `configured` | 소유 안 함 |
| spec RID+SSA, 다른 필드 드리프트 | patch, 옵션 포함 | RID, SSA | **4** | `serverside-applied` | Apply |
| spec RID, 다른 필드 드리프트 | patch, 옵션 포함 | RID | **4** | `configured` | 소유 안 함 |
| spec RID+SSA | API, 옵션 미지정 | RID, SSA | **4** | `serverside-applied` | Apply |
| spec RID+SSA | API, 다른 옵션 지정 | `Validate=true` | **1** | `configured` | Update |
| spec RID+SSA, 다른 필드 드리프트 | 자동 sync | RID, SSA | **4** | `serverside-applied` | Apply |

(RID = `RespectIgnoreDifferences=true`, SSA = `ServerSideApply=true`)

- 같은 `ignoreDifferences` 아래에서 replicas 유지 여부는 spec의 `syncOptions`가 아니라 `operation`의 RID 유무로
  갈렸다. RID가 있는 조건끼리 비교하면 SSA 유무는 결과 메시지와 소유 방식만 바꿨다.
- `Apply` 행은 `argocd-controller`가 replicas를 소유하면서도 값이 4로 유지된다. RID가 live 값을 target에 복사해
  apply하기 때문이다(`controller/sync.go:236-238`). 소유 자체가 덮어쓰기를 뜻하지는 않는다.
- 드리프트 조건에서 `revisionHistoryLimit`은 5에서 Git 값 3으로 돌아오고 `replicas`만 유지됐다. sync가 실제로
  적용된 상태에서 무시가 동작했다는 대조다.
- 범위: §4와 다른 클러스터·대상에서의 재현이고, HPA가 붙은 Deployment로는 시험하지 않았다. 실험 중 같은 Docker VM의
  부하로 추정되는 etcd 지연으로 13개 run이 결과 없이 끊겨 다시 돌렸으며, 33회에는 완료된 run만 들어 있다.
- 스크립트: [`ts022-repro.mjs`](../assets/ts022-repro.mjs), 원본 결과: [`ts022-repro-results.jsonl`](../assets/ts022-repro-results.jsonl), 실행 환경: [`ts022-repro-meta.json`](../assets/ts022-repro-meta.json)
