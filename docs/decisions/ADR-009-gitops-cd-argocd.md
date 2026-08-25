# ADR-009 · GitOps CD — ArgoCD 채택 (Terraform=인프라 / ArgoCD=앱)

- 상태: **Accepted** (2026-08-10 구현·실증 완료 — ArgoCD 설치, CI 태그 커밋, 자동 동기화 + selfHeal/prune,
  드리프트 self-heal 실증. 구현 중 나온 사건은 [[TS-022]])
- 날짜: 2026-07-31
- 슬라이스: S09(배포·운영 준비) — 선행 S08(아웃박스) 먼저
- 관련: [[ADR-008]](Kafka 이벤트 백본), `docs/deployment/aws-eks-deploy-plan.md`, `docs/deployment/app-changes-for-k8s-kafka.md`

## 맥락
S09는 EKS(라이브 K8s) + Strimzi 멀티브로커로 "실제로 도는 분산 시스템"을 실증하는 인프라 트랙이다.
배포(CD)를 어떻게 할지 두 선택지가 있다:
- **push 기반**: GitHub Actions가 OIDC 롤로 클러스터에 `helm/kubectl apply`를 직접 실행.
- **pull 기반(GitOps)**: 클러스터 안의 ArgoCD가 Git 매니페스트를 감시해 desired-state로 지속 동기화.

이 프로젝트의 관통 철학은 "**Git이 단일 진실원**, 계약/정적 가드/테스트로 코드가 옳음을 스스로 증명"이다.
배포 축을 GitOps로 두면 그 철학이 런타임까지 확장된다 — **코드 계약은 harness가, 클러스터 실제 상태는
ArgoCD가 Git과 diff**. 제약: 단일 앱(모듈러 모놀리스, api/web 2 Deployment) 규모라 과설계 위험이 있다.

## 결정
1. **CD는 ArgoCD(GitOps, pull 기반)로 한다.** Git의 `k8s/` 매니페스트가 desired-state이고, ArgoCD가
   `Application`으로 클러스터를 지속 동기화한다. `syncPolicy: automated + selfHeal + prune`.
2. **책임 경계를 명확히 나눈다.**
   - **Terraform** = 인프라 프로비저닝(EKS·VPC·RDS·ElastiCache·IRSA·ECR).
   - **GitHub Actions** = 빌드·하네스·테스트·이미지 ECR push·**배포 매니페스트 이미지 태그 갱신(Git commit)**까지. **apply는 하지 않는다** → 클러스터 apply 권한을 CI에 주지 않아 공격면 축소.
   - **ArgoCD** = Git → 클러스터 동기화(앱·오퍼레이터 CR), 드리프트 감지·self-heal·롤백.
3. **Application은 최소로 유지한다.** api·web(앱) + (선택) 관측/Strimzi CR 정도. **App-of-Apps 같은 대형
   패턴은 지양** — 단일 앱에서 계층을 부풀리면 그 자체가 오버엔지니어링이다.
4. **매니페스트는 이 모노레포 `k8s/`에 둔다**(별도 config 레포 대신). 규모상 단순·추적 용이가 우선.
5. **ArgoCD UI는 사설로**(포트포워드 또는 사설 Ingress). 공개 노출하지 않는다.

## 고려한 대안
- **push 기반(Actions가 직접 apply)**: 설정이 가장 단순하고 컴포넌트가 안 는다. 그러나 (1) 클러스터
  apply 권한을 CI가 들고 있어야 하고 (2) "클러스터 실제 상태 = Git"이라는 보장(드리프트 감지·self-heal)이
  없다. 이 프로젝트의 "Git이 진실원" 서사와 약하게 맞음 → 기각(단, ArgoCD가 과하면 언제든 되돌릴 수 있는
  합리적 백업안으로 남겨둠).
- **Flux(대안 GitOps)**: 기능적으로 유사. ArgoCD는 UI/시각화가 강해 **면접 데모(Synced/Healthy·드리프트
  복구)에 유리** → ArgoCD 채택.
- **App-of-Apps/멀티 레포/멀티 환경**: 단일 앱·단일 데모 환경엔 과함 → 기각(스코프 관리).

## 결과 / 한계 (정직)
- ~~**아직 Proposed**: 코드/매니페스트 미작성.~~ → **Accepted (2026-08-10 승격).**
  `k8s/argocd/`에 values·`Application`이 있고, `scripts/bring-up.sh` 4/7단계가 설치한다.
  자동 동기화(`selfHeal`·`prune`)가 실제로 동작하는 것을 확인했다 — 2026-08-25 롤링 측정에서
  `automated`를 잠시 제거하지 않으면 `kubectl set image`를 즉시 되돌려, 측정 구간에 두 번째
  롤링이 겹쳐 들어왔다([[TS-035]] §5). **되돌리는 힘이 실제로 있다는 증거**다.
  구현 중 나온 사건은 [[TS-022]].
- **컴포넌트 증가**: ArgoCD 자체가 클러스터 리소스를 먹고 관리 포인트가 하나 는다. 데모용이면 그만큼 과금 →
  Application 최소화로 상쇄.
- **Terraform과의 경계 흐림 주의**: "인프라=Terraform, 앱=ArgoCD"를 지키지 않으면 무엇이 무엇을 관리하는지
  모호해진다. ArgoCD 자신의 설치는 부트스트랩이라 Helm(또는 Terraform helm provider)으로 1회 처리.
- **비밀**: 매니페스트에 평문 비밀 금지(프로젝트 규칙). External Secrets → Secrets Manager 경유 유지 —
  ArgoCD는 `ExternalSecret` 리소스를 동기화할 뿐 값은 모른다.
- **되돌리기**: 과하다고 판단되면 push 기반(Actions apply)으로 회귀 가능 — 매니페스트(`k8s/`)는 그대로
  재사용되므로 전환 비용이 낮다.
