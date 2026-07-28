# 배포 계획 — AWS ECS Fargate (라이브) + K8s 아티팩트

- 상태: 계획(미착수). 실행 전 이 문서로 합의.
- 목적: 백엔드 개발자 취업용 **살아있는 데모 URL** + 클라우드 엔지니어링 신호(ECR·IaC·CI/CD·매니지드·관측성).
- 슬라이스: 배포(S08 부하테스트의 측정 환경도 이 위에서).

## 0. 결정 요약 (왜 이렇게)

| 결정 | 선택 | 근거 |
|------|------|------|
| 오케스트레이터 | **ECS Fargate** | 앱은 스테이트리스 2개(api·web) + 매니지드 데이터뿐 → K8s는 오버엔지니어링. 서버리스 컨테이너로 충분하고 클라우드 신호도 확보. |
| Kubernetes | **매니페스트/Helm + 로컬 kind 검증 + 문서**(라이브 X) | 라이브 EKS 없이 K8s 리터러시 키워드 확보. 면접에선 "모놀리스라 ECS 적정, EKS 이관 경로 문서화"로 판단력 어필. |
| Kafka | **MSK**(매니지드) | 인지도 큰 매니지드 Kafka로 DLQ/이벤트 백본(ADR-008) 신호 격상. (Serverless vs provisioned는 §5에서 확정) |
| DB/Redis | **RDS PostgreSQL · ElastiCache Redis** | 매니지드로 운영 신호 + 안정성. |
| IaC | **Terraform** | 전 인프라 코드화(신호 + 재현성). |
| CI/CD | **GitHub Actions → ECR → ECS**(OIDC) | 장기 액세스키 없이 OIDC 롤로 배포 자동화. |
| 비밀 | **Secrets Manager** + env only | 프로젝트 규칙(비밀은 env로만) 유지. |

> 비용은 이 프로젝트의 우선순위가 아님(취업 목적). 단 인지 목적의 대략치는 §9.

## 1. 타깃 아키텍처

```
GitHub Actions ──(빌드·하네스·테스트)──> ECR (api 이미지 / web 이미지)
        │
        └──(OIDC 롤 / 배포)──> ECS Fargate (ap-northeast-2)
                                  ├─ api 서비스 ── ALB(HTTPS, ACM) ──┐
                                  ├─ web 서비스 ─────────────────────┴─ 도메인
                                  ├─ RDS PostgreSQL (private)
                                  ├─ ElastiCache Redis (private)
                                  └─ MSK (매니지드 Kafka, private)
   시크릿: Secrets Manager   로그·메트릭: CloudWatch   오토스케일: ECS Service Auto Scaling
```

- api는 Flyway로 부팅 시 RDS 마이그레이션 적용(ddl-auto=validate 유지).
- web(Next.js)은 standalone 이미지. ALB가 경로/호스트로 web·api 라우팅.
- MSK/RDS/ElastiCache는 프라이빗 서브넷, api 태스크 SG만 접근.

## 2. 단계(Phase)와 산출물

각 단계는 가능한 로컬 검증 후 진행. **[내]=코드/IaC 작성, [너]=AWS 계정 액션·과금·승인.**

### Phase 1 — 컨테이너화 (AWS 비용 0, 먼저 시작 가능)
- [내] `apps/api/Dockerfile`(멀티스테이지: gradle 빌드 → JRE 런타임, non-root, JAVA_OPTS, healthcheck)
- [내] `apps/web/Dockerfile`(`output: standalone`)
- [내] `.dockerignore`, 로컬 `docker-compose.prod.yml`로 프로덕션 이미지 기동 검증
- 산출물: 두 프로덕션 이미지 로컬 부팅 확인

### Phase 2 — ECR + CI 이미지 파이프라인
- [너] GitHub OIDC ↔ AWS 롤 신뢰관계 생성(권한: ECR push, 이후 ECS deploy)
- [내] GitHub Actions: 기존 하네스/테스트 뒤에 이미지 빌드 → ECR push(태그=git SHA)
- 산출물: main 머지 시 ECR에 이미지 자동 적재

### Phase 3 — IaC (Terraform, 핵심)
- [내] VPC(퍼블릭/프라이빗) · ECR · RDS · ElastiCache · MSK · ECS(cluster/taskdef/service) · ALB · ACM · Secrets Manager · IAM(실행/태스크 롤, MSK 접근) · CloudWatch 로그그룹 · Service Auto Scaling
- [너] `terraform apply`(과금 발생) · tfstate 백엔드(S3+DynamoDB) 부트스트랩
- 산출물: 전체 인프라 기동

### Phase 4 — 앱 프로덕션 설정 (소량 코드 변경 불가피)
- [내] prod 프로필: datasource/redis/`KAFKA_BOOTSTRAP_SERVERS`=MSK 엔드포인트
- [내] **MSK IAM 인증**: `build.gradle`에 `aws-msk-iam-auth` 의존성 + Kafka SASL 설정
- [내] Flyway 마이그레이션이 RDS에 적용되는지 확인(ddl-auto=validate)
- [내] 시크릿 전량 Secrets Manager → 태스크 정의 secrets로 주입
- 산출물: 컨테이너가 매니지드 서비스에 실연결

