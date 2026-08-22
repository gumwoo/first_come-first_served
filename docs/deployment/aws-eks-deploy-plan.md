# 배포 계획 — AWS EKS(라이브 K8s) + Strimzi 멀티브로커 Kafka

- 상태(2026-08-10): **Phase 1~7 완료 / Phase 8은 부분 완료.**
  - Phase 1·2 컨테이너화·ECR 파이프라인 — 완료
  - Phase 3 Terraform(EKS·RDS·ElastiCache·ALB) — 기동 중([[ADR-012]] Accepted)
  - Phase 4 Strimzi 멀티브로커(KRaft, 브로커 3) — 기동 중
  - Phase 5 앱 프로덕션 설정 — 완료(멀티팟 대응 ①②③④ 포함)
  - Phase 6 GitOps(ArgoCD)·HPA·Ingress·HTTPS — 완료, 자동 동기화 + selfHeal/prune([[ADR-009]] Accepted, [[TS-022]])
  - Phase 7 관측성 — Prometheus/Grafana + 앱 메트릭 + Kafka 브로커/Consumer Lag 수집 완료
  - **Phase 8 — 이진 실증([[TS-023]])과 정량 부하 실측이 모두 완료.** capacity knee 600~650 rps,
    포화 징후는 노드 CPU와 Hikari pending에서 동시에 관측(`benchmarks/phase1-saturation/`)
  - **DoD 기준 9/9** — §6 참조
- AWS 상시 과금은 Phase 3(EKS·RDS)부터 발생한다. 비용 통제는 apply/destroy 운용.
- 목적: 백엔드 취업용 **살아있는 데모 URL** + **분산 시스템(K8s·멀티브로커 Kafka·오토스케일) 실증**.
- 짝 문서: [앱 변경사항 — K8s·멀티브로커 대응](app-changes-for-k8s-kafka.md)
- 슬라이스: 배포(S09). 부하테스트(S10) 측정 환경도 이 위에서. 선행: **S08 아웃박스(정합성) 먼저**.

## 0. 결정 요약 (왜 이렇게)

| 결정 | 선택 | 근거 |
|------|------|------|
| 오케스트레이터 | **EKS(라이브 쿠버네티스)** | 라이브 K8s 운영 역량 시연이 목표. 오토스케일·롤링·프로브를 실제로 돌린다. |
| Kafka | **Strimzi 오퍼레이터로 in-cluster 멀티브로커(RF 3)** | "전부 K8s에서 도는 분산 시스템" 서사. 복제·파티션·페일오버를 **실증**(단일 브로커면 Kafka HA 의미 없음). |
| DB/Redis | **RDS PostgreSQL · ElastiCache Redis (매니지드, 클러스터 밖)** | 스테이트풀 DB는 매니지드가 실무 정석. Kafka만 in-cluster로 시연 대상. |
| 앱 구조 | **모듈러 모놀리스 유지**(api·web 2 Deployment) | 억지 마이크로서비스 금지. 파드 replica가 같은 컨슈머 그룹 공유로 확장. |
| 스케일 | **api·web HPA(CPU) + Kafka 파티션 병렬 + 브로커 페일오버** | 정직: 컨슈머 작업이 가벼워 "컨슈머 랙 오토스케일"은 억지. 스케일은 CPU 기준 HTTP 티어(api·web — web은 2026-08-11 실측 첫 실패 지점), Kafka는 HA·파티션·관측으로 나눠 실증. |
| IaC | **Terraform**(EKS·VPC·RDS·ElastiCache·IRSA) + **Helm**(앱·Strimzi·관측) | 신호 + 재현성. |
| CI (빌드·테스트·이미지) | **GitHub Actions → ECR**(OIDC) + 배포 매니페스트 이미지 태그 갱신(Git commit) | 장기 키 없이 OIDC 롤. push 배포 대신 "이미지 push + Git 갱신"까지만. |
| CD (배포·동기화) | **ArgoCD**(GitOps, pull 기반) — Git 매니페스트를 클러스터에 지속 동기화 | 선언적 desired-state·드리프트 감지·self-heal·원클릭 롤백. **Terraform=인프라 / ArgoCD=앱** 분업. 상세 [ADR-009](../decisions/ADR-009-gitops-cd-argocd.md). |
| 비밀 | **External Secrets → Secrets Manager** | 프로젝트 규칙(비밀 env only) 유지. |
| 관측 | **kube-prometheus-stack(Prometheus+Grafana)** | Kafka·Consumer Lag·HPA·JVM 대시보드로 **실증**. |

