# k8s — 배포 매니페스트 (ArgoCD의 desired-state)

[ADR-009](../docs/decisions/ADR-009-gitops-cd-argocd.md)에 따라 **Git이 진실원**이고,
클러스터 안의 ArgoCD가 이 디렉터리를 동기화한다. **CI는 apply하지 않는다** —
이미지 태그를 커밋할 뿐이다(클러스터 apply 권한을 CI에 주지 않아 공격면을 줄인다).

```
k8s/base/
  namespace · configmap · api(Deployment·Service·HPA·PDB) · web(Deployment·Service) · ingress
```

## 라우팅 — ALB는 web만 본다

```
브라우저  GET /api/auth/login
   └→ ALB → web Pod
        └→ Next rewrite: /api/:path* → ${API_ORIGIN}/:path*   ← /api를 떼어낸다
             └→ flowticket-api:80 → Spring @PostMapping("/auth/login")
```

**앱이 처음부터 이 구조를 전제로 짜여 있다**(`next.config.mjs`). Spring 쪽에는 `/api` 접두어가
없다 — `@PostMapping("/auth/login")` 형태다.

> ⚠️ **ALB에서 `/api`를 API Service로 직접 보내면 안 된다.** ALB의 Prefix 라우팅은 접두어를
> 제거하지 않아 Spring이 `/api/auth/login`을 받고 **전부 404**가 된다. 초안이 그렇게 작성됐다.

이 구조의 이점: API가 인터넷에 노출되지 않는다 / 접두어 문제가 없다 / OAuth 프록시가 기존 설계
그대로다 / 브라우저 same-origin이라 CORS가 없다 / 타깃그룹과 Ingress가 각각 하나다.

### ⚠️ `API_ORIGIN`은 매니페스트에 없다 — 빌드 시점에 굳는다

`next.config.mjs`의 `rewrites()`는 **standalone 번들로 구워져 런타임 env로 바뀌지 않는다.**
이 프로젝트가 실제로 겪었다 — `apps/web/Dockerfile`: *"런타임 주입을 시도했더니 빌드 때 값인
localhost:8080으로 프록시해 ECONNREFUSED가 났다"*. 그래서 **이미지 build-arg**로 정한다.

```yaml
# .github/workflows/image.yml
build-args: |
  API_ORIGIN=${{ vars.API_ORIGIN || 'http://flowticket-api' }}
```

**포트를 붙이지 않는다.** Service는 `port: 80`을 열고 `targetPort: 8080`으로 넘긴다 —
`:8080`을 적으면 Service가 열지 않은 포트라 연결이 거부된다.

Deployment에 `API_ORIGIN`을 넣으면 **설정한 것처럼 보이지만 아무 효과가 없는 죽은 값**이 된다.
하네스 규칙 ④가 이걸 막고, ⑤가 위 포트 불일치를 막는다.

## SSE가 Next 프록시를 통과하는가 — **확인됨(2026-08-08)**

SSE도 같은 경로를 탄다.

```
useSeats.ts          new EventSource("/api/sse/events/{id}/seats")
next.config.mjs      /api/:path* → ${API_ORIGIN}/:path*
SeatSseController    @GetMapping("/sse/events/{id}/seats", produces = TEXT_EVENT_STREAM_VALUE)
```

**실측 결과 버퍼링 없음.** 스트림을 열어 둔 채 좌석을 선점했더니 즉시 도착했다.

```
23:31:55.659  좌석 167701 선점 요청 (holdId:1)
23:31:56.183  event:seat.held              ← 0.52초
23:31:56.225  data:{"seatIds":[167701]}
```

버퍼링이었다면 스트림이 닫힐 때까지(25초) 몰려 나왔을 것이다.

이 한 번의 요청이 여러 경로를 동시에 증명했다 — 대기열 토큰 발급 → 스케줄러 승격(ADMITTED)
→ 좌석 조건부 UPDATE 선점 → **커밋 후** SSE 발송(PR #175) → Redis TLS 위 pub/sub 팬아웃 → 구독자 전달.

> 대조군(클러스터 내부에서 API 직접 구독)은 컨테이너에 도구가 없어 실패했다.
> **프록시 통과 여부라는 원래 질문에는 답이 나왔지만, "프록시가 지연을 얼마나 더하는지"는 모른다.**

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
| `API_ORIGIN`(**build-arg**) ↔ `next.config.mjs` rewrites | `http://flowticket-api` | 이름·포트가 틀리면 프록시가 엉뚱한 곳으로 간다 |
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
