# TS-019 · HPA를 선언했는데 스케일 판단을 못 한다 — metrics-server 없음

- 슬라이스: S09(EKS 배포)
- 날짜: 2026-08-08
- 유형: 배포 환경 결함(누락) — **조용히 동작하지 않음**
- 관련: `k8s/base/api-hpa.yaml`, `docs/deployment/aws-eks-deploy-plan.md` Phase 6
- 상태: **해결** — metrics-server 설치 후 스케일업까지 확인(§4)

## 1. 증상

`kubectl top`이 실패해서 알게 됐다.

```
$ kubectl top pods -n flowticket
error: Metrics API not available

$ kubectl get hpa -n flowticket
NAME             REFERENCE                   TARGETS              MINPODS  MAXPODS  REPLICAS
flowticket-api   Deployment/flowticket-api   cpu: <unknown>/60%   3        9        3
```

**`TARGETS`가 `<unknown>`이다.** HPA 객체는 생성됐고 상태도 정상으로 보이지만,
**CPU를 읽지 못해 스케일 판단 자체를 하지 않는다.**

## 2. 원인

HPA는 `metrics.k8s.io` API로 파드 CPU를 읽는데, 그 API를 제공하는 **metrics-server가
클러스터에 없다.** EKS는 metrics-server를 기본 설치하지 않는다 —
`kubectl top`도 같은 API를 쓰므로 함께 실패한다.

`k8s/base/api-hpa.yaml`은 `type: Resource / name: cpu`를 쓰므로 metrics-server가 전제인데,
**그 전제를 어디에도 적어두지 않았고 설치도 하지 않았다.**

## 3. 이 결함이 위험한 이유

**아무것도 실패하지 않는다.**

- `kubectl apply` 성공
- `kubectl get hpa` → 정상적인 행이 출력됨
- Pod는 잘 뜨고 서비스도 정상

**부하가 몰려야 비로소 "왜 안 늘지?"를 알게 된다.** 그리고 그 시점은 대개 늘어야 할 때다.
[[TS-017]](OAuth 콜백)·[[TS-018]](ALB timeout)과 같은 유형 —
**에러가 아니라 조용히 다른 상태**여서 늦게 발견된다.

## 4. 해결 — 설치하고 **발동까지** 확인했다 (2026-08-08)

```bash
helm install metrics-server metrics-server/metrics-server -n kube-system
```

`TARGETS`가 바뀌는 것까지는 "읽는다"일 뿐이라, **부하를 줘서 실제로 늘어나는지**까지 봤다.

```
설치 전   cpu: <unknown>/60%      FailedGetResourceMetric 461회
설치 후   cpu: 11%/60%            kubectl top도 값 반환(api 14~48m)

부하 20워커(좌석 조회) 투입 →
  Normal  SuccessfulRescale  New size: 4
  reason: cpu resource utilization (percentage of request) above target

파드 3 → 4 (새 파드 생성 확인), TARGETS cpu 24%/60%
```

`behavior`도 설계대로 동작했다 — 확장 `stabilizationWindow: 0`이라 **25초 만에 반응**했고,
축소 300초라 부하를 끊은 뒤에도 4개를 유지했다(진동 방지).

### 확인하지 않은 것
- **최대치(9)까지 밀어보지 않았다.** 부하가 4개분이었다.
- **새 파드가 뜬 뒤 응답이 개선됐는지는 측정하지 않았다.** "늘었다"까지이고
  "그래서 빨라졌다"는 S10 부하테스트의 몫이다.
- 파드별 CPU 편차가 컸다(8m~107m). ALB 라운드로빈인데 왜 쏠렸는지는 보지 않았다.

## 5. 재발 방지 — 아직 안 함

**매니페스트가 클러스터 애드온을 전제하는데 그것이 문서에도 코드에도 없었다.**
같은 유형이 또 있을 수 있다(예: EBS CSI가 없으면 PVC가 Pending — 이번엔 Terraform이 설치했다).

하네스로 잡기는 어렵다 — 클러스터 상태를 정적으로 알 수 없기 때문이다.
대신 **`k8s/README.md`에 "이 매니페스트가 전제하는 클러스터 애드온" 목록**을 두는 편이 현실적이다.
아직 하지 않았다.

## 6. 교훈

**"선언했다"와 "동작한다"는 다르다.** HPA·PDB·probe처럼 **선언만으로는 검증되지 않는 것들**은
실제로 발동시켜 봐야 확인된다. 이 프로젝트에서 발동 확인 현황:
- ~~HPA 스케일업~~ → **§4에서 확인**(3→4)
- **PDB** — 노드 드레인을 해본 적 없음
- **브로커 페일오버** — 브로커를 죽여본 적 없음

## 재발 (2026-08-11) — "고쳤다"와 "재현 가능하게 고쳤다"는 다르다

클러스터를 destroy 후 재생성했더니 **같은 문제가 그대로 돌아왔다.** 원인은 단순하다 —
당시 metrics-server를 `helm install`로 손수 깔고 이 문서를 "해결"로 닫았는데, **그 설치가
IaC에도 매니페스트에도 없었다.** 저장소를 `metrics-server`로 검색하면 TS 문서 세 개만 나왔다.

### 이번엔 증상이 다르게 나타났다

TS-019 당시에는 HPA의 `TARGETS`가 `<unknown>`인 것으로 알아챘다. 이번에는 **ArgoCD가 먼저
알려줬다** — 앱 전체가 `Synced/Degraded`인데 `status.resources`의 리소스 9개는 전부 health가
비어 있어 어느 것이 문제인지 안 보였다.

```
HPA TARGETS:  cpu: <unknown>/60%
HPA conditions: AbleToScale=True, ScalingActive=False (reason=FailedGetResourceMetric)
```

ArgoCD의 HPA health check가 `ScalingActive=False`를 Degraded로 판정하고, 그것이 앱 집계로
올라간 것이다. 하드 리프레시와 application-controller 재시작으로도 사라지지 않은 것이 단서였다
— 캐시 잔상이라면 그때 지워졌어야 한다.

⚠️ 처음에 이것을 "초기 기동 시 파드가 한 번 죽은 잔상으로 **추측**된다"고 말했는데 틀렸다.
실제 원인이 있었고, 추측을 접고 계속 판 것이 맞았다.

### 조치 — 관리형 애드온으로 못박음

`metrics-server`는 EKS 관리형 애드온으로 제공된다(IRSA 불필요). 그래서 기존 애드온 목록에 넣었다.

```hcl
resource "aws_eks_addon" "this" {
  for_each = toset(["vpc-cni", "coredns", "kube-proxy", "metrics-server"])
```

Helm 릴리스를 먼저 걷어내고 apply했으며, 적용 후 HPA가 즉시 CPU(4%)를 읽고 `ScalingActive=True`,
ArgoCD 앱도 `Healthy`가 되는 것을 확인했다. 부수적으로 관리형 애드온은 복제본이 2라 Helm 기본(1)보다
가용성이 낫다.

### 이 사건이 A5(정량 부하 측정)를 막고 있었다

metrics-server가 없으면 부하를 줘도 HPA가 늘지 않는다. 그 상태로 부하 시험을 했다면 "부하가
부족한가"로 오진했을 것이다. S09 DoD의 마지막 항목이 "부하 시 HPA 수평 확장 곡선"이므로,
이 재발을 먼저 잡지 않았으면 그 측정 자체가 성립하지 않았다.