> **오버엔지니어링 방어(면접 프레이밍)**: 이 규모엔 과한 걸 안다 → **분산/K8s/Kafka 운영 역량 시연 목적으로 의도적 선택**이며, 선착순 티켓팅=폭주+정합성 도메인이라 확장·복제가 실제로 의미 있고, **부하·페일오버로 효과를 실증**한다. "구성만"이 아니라 "증거 있는 엔지니어링".
>
> **ArgoCD(GitOps)를 왜 더하나**: 이 프로젝트 철학이 "**Git이 단일 진실원**, 계약/가드로 스스로 증명"이라, 배포 축을 GitOps로 확장하는 건 결이 정확히 같다 — 코드 계약은 harness가, **클러스터 실제 상태는 ArgoCD가 Git과 diff**. 단일 앱이어도 GitOps는 정당화되며(MSA와 달리 오버엔지니어링 반박이 쉬움), Application은 **최소(api/web + 관측)로 유지**해 부풀리지 않는다. push 배포 대비 트레이드오프는 [ADR-009](../decisions/ADR-009-gitops-cd-argocd.md)에 기록.

## 1. 타깃 아키텍처

```
GitHub Actions ─(빌드·하네스·테스트·이미지)→ ECR
       └─(이미지 태그로 배포 매니페스트 갱신 → Git commit)→ k8s/ 매니페스트(Git = 단일 진실원)
                                                                    │  pull·watch
                                                                    ▼
   ArgoCD (in-cluster, GitOps) ─(sync·self-heal·drift 감지)→ EKS 클러스터 (ap-northeast-2)
             ├─ Strimzi Operator → Kafka broker×3 (RF 3, 파티션 N) [in-cluster]
             ├─ api Deployment (replicas, HPA=CPU, liveness/readiness, graceful shutdown) ─┐
             ├─ web Deployment (replicas)                                                    ├─ ALB Ingress (HTTPS/ACM) → 도메인
             │                                                                               ┘
             ├─ External Secrets ← Secrets Manager
             └─ kube-prometheus-stack (Prometheus + Grafana): Kafka·Consumer Lag·HPA·JVM
   (클러스터 밖 매니지드) RDS PostgreSQL · ElastiCache Redis
   IaC: Terraform(EKS·VPC·RDS·ElastiCache·IRSA)   로그: CloudWatch(또는 Loki)
   경계: Terraform=인프라 프로비저닝 / ArgoCD=앱·오퍼레이터 매니페스트 동기화 / Actions=빌드·이미지·Git 갱신
```

- api는 Flyway로 부팅 시 RDS 마이그레이션(ddl-auto=validate 유지).
- Kafka는 Strimzi CRD로 브로커 3·RF 3·`order-events` 파티션 N. api 파드가 같은 컨슈머 그룹.
- RDS/ElastiCache는 프라이빗, 파드 SG(또는 IRSA/보안그룹)만 접근.

## 2. 단계(Phase)와 산출물

각 단계 가능한 로컬 검증 후 진행. **[내]=코드/IaC/Helm 작성, [너]=AWS 계정 액션·과금·승인.**

### Phase 1 — 컨테이너화 (AWS 비용 0) — **완료(S09-1)**
- `apps/api/Dockerfile`(gradle 8.10.2 빌드 → JRE 실행, non-root, MaxRAMPercentage, readiness 헬스체크),
  `apps/web/Dockerfile`(pnpm → standalone, non-root), 루트 `.dockerignore`, `infra/docker-compose.prod.yml`.
- **로컬 운영 이미지로 실제 검증 완료**: readiness/liveness 200, Flyway 마이그레이션, Redis 공유,
  Kafka 컨슈머 그룹 조인, web→api 프록시, 회원가입→로그인→/me→refresh, 대기열 토큰 발급.
- 이 단계에서 **배포 블로커 2건을 실측으로 발견해 수정**했다(아래 "실측으로 잡은 것").
- 산출물: 프로덕션 이미지 로컬 부팅 ✅

> **실측으로 잡은 것(문서만 봤으면 못 잡았을 것)**
> 1. `/actuator/health/{liveness,readiness}`가 **401**이었다 — 시큐리티가 `/actuator/health`만 permitAll.
>    K8s probe는 인증 헤더를 못 붙이므로 그대로 배포하면 **Pod가 영원히 Ready가 되지 않는다.**
> 2. Next `standalone`은 rewrites 목적지를 **빌드 시점에 굽는다** — 런타임 `API_ORIGIN` 주입이 무시돼
>    `localhost:8080`으로 프록시하며 `ECONNREFUSED`. → build-arg로 전환.

