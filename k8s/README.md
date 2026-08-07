# k8s — 배포 매니페스트 (ArgoCD의 desired-state)

[ADR-009](../docs/decisions/ADR-009-gitops-cd-argocd.md)에 따라 **Git이 진실원**이고,
클러스터 안의 ArgoCD가 이 디렉터리를 동기화한다. **CI는 apply하지 않는다** —
이미지 태그를 커밋할 뿐이다(클러스터 apply 권한을 CI에 주지 않아 공격면을 줄인다).

```
k8s/base/
  namespace · configmap · api(Deployment·Service·HPA·PDB) · web(Deployment·Service) · ingress×2
```

## Ingress가 둘인 이유 (ALB는 하나)

`healthcheck-path`는 **Ingress 단위**로만 적용된다. api와 web을 한 Ingress에 두면 두 타깃그룹이
같은 경로를 검사하게 되고, Next.js인 web에 `/actuator/health/readiness`를 던져 **web 타깃 전체가
unhealthy**가 된다(컨트롤러가 per-backend 분리를 지원하지 않는다 — kubernetes-sigs/#1056).

그렇다고 Ingress를 그냥 둘로 나누면 **ALB도 둘**이 된다(요금·DNS). 같은
`alb.ingress.kubernetes.io/group.name`을 주면 컨트롤러가 **하나의 ALB로 합친다.**
`group.order`는 규칙 평가 순서이고, `/api`가 catch-all(`/`)보다 먼저 평가돼야 한다.

**`/actuator`는 공개 규칙에서 뺐다.** ALB 헬스체크는 타깃그룹이 Pod IP로 직접 검사하므로 Ingress
규칙이 필요 없고, 두면 `metrics`·`prometheus`까지 인터넷에 열린다
(`exposure: health, info, metrics, prometheus`). Prometheus는 클러스터 안에서 Pod를 스크레이프한다.

## 아직 적용할 수 없다 — 남은 자리(REPLACE_*)

**의도적으로 placeholder를 남겼다.** `platform` 스택을 apply해야 나오는 값들이고,
지금 임의의 값을 박으면 "적용되는 것처럼 보이지만 틀린" 상태가 된다.

| placeholder | 어디서 |
|---|---|
| `REPLACE_DB_HOST` / `REPLACE_REDIS_HOST` | `terraform output db_endpoint` / `redis_endpoint` |
| `REPLACE_ACM_CERT_ARN` | bootstrap 스택의 ACM 인증서 |
| `REPLACE_ECR_REGISTRY` | `<account>.dkr.ecr.ap-northeast-2.amazonaws.com` |
| `REPLACE_TAG` | CI가 push한 이미지 태그(= 커밋 SHA) |

> ECR 레지스트리에는 **AWS 계정 ID**가 들어간다. 공개 저장소라 placeholder로 두고,
> 실제 값은 apply 시점에 채운다.

## 시크릿은 이 디렉터리에 없다

`flowticket-api-secrets`(JWT_SECRET·DB_PASSWORD·OAuth 키·KOPIS 키 등)는 **매니페스트에 두지
않는다**(ADR-013, 프로젝트 규칙). RDS 마스터 비밀번호는 Terraform이
`manage_master_user_password`로 Secrets Manager에 만든다.

**현재 상태**: Deployment가 `secretRef: flowticket-api-secrets`를 참조만 하고, 그 Secret을
만드는 것은 이 디렉터리 밖이다. External Secrets Operator로 Secrets Manager와 연결하는 것이
목표이고 **아직 하지 않았다** — 그전까지는 클러스터에 직접 만들어야 한다.

## 무중단 배포를 위해 짝을 맞춘 값들

이 파일들에서 **혼자 의미를 갖는 값은 하나도 없다.** 어긋나면 조용히 무중단이 깨진다.

| 짝 | 값 | 어긋나면 |
|---|---|---|
| `maxUnavailable: 0` ↔ readiness | — | 1이면 새 Pod가 뜨기 전에 용량이 줄어 부하 중 에러 |
| `SHUTDOWN_TIMEOUT(30s)` ↔ `terminationGracePeriodSeconds(45)` | 앱 < Pod | 뒤집히면 진행 중 요청 중 SIGKILL |
| `preStop sleep 5` ↔ ALB `deregistration_delay 10` | — | 없으면 등록 해제가 시작되기도 전에 내려가 502 (충분한지는 실측 대상) |
| probe 경로 ↔ `application.yml`의 health group | — | readiness에 Kafka가 끼면 브로커 하나에 API 전체가 빠짐 |
| `REDIS_SSL_ENABLED=true` ↔ ElastiCache TLS | 둘 다 켜짐 | 하나만 켜지면 **Pod가 영원히 Ready 안 됨** |

마지막 줄이 [C-7](../docs/deployment/app-changes-for-k8s-kafka.md) 체크리스트 2번이다.

## 검증

```bash
kubectl kustomize k8s/base           # 렌더링(클러스터 불필요)
kubectl apply --dry-run=client -k k8s/base
```

`REPLACE_*`가 남아 있어도 렌더링은 된다 — **문법은 검증되지만 값은 검증되지 않는다.**

## 아직 없는 것

- **Strimzi Kafka CR** — `KAFKA_BOOTSTRAP_SERVERS`가 가리키는 대상(Phase 4)
- **ArgoCD `Application`** — 이 디렉터리를 가리킬 리소스(Phase 6)
- **kube-prometheus-stack** — 관측(Phase 7)
- **ExternalSecret** — 위 시크릿 절
