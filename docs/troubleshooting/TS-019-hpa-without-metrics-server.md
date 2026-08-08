# TS-019 · HPA를 선언했는데 스케일 판단을 못 한다 — metrics-server 없음

- 슬라이스: S09(EKS 배포)
- 날짜: 2026-08-08
- 유형: 배포 환경 결함(누락) — **조용히 동작하지 않음**
- 관련: `k8s/base/api-hpa.yaml`, `docs/deployment/aws-eks-deploy-plan.md` Phase 6
- 상태: 미해결 — 설치 예정

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

## 4. 해결(예정)

```bash
helm repo add metrics-server https://kubernetes-sigs.github.io/metrics-server/
helm install metrics-server metrics-server/metrics-server -n kube-system
```

설치 후 확인할 것:
- `kubectl top pods` 가 값을 반환하는가
- `kubectl get hpa` 의 `TARGETS`가 `<unknown>`에서 실제 퍼센트로 바뀌는가
- 부하를 주면 **replicas가 실제로 증가**하는가 (여기까지 봐야 "HPA가 동작한다"이다)

## 5. 재발 방지 — 아직 안 함

**매니페스트가 클러스터 애드온을 전제하는데 그것이 문서에도 코드에도 없었다.**
같은 유형이 또 있을 수 있다(예: EBS CSI가 없으면 PVC가 Pending — 이번엔 Terraform이 설치했다).

하네스로 잡기는 어렵다 — 클러스터 상태를 정적으로 알 수 없기 때문이다.
대신 **`k8s/README.md`에 "이 매니페스트가 전제하는 클러스터 애드온" 목록**을 두는 편이 현실적이다.
아직 하지 않았다.

## 6. 교훈

**"선언했다"와 "동작한다"는 다르다.** HPA·PDB·probe처럼 **선언만으로는 검증되지 않는 것들**은
실제로 발동시켜 봐야 확인된다. 이 프로젝트에서 아직 발동을 확인하지 않은 것:
- HPA 스케일업 (이 문서)
- PDB (노드 드레인을 해본 적 없음)
- 브로커 페일오버 (브로커를 죽여본 적 없음)