### Phase 2 — ECR + CI 이미지 파이프라인 (GitOps 대비) — **이미지 push까지 완료**
- [너] ECR 저장소 2개(`flowticket-api`/`flowticket-web`), GitHub OIDC 공급자, IAM 롤
  `flowticket-gha-ecr`(**ECR push 전용** — EKS apply 권한 없음), GitHub Secret `AWS_ROLE_ARN` ✅
- [내] `.github/workflows/image.yml` ✅ — **CI 성공 후**(`workflow_run`) 이미지 빌드 → ECR push, **태그 = git SHA**
  - push 트리거가 아니라 `workflow_run`인 이유: push면 CI와 **병렬**로 돌아 테스트가 깨진 커밋의 이미지도 올라간다.
  - `workflow_run`에는 paths 필터를 걸 수 없어 잡 안에서 **변경 파일을 직접 판별**(문서만 고친 머지엔 빌드 안 함).
  - `latest` 같은 이동 태그를 두지 않는다 — 도는 이미지와 커밋이 1:1이어야 롤백이 "이전 SHA로" 끝난다.
- [내] **매니페스트 태그 갱신 커밋은 Phase 6**(ArgoCD 도입)에서 붙인다 — 지금은 갱신할 `k8s/`가 없다.
- 산출물: main 머지(테스트 통과) 시 ECR 자동 적재 ✅ / 매니페스트 자동 갱신은 Phase 6

