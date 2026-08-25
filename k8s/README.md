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
| `REPLACE_TAG` | base 기본값. 실제로는 오버레이의 `images.newTag`가 덮어쓴다 |

> ⚠️ `REPLACE_ECR_REGISTRY` 행이 2026-08-12까지 이 표에 남아 있었다. **매니페스트에는 그런
> placeholder가 없다** — 레지스트리는 `overlays/demo-local/kustomization.yaml`의
> `images.newName`에 실제 값으로 들어가 있고, 거기에는 **AWS 계정 ID가 그대로 커밋돼 있다.**
> 표 아래에 붙어 있던 "공개 저장소라 placeholder로 둔다"는 설명은 사실과 반대였다.
>
> 계정 ID는 자격증명이 아니라 식별자라 프로젝트의 비밀 규칙(ADR-013) 위반은 아니다. 다만
> **문서가 "숨겼다"고 말하는데 실제로는 커밋돼 있는 상태**는 그 자체로 위험하다 — 읽는 사람이
> 저장소의 노출 범위를 잘못 알게 된다.

## 시크릿은 이 디렉터리에 없다

`flowticket-api-secrets`는 **External Secrets Operator가 AWS에서 동기화한다**(2026-08-11~).
출처는 SSM Parameter Store(값 9개) + Secrets Manager(DB 자격증명 2개)이며, 매니페스트는
`k8s/external-secrets/`에 있다. 이전에는 손으로 만들었고 클러스터를 재생성할 때마다 사람이
값을 다시 넣어야 했다 — 그것이 매니페스트만으로 복구되지 않던 유일한 구멍이었다.

비밀 값 자체(JWT_SECRET·DB_PASSWORD·OAuth 키·KOPIS 키 등)는 **매니페스트에 두지 않는다**
(ADR-013, 프로젝트 규칙). RDS 마스터 비밀번호는 Terraform이 `manage_master_user_password`로
Secrets Manager에 만든다. Deployment는 `secretRef: flowticket-api-secrets`를 참조만 한다.

> ⚠️ 2026-08-12까지 이 절에는 **"ESO 연결은 목표이고 아직 하지 않았다"**는 문단이 아래에 함께
> 남아 있었다. 위 문단(했다)과 정면으로 모순인데도 같은 절 안에 공존했다 — 새 서술을 얹으면서
> 옛 문단을 지우지 않았기 때문이다. 그 문단을 제거했다.

## 무중단 배포를 위해 짝을 맞춘 값들

이 파일들에서 **혼자 의미를 갖는 값은 하나도 없다.** 어긋나면 조용히 무중단이 깨진다.

| 짝 | 값 | 어긋나면 |
|---|---|---|
| `maxUnavailable: 0` ↔ readiness | — | 1이면 새 Pod가 뜨기 전에 용량이 줄어 부하 중 에러 |
| `SHUTDOWN_TIMEOUT(30s)` ↔ `terminationGracePeriodSeconds(45)` | 앱 < Pod | 뒤집히면 진행 중 요청 중 SIGKILL |
| web `preStop sleep 25` ↔ 등록 해제 **전파** 시간 | preStop > 전파 | 짧으면 컨테이너가 먼저 죽고, ALB가 아직 정상으로 아는 IP로 보내 502/504 — 2026-08-25 실측 13건([TS-035](../docs/troubleshooting/TS-035-rolling-deregistration-race.md)). `deregistration_delay`와 짝짓는 값이 **아니다**(그건 deregistering 이후의 드레이닝) |
| web `preStop(25s)` ↔ `terminationGracePeriodSeconds(60)` | preStop < Pod | 뒤집히면 preStop 도중 SIGKILL — 고치려던 것보다 나빠진다 |
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

## 이 디렉터리에 무엇이 있나

한때 여기에 **"아직 없는 것"** 목록이 있었다. 네 항목 전부 그 뒤로 생겼는데 목록은 그대로
남아 **문서가 저장소를 반대로 설명하는 상태**가 됐다(2026-08-12 리뷰에서 발견). 목록을
현재 상태로 바꾸고, 같은 종류의 드리프트를 하네스 규칙 ⑯이 잡도록 했다.

| 대상 | 위치 |
|---|---|
| Strimzi Kafka CR — `KAFKA_BOOTSTRAP_SERVERS`가 가리키는 대상 | `kafka/kafka.yaml` |
| ArgoCD `Application` — 이 디렉터리를 가리키는 리소스 | `argocd/application.yaml` |
| kube-prometheus-stack — 관측 | `monitoring/kube-prometheus-stack.values.yaml` |
| ExternalSecret — 위 시크릿 절 | `external-secrets/externalsecret-api.yaml` |

**"아직 없다"고 쓸 때는 경로를 백틱으로 적는다.** 규칙 ⑯이 그 경로의 실존을 검사하므로,
나중에 생기면 CI가 문서를 고치라고 말해 준다. 경로 없이 자연어로만 쓰면 아무도 못 잡는다.