### Phase 5 — HTTPS·도메인·배포·스모크
- [너] 도메인 확보(Route53 등록/이관) · ACM 인증서 DNS 검증
- [내] ALB 리스너(443) + 라우팅, 배포 스크립트
- [내/너] 배포 → `/actuator/health` 확인 → 관리자 로그인 → (선택) 배포 URL 대상 admin E2E 1회
- 산출물: 공개 HTTPS 데모 URL

### Phase 6 — 관측성
- [내] CloudWatch 대시보드/Log Insights, (선택) actuator/prometheus + Grafana(Amazon Managed Grafana 또는 컨테이너)
- 산출물: 지표/로그 대시보드 + 캡처를 노션/README에 박제

### Phase 7 — K8s 아티팩트 (스킬 신호, 라이브 X)
- [내] `k8s/` Helm 차트: api/web Deployment(+HPA·liveness/readiness probe), Service, Ingress, ConfigMap/Secret
- [내] 로컬 kind/minikube 기동 검증 + README "EKS 이관 절차"
- 산출물: K8s 매니페스트 + 로컬 검증 로그 + 문서

### Phase 8 — S08 부하테스트 (배포 위에서)
- [내] k6를 배포 URL 대상 → RPS/p95 곡선 + ECS Service Auto Scaling 확장 실증
- [내] IMP 문서(로컬=머신 바운드 → 클라우드 재측정) + 캡처
- 산출물: 의미 있는 부하 수치 + 오토스케일 데모

## 3. 착수 전 확정할 것

1. **리전**: 기본 `ap-northeast-2`(서울)?
2. **도메인**: HTTPS(ACM)에 필요. Route53 신규 구입 vs 보유 도메인? (ALB 기본 DNS로는 신뢰 인증서 불가)
3. **MSK**: Serverless(운영 단순, 앱 IAM 인증) vs provisioned(브로커 관리). 기본은 Serverless 권장.
4. **GitHub OIDC**: 액세스키 대신 OIDC 롤 사용(권장).

## 4. 역할 분담 & 안전

- **내가**: Dockerfile·CI 워크플로우·Terraform·Helm·prod 설정·문서 전부 repo 코드로.
- **네가**: AWS 계정/자격증명, OIDC 롤·tfstate 부트스트랩, `terraform apply`(과금), 도메인 구입, 비용 승인.
- 나는 **자격증명 입력·유료 리소스 프로비저닝을 대신 하지 않음**. 명령/코드를 주고 네가 실행, 로그로 함께 디버깅.
- 백엔드 통합/컴파일 검증은 로컬 gradle 없음 → CI(Testcontainers).

## 5. 앱에 생길 변경(실행 단계에서)

- `apps/api/Dockerfile`, `apps/web/Dockerfile`, `.dockerignore`, `docker-compose.prod.yml`
- `build.gradle.kts`: `aws-msk-iam-auth`(MSK IAM) 추가
- prod 프로필 설정(env/Secrets 주입 전제) — 값은 코드에 넣지 않음
- `.github/workflows/`: 이미지 빌드/푸시/배포 잡
- `infra/terraform/**`, `k8s/**` 신규
- (선택) actuator prometheus 노출 조정

## 6. 리스크 & 정직한 한계

- **여러 세션짜리 큰 작업.** terraform/AWS 실행·검증은 네 계정에서 네가 수행.
- **MSK가 가장 까다로움**: IAM 인증 설정·네트워킹(프라이빗 서브넷) 손이 감.
- **HTTPS엔 도메인 필수** — 도메인 없으면 신뢰 인증서 불가(자체 서명은 데모로 부적합).
- 라이브 EKS는 안 함(오버엔지니어링) — K8s는 매니페스트+로컬 검증+문서로만.
- 비용은 우선순위 아니나, 안 쓸 땐 서비스 desired-count=0으로 내려 절약 가능.

## 7. 비용 대략치(인지용, 상시 가동 기준)

Fargate(소형 2서비스) + RDS(db.t4g.micro) + ElastiCache(cache.t4g.micro) ≈ 월 $40~70.
MSK는 별도(Serverless는 시간당+최소요금, provisioned는 브로커 시간). 데모 후 내려두면 절감.

## 8. 완료 정의(DoD)

- [ ] 공개 HTTPS URL에서 홈→예매→결제(Mock/Toss)·관리자 콘솔 동작
- [ ] main 머지 시 이미지 빌드→ECR→ECS 자동 배포
- [ ] Terraform으로 전 인프라 재현 가능
- [ ] CloudWatch(또는 Grafana) 지표/로그 대시보드
- [ ] `k8s/` 매니페스트 + 로컬 kind 검증 + 이관 문서
- [ ] 배포 URL 대상 k6 부하 수치 + 오토스케일 실증(S08)