### Phase 3 — IaC: 클러스터·데이터·네트워크 (Terraform)
- [내] VPC(퍼블릭/프라이빗) · **EKS**(노드그룹) · RDS · ElastiCache · ECR · IAM/**IRSA** · CloudWatch
- [너] `terraform apply`(과금) · tfstate 백엔드(S3+DynamoDB)
- 산출물: EKS 클러스터 + 매니지드 데이터 기동, `kubectl` 접속

### Phase 4 — Strimzi 멀티브로커 Kafka (in-cluster)
- [내] Strimzi 오퍼레이터 설치(Helm) + `Kafka` CR(브로커 3, RF 3), `KafkaTopic`(order-events 파티션 N, DLT)
- [내] 앱 Kafka 설정을 in-cluster 부트스트랩 서비스로 지정
- 산출물: 3브로커 Kafka + 토픽 생성, 브로커 페일오버 예비 확인

### Phase 5 — 앱 프로덕션 설정 (코드 변경 — 짝 문서 참조)
- [내] [앱 변경사항 문서](app-changes-for-k8s-kafka.md)의 항목: **파티션↑·복제↑·Kafka 메트릭·probe·graceful shutdown·env 외부화**
- [내] prod 프로필: datasource/redis/`KAFKA_BOOTSTRAP_SERVERS`(in-cluster) · 시크릿은 External Secrets
- 산출물: 컨테이너가 매니지드/클러스터 서비스에 실연결

### Phase 6 — GitOps 배포(ArgoCD) · HPA · Ingress · HTTPS
- [내] `k8s/`(또는 `deploy/helm`) 차트: api/web Deployment·Service·**HPA**·liveness/readiness·ConfigMap/ExternalSecret
- [내] **ArgoCD 설치(Helm)** + `Application`(source=이 레포 `k8s/`, dest=클러스터, **syncPolicy: automated + prune + selfHeal**)
  - 최소 Application 세트: **api·web**(앱) + (선택) 관측/Strimzi CR을 별도 Application으로 — App-of-Apps 같은 대형 패턴은 지양(스코프 관리)
- [내] **ALB Ingress Controller** + Ingress(호스트/경로), [너] 도메인·**ACM** DNS 검증
- [내/너] Git 갱신 → **ArgoCD 자동 sync** → `/actuator/health` → 관리자 로그인 → (선택) 배포 URL admin E2E
- [내] 드리프트 데모: 클러스터에서 수동 변경 → ArgoCD가 **OutOfSync 감지·self-heal 복구**(캡처)
- 산출물: 공개 HTTPS 데모 URL, HPA 동작, **ArgoCD Synced/Healthy + self-heal 증거**

### Phase 7 — 관측성 (실증 준비)
- [내] kube-prometheus-stack(Helm): Prometheus + Grafana. **Kafka·Consumer Lag·HPA·JVM** 대시보드
- 산출물: 지표 대시보드 + 캡처(노션/README)

### Phase 8 — 부하테스트 · 페일오버 실증 (S10)
- [내] k6를 배포 URL 대상 → **HPA 수평 확장**(RPS/p95 곡선), **Consumer Lag** 추이
- [내] **브로커 1대 강제 종료 → RF 3로 서비스 지속** 페일오버 데모(캡처)
- [내] IMP 문서(로컬 머신바운드 → 클라우드 재측정) + 스케일/페일오버 증거
- 산출물: 오토스케일·페일오버·랙 대시보드 = "증거 있는 분산"

## 3. 착수 전 확정할 것

1. **리전**: 기본 `ap-northeast-2`(서울)?
2. **도메인**: HTTPS(ACM)에 필요(ALB 기본 DNS로는 신뢰 인증서 불가). Route53 신규 vs 보유?
3. **Kafka 규모**: 브로커 3 / RF 3 / `order-events` 파티션 수(예: 6)?
4. **관측 스택**: kube-prometheus-stack(권장) vs CloudWatch Container Insights?
5. **GitHub OIDC / IRSA** 사용(권장).
6. **GitOps 매니페스트 위치**: 이 모노레포 내 `k8s/`(권장, 단순) vs 별도 config 레포?
7. **ArgoCD sync 정책**: automated + selfHeal + prune(권장) vs 수동 sync(데모 통제)? ArgoCD UI 노출은 사설(포트포워드/사설 Ingress).

## 4. 역할 분담 & 안전

- **내가**: Dockerfile·CI·Terraform·Helm·Strimzi CR·**ArgoCD Application 매니페스트**·prod 설정·앱 코드 변경·문서 전부 repo 코드로.
- **네가**: AWS 계정/자격증명, OIDC·IRSA·tfstate 부트스트랩, `terraform apply`/`helm install`(ArgoCD 포함, 과금), 도메인 구입, 비용 승인.
- 나는 **자격증명 입력·유료 리소스 프로비저닝을 대신 하지 않음.** 명령/코드 제공 + 로그로 디버깅.
- 백엔드 컴파일/통합 검증은 로컬 gradle 없음 → CI(Testcontainers).

## 5. 리스크 & 정직한 한계

- **가장 큰 리스크 = "구성만 하고 실증 못 함".** 그러면 진짜 오버엔지니어링. → 파티션·복제·부하·페일오버로 **반드시 실증**(Phase 7·8이 핵심).
- **Strimzi 멀티브로커 = 이 계획에서 제일 어렵고 무거운 부분**(운영자·PVC·리밸런스·리소스).
- **컨슈머 작업이 가벼워** 컨슈머-랙 오토스케일은 자연스럽지 않음 → 스케일은 **API 티어 HPA**, Kafka는 **HA·파티션·관측**으로 나눠 실증(억지 금지).
- **HTTPS엔 도메인 필수.**
- **ArgoCD도 클러스터에서 도는 컴포넌트** → 리소스·관리 포인트가 하나 늘고, 데모용이면 그만큼 과금. 단일 앱이라 **Application을 최소로 유지**하고 App-of-Apps 같은 대형 패턴은 지양(안 그러면 그게 오버엔지니어링). "왜 push 대신 GitOps?"는 [ADR-009](../decisions/ADR-009-gitops-cd-argocd.md)로 방어.
- **여러 세션짜리 대형 작업.** AWS 실행은 네 계정에서 네가.
- 비용은 우선순위 아니나, 데모 후 노드그룹/브로커 축소·중단으로 절감.

## 6. 완료 정의(DoD)

> 2026-08-11 기준 **9/9**. 각 항목 뒤에 근거를 적었다 — 근거를 못 대는 항목은 체크하지 않는다.

- [x] 공개 HTTPS URL 동작 — `https://flow-ticket.com` 200, `/api/events` 200 확인.
      ⚠️ **결제까지의 전체 플로우를 이번에 다시 훑지는 않았다**(진입점과 목록 API만 확인)
- [x] main 머지 → 이미지 ECR + 매니페스트 Git 갱신 → **ArgoCD 자동 sync** — `bump-tag` 잡 실행 성공,
      자동 동기화 활성화(#204·#206)
- [x] **ArgoCD Synced/Healthy** + 드리프트 self-heal 데모 — [[TS-022]] §6-3(양성 2건 + 음성 대조군 1건)
- [x] Terraform으로 EKS·데이터·네트워크 재현 — 3AZ 클러스터 기동 중([[ADR-012]] Accepted)
- [x] Strimzi **브로커 3·RF 3** + `order-events` **파티션 6** — `--describe`로 확인
- [x] **부하 시 api HPA 수평 확장 곡선(RPS/p95)** — 2026-08-11 완료.
      k6를 **클러스터 밖(노트북)**에서 돌려 [[TS-021]] §7의 자원 경합 문제를 피했다.
      HPA 확장 곡선(3→9, 68초)과 도착률별 RPS/p95를 **클라이언트·서버 양쪽**으로 기록했고,
      AS-IS/TO-BE 비교로 병목 이동까지 추적했다:
      `benchmarks/{asis-web2pod,tobe-web-hpa,phase1-saturation}/`
      - capacity knee **600~650 rps**, 포화 징후가 **노드 CPU와 Hikari pending에서 동시에** 나타남
      - web 병목 개선 전후: 600 rps에서 실패 4.06% → 0%, p95 10,006ms → 123ms
      - ⚠️ 수치는 노트북에서 인터넷 경유로 측정 — 절대 성능이 아니라 동일 조건 비교값이다
- [x] **브로커 페일오버**에도 서비스 지속 — [[TS-023]] §4(4회차, 유실 0)
- [x] Grafana에 **Kafka·Consumer Lag·HPA** 대시보드 — #208(14패널, 쿼리 19개 전부 데이터 반환)
- [x] `k8s/` 차트 + 배포 절차 문서 — `k8s/{base,overlays,kafka,monitoring,argocd}` + 각 kustomization

### 클러스터 재생성 절차 (bring-up)

`terraform-design.md` §6에 destroy 순서는 있으나 **반대 방향이 없었다.** 2026-08-11 재기동에서
순서를 기억에 의존해 진행했고, metrics-server가 빠진 것을 나중에 발견했다([[TS-019]] 재발).

```
1. terraform apply (platform)                       # EKS·RDS·ElastiCache·NAT
2. aws eks update-kubeconfig --name flowticket
3. helm: aws-load-balancer-controller               # IRSA ARN·VPC ID 인자 필요
   helm: strimzi (ns kafka)
   helm: kube-prometheus-stack (-f k8s/monitoring/...values.yaml)
   helm: argocd (-f k8s/argocd/values.yaml)
   # metrics-server 는 EKS Add-ons 로 terraform 이 만든다(TS-019)
4. kubectl apply -f k8s/base/namespace.yaml         # ← 반드시 ESO보다 먼저(아래 ⚠️)
   bash k8s/external-secrets/bootstrap.sh           # ESO + 시크릿 동기화
5. kubectl apply -k k8s/kafka
   kubectl apply -k k8s/monitoring
   kubectl apply -f k8s/argocd/application.yaml
6. Route53 A 레코드를 새 ALB로 갱신                   # 클러스터 재생성 시 ALB 이름이 바뀐다
```

⚠️ 4번이 3번보다 뒤인 이유: ESO는 CRD를 스스로 설치하므로 순서 의존이 없지만, api Pod가
시크릿을 기다리므로 앱 배포(5번) 전에 끝나 있어야 한다.

⚠️ **네임스페이스를 4번 앞에 둔 이유 — 이게 없으면 절차대로 해도 반드시 실패한다.**
`bootstrap.sh`의 마지막 단계가 `flowticket` 네임스페이스에 ExternalSecret을 만드는데,
그 네임스페이스는 **5번(`kubectl apply -k k8s/overlays/...`)이 만든다.** 즉 4번 → 5번 순서
자체는 맞지만 네임스페이스 생성만 앞으로 빼야 한다. 2026-08-16 재기동에서 실제로 걸렸다:

```
Error from server (NotFound): error when creating "STDIN": namespaces "flowticket" not found
```

**DoD 밖의 후속 개선** — ✅ **2026-08-11 완료**: External Secrets Operator 도입.
`flowticket-api-secrets`는 이제 AWS에서 동기화된다(SSM Parameter Store 9개 + Secrets Manager
DB 자격증명 2개). 시크릿을 삭제해도 약 4초 만에 재생성되는 것을 실증했다. 완료 조건은 아니었으나 운영 재현성의
마지막 구멍이었다.

재생성 범위는 정확히 이렇다 — **RDS를 포함해 인프라를 전부 재생성해도** `bootstrap.sh`가
`terraform output -raw db_secret_arn`으로 RDS 시크릿 ARN을 매번 채우므로 사람이 매니페스트를
고칠 일이 없다. 다만 **SSM 파라미터 9개(JWT·OAuth·KOPIS·TOSS·관리자 계정)는 AWS 계정에 남아
있어야 한다** — 그것까지 지우면 값을 다시 등록해야 하고, 그 값은 애초에 사람만 아는 것이다.
