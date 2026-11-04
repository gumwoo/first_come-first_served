# overlays — 환경별 실제 값

`k8s/base/`는 `REPLACE_*` placeholder를 유지하고, **실제 값은 여기 오버레이에** 둔다.

## Git이 진실원이다

[ADR-009](../../docs/decisions/ADR-009-gitops-cd-argocd.md)대로 ArgoCD가 `main`의 `demo-local/`을
클러스터에 동기화한다(`k8s/argocd/application.yaml`). `kubectl apply -k`로 직접 적용하지 않는다.
클러스터에서 손으로 바꾼 값은 `selfHeal`이 Git 상태로 되돌린다.

## 여기에 있는 값

`demo-local/kustomization.yaml`에는 AWS 계정 ID, RDS·Redis 엔드포인트, ACM 인증서 ARN이 커밋돼 있다.
모두 식별자이고 시크릿이 아니다. 시크릿은 External Secrets가 AWS에서 동기화한다([k8s/README.md](../README.md)).

## 값을 바꾸는 법

| 값 | 바꾸는 주체 |
|---|---|
| 이미지 태그 | CI가 커밋한다(`.github/workflows/image.yml`). 손으로 고치지 않는다 |
| 엔드포인트·인증서 ARN | terraform output 값으로 `kustomization.yaml`을 고쳐 PR로 올린다 |
| 공연 오픈 사전 확장 | `event-prescale.yaml` 머리말의 절차를 따른다 |

렌더링 결과는 클러스터 없이 확인할 수 있다.

```bash
kubectl kustomize k8s/overlays/demo-local
```
