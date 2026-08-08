# overlays — 환경별 실제 값

`k8s/base/`는 `REPLACE_*` placeholder를 유지하고, **실제 값은 여기 오버레이에** 둔다.

## 왜 나눴나

`REPLACE_ECR_REGISTRY`·`REPLACE_ACM_CERT_ARN`에는 **AWS 계정 ID**가 들어간다.
이 저장소는 공개라 커밋하면 히스토리에 영구히 남는다(치명적 비밀은 아니지만 되돌릴 수 없다).

## ⚠️ 지금은 Git이 진실원이 아니다 (정직)

[ADR-009](../../docs/decisions/ADR-009-gitops-cd-argocd.md)는 **"Git의 `k8s/` 매니페스트가
desired-state"** 라고 정했는데, `demo-local/` 오버레이는 `.gitignore` 대상이라 **Git에 없다.**
지금은 로컬에서 `kubectl apply -k`로 직접 적용하는 상태이고, **ArgoCD를 붙이려면 이 값들이
Git에 있어야 한다.**

해소 계획: 저장소를 비공개로 전환할 때 오버레이를 커밋한다. 그때까지 ADR-009는
"매니페스트 구조는 준비됐고 동기화 주체만 사람"인 상태다.

## 쓰는 법

```bash
cp -r k8s/overlays/demo-local.example k8s/overlays/demo-local
# demo-local/kustomization.yaml 의 <...> 자리를 terraform output 값으로 채운다
kubectl apply -k k8s/overlays/demo-local
```
