# Terraform 구현 설계 — 3AZ EKS 데모 인프라

> 본 문서는 [ADR-012](../decisions/ADR-012-3az-eks-infra-cost-control.md)에서 확정한 인프라 결정을
> **Terraform 구현 구조로 구체화**한다. "왜 그렇게 정했는가"는 ADR-012에, "어떻게 만드는가"는 여기에 둔다.

- 상태: 설계(코드 미작성)
- 관련: [ADR-009](../decisions/ADR-009-gitops-cd-argocd.md) GitOps CD,
  [aws-eks-deploy-plan.md](aws-eks-deploy-plan.md) Phase 3,
  [app-changes-for-k8s-kafka.md](app-changes-for-k8s-kafka.md)

---

## 1. 디렉터리 구조와 state 분리

```
infra/terraform/
├─ state-bootstrap/          # S3 버킷 + 잠금 (local state, 1회)
├─ bootstrap/                # 영속 — destroy 대상 아님
│  ├─ route53.tf             #   Hosted Zone
│  ├─ acm.tf                 #   와일드카드 인증서 + DNS 검증
│  ├─ ecr.tf                 #   flowticket-api / flowticket-web
│  ├─ iam.tf                 #   GitHub OIDC provider + Actions 롤
│  └─ outputs.tf
└─ platform/                 # 휘발 — apply/destroy 반복
   ├─ modules/
   │  ├─ vpc/                #   서브넷·라우팅·NAT·엔드포인트
   │  ├─ eks/                #   클러스터·노드그룹·애드온·IRSA
   │  ├─ rds/
   │  ├─ elasticache/
   │  └─ endpoints/
   └─ environments/demo/     #   변수 조합 (유일한 환경)
```

**왜 나누나**: `platform destroy`가 도메인·인증서·이미지까지 지우면 다시 켤 때마다 DNS 검증을
새로 기다려야 한다. bootstrap은 **연간 $15 수준**이라 남겨두는 편이 압도적으로 싸다.

### state 백엔드
| 계층 | 백엔드 | 이유 |
|---|---|---|
| `state-bootstrap` | local | 닭과 달걀 — 버킷 자체를 만드는 단계 |
| `bootstrap` | S3 | 영속 리소스라 유실 위험을 줄임 |
| `platform` | S3 | apply/destroy를 반복하므로 state 유실이 치명적 |

> S3 네이티브 잠금(`use_lockfile`) 사용 여부는 **작성 시점의 Terraform 버전으로 확인**한다.
> 지원되지 않으면 DynamoDB 잠금 테이블을 둔다.

### 계층 간 연결
`platform`이 `terraform_remote_state`로 `bootstrap`의 출력을 읽는다.

```
bootstrap outputs
  hosted_zone_id
  certificate_arn
  ecr_api_repository_url
  ecr_web_repository_url
```

⚠️ **민감값은 remote state output으로 넘기지 않는다.** DB 비밀번호 등은
Secrets Manager / SSM Parameter Store에 두고 **ARN만** 전달한다
(state 파일은 평문이며 프로젝트 규칙상 비밀은 환경변수·시크릿 저장소로만).

---

## 2. 네트워크 계획

### CIDR
| 용도 | AZ-a | AZ-b | AZ-c | 비고 |
|---|---|---|---|---|
| VPC | `10.0.0.0/16` | | | |
| Public | `10.0.0.0/24` | `10.0.1.0/24` | `10.0.2.0/24` | ALB, NAT |
| Private **Data** | `10.0.16.0/24` | `10.0.17.0/24` | `10.0.18.0/24` | RDS, ElastiCache |
| Private **App** | `10.0.32.0/19` | `10.0.64.0/19` | `10.0.96.0/19` | EKS 노드 + **Pod IP** |

> App 서브넷만 `/19`(약 8,190 IP)인 이유: **VPC CNI가 Pod에 서브넷 IP를 직접 할당**한다.
> `/24`면 노드 몇 대에 Pod가 몇십 개만 떠도 IP가 고갈된다.
> `10.0.128.0/17`은 확장용으로 비워 둔다.

### 라우팅 — AZ별 NAT
```
Public RT (공용 1개)        → 0.0.0.0/0 → Internet Gateway
Private App RT-a           → 0.0.0.0/0 → NAT-a   (AZ-a에 위치)
Private App RT-b           → 0.0.0.0/0 → NAT-b
Private App RT-c           → 0.0.0.0/0 → NAT-c
Private Data RT (3개)      → 기본 라우트 없음 (VPC 내부만)
```
**같은 AZ의 NAT로 내보내야** 한다. 교차 AZ로 라우팅하면 AZ 격리도 깨지고 데이터 전송료도 붙는다.

### VPC 엔드포인트
| 엔드포인트 | 유형 | 우선순위 | 비고 |
|---|---|---|---|
| S3 | Gateway | **1차** | **시간당 요금 없음.** ECR 이미지 레이어가 S3 경로를 씀 |
| ECR API | Interface | **1차** | |
| ECR DKR | Interface | **1차** | |
| CloudWatch Logs | Interface | 2차 | 필요 시 |
| Secrets Manager | Interface | 2차 | 필요 시 |
| STS | Interface | 2차 | 필요 시 |

Interface 엔드포인트는 **AZ마다 ENI가 생기고 시간당 과금**된다(3개 서비스 × 3 AZ = ENI 9개).
NAT가 3개 있으므로 **없어도 동작한다** — 필수가 아니라 AWS 트래픽을 NAT에서 분리하는 개선이다.
Interface 엔드포인트는 프라이빗 DNS가 켜져 있어야 하므로 VPC의 `enable_dns_hostnames`/
`enable_dns_support`를 함께 확인한다.

---

## 3. EKS

### 노드그룹 (AZ별 분리)
```hcl
# 개념 — 실제 코드는 for_each 루프
node_groups = {
  az-a = { subnet = private_app_a, min = 1, desired = 1, max = 2 }
  az-b = { subnet = private_app_b, min = 1, desired = 1, max = 2 }
  az-c = { subnet = private_app_c, min = 1, desired = 1, max = 2 }
}
```

### 변수 — 인스턴스 계열 전환
```
node_instance_type = "t3.large"     # 기본: 기능·배포·페일오버 검증
                   = "m6i.large"    # 부하테스트(S10): 비버스터블
```
ADR-012 §5 참조. **부하 측정 시 반드시 비버스터블로 바꾼 뒤 측정한다.**

### 애드온 / 컨트롤러
| 구성요소 | 설치 주체 | 비고 |
|---|---|---|
| VPC CNI, CoreDNS, kube-proxy | EKS 애드온(Terraform) | |
| **EBS CSI Driver** | EKS 애드온 + IRSA | StorageClass의 전제 |
| **AWS Load Balancer Controller** | Helm(부트스트랩) + IRSA | Ingress → ALB |
| **Cluster Autoscaler** | Helm(부트스트랩) + IRSA | 노드그룹 ASG 태그 필요 |
| ArgoCD | Helm(1회 부트스트랩) | 이후 앱은 ArgoCD가 관리 — ADR-009 |
| Strimzi Operator, 앱, 관측 | **ArgoCD** | Terraform이 아님 |

> 경계 규칙(ADR-009): **인프라 = Terraform / 앱 = ArgoCD.**
> ArgoCD 자신의 설치만 부트스트랩으로 예외 처리한다.

### StorageClass
```yaml
provisioner: ebs.csi.aws.com
volumeBindingMode: WaitForFirstConsumer   # ← AZ 불일치 방지 (ADR-012 §7)
reclaimPolicy: Delete
allowVolumeExpansion: true
parameters: { type: gp3, encrypted: "true" }
```

두 설정의 역할이 다르다 — **둘 다 있어야** 브로커가 노드 장애 후 같은 AZ에서 복구된다.
```
WaitForFirstConsumer  → 최초 볼륨 생성 AZ를 Pod 스케줄링 위치에 맞춤
AZ별 노드그룹          → 노드가 죽어도 대체 노드가 같은 AZ에 뜸
```

---

## 4. 리소스 예산표

`t3.large` × 3 = **6 vCPU / 24 GiB**. 노드당 allocatable은 시스템 예약을 빼면 대략
**1.7 vCPU / 6.5~7.0 GiB** 수준이다 → 클러스터 합계 약 **5.1 vCPU / 20 GiB**.

> ⚠️ allocatable 값은 추정이다. 클러스터 기동 후 `kubectl describe node`로 확인해 이 표를 갱신한다.

| 워크로드 | 개수 | CPU req | Mem req | Mem limit |
|---|---|---|---|---|
| Kafka broker (Strimzi) | 3 | 500m | 1Gi | 2Gi |
| API | 2 (HPA 2→6) | 500m | 768Mi | 1.5Gi |
| Web | 2 | 100m | 256Mi | 512Mi |
| Prometheus | 1 | 200m | 1Gi | 2Gi |
| Grafana | 1 | 100m | 128Mi | 256Mi |
| ArgoCD (전체) | – | 250m | 512Mi | 1Gi |
| Strimzi Operator | 1 | 100m | 256Mi | 512Mi |
| AWS LB Controller | 1 | 50m | 128Mi | 256Mi |
| EBS CSI / Cluster Autoscaler | – | 100m | 256Mi | 512Mi |
| CoreDNS ×2 + DaemonSet | – | 250m | 340Mi | – |
| **평시 합계(requests)** | | **약 3.4 vCPU** | **약 7.6 GiB** | |
| **HPA 최대(API 6 Pod)** | | **약 5.4 vCPU** | **약 9.9 GiB** | |

**해석**: 메모리는 여유가 있으나 **CPU가 먼저 한계에 닿는다.** 평시 3.4 / 가용 5.1이라
HPA가 API를 6 Pod까지 늘리면 5.4로 **초과** → **Cluster Autoscaler가 4번째 노드를 붙이는 구간**이
실제로 발생한다. 이것이 ADR-012 §4의 근거이자, S10에서 측정할 대상이다.

**스케줄러는 `limit`이 아니라 `request`로 배치**하므로, request를 너무 크게 잡으면 Pending이,
너무 작게 잡으면 노드 메모리 압박으로 OOMKill이 난다. 위 값은 **초기 예산**이며
`kubectl top` 실측으로 조정한다.

### Kafka 배치 (rack awareness ≠ Pod 분산)
```yaml
rack:
  topologyKey: topology.kubernetes.io/zone     # Kafka에 broker.rack 제공
template:
  pod:
    topologySpreadConstraints:                 # Pod를 AZ별 1개씩 강제
      - maxSkew: 1
        topologyKey: topology.kubernetes.io/zone
        whenUnsatisfiable: DoNotSchedule
        labelSelector: { matchLabels: { strimzi.io/name: flowticket-kafka-kafka } }
```
**rack awareness만으로는 브로커 Pod가 AZ에 균등 분산되지 않는다** — 둘은 역할이 다르다(ADR-012 §6).
설정 문법은 작성 시점의 Strimzi 공식 문서로 확인한 뒤 반영한다.

### CA와 상태 저장 워크로드
- Strimzi가 만드는 **PodDisruptionBudget** 확인 — 한 번에 브로커 1개만 내려가야 한다.
- 브로커 Pod에 `cluster-autoscaler.kubernetes.io/safe-to-evict: "false"` 적용을 검토한다.
- 없으면 CA scale-down이 조용히 브로커를 옮겨 **페일오버 실험 결과가 오염**된다.

---

## 5. apply 순서

```
0. 쿼터·Budgets 확인 (§7)
1. state-bootstrap   S3 버킷 (+ 필요 시 잠금 테이블)
2. bootstrap         Route53 → 도메인 NS 연결 → ACM 요청 → DNS 검증 대기 → ECR·IAM
3. platform          VPC → 엔드포인트 → EKS → 노드그룹 → 애드온 → RDS → ElastiCache
4. 부트스트랩 Helm    LB Controller → Cluster Autoscaler → ArgoCD
5. ArgoCD            Strimzi Operator → Kafka CR → 앱(api/web) → 관측
6. 검증              Ingress ALB 생성 확인 → HTTPS → /actuator/health/readiness
```

`terraform plan` 결과를 반드시 검토한 뒤 `apply`한다. **2단계의 ACM DNS 검증은 대기 시간이 있다.**

## 6. destroy 순서 ⚠️

**순서를 지키지 않으면 리소스가 남아 계속 과금된다.**

```
1. kubectl delete ingress --all        # ALB 제거. 안 하면 VPC 삭제가 막힌다
                                       #   (ALB는 Terraform이 모르는 리소스)
2. Kafka CR 삭제 → PVC 삭제 확인        # StatefulSet PVC는 자동 삭제되지 않는다
3. 나머지 Kubernetes 리소스 삭제
4. EBS 볼륨 잔여 확인 (콘솔/CLI)        # 남으면 GB-월 과금 지속
5. terraform destroy (platform)
6. 잔여 점검: ALB · NAT · Elastic IP · EBS · 스냅샷
```

- **bootstrap은 destroy하지 않는다.**
- RDS는 데모용이므로 `skip_final_snapshot = true` (스냅샷도 저장 비용이 붙는다).
- 데이터 유실은 문제가 되지 않는다 — Flyway 마이그레이션, KOPIS 동기화, 좌석 자동 시딩,
  Kafka 토픽 자동 생성이 모두 **기동 시 스스로 채워지는** 구조다.

## 7. 사전 점검표

### 서비스 쿼터 (⏰ 증액은 승인 대기가 있으므로 **가장 먼저**)
| 항목 | 필요 | 비고 |
|---|---|---|
| **On-Demand Standard vCPU** | **≥ 12** (여유 16) | `t3.large` × 최대 6노드 = 12 vCPU. 신규 계정은 기본값이 낮다 |
| Elastic IP | 3 | NAT 3개. 기본 5라 빠듯 |
| VPC | 1 | |
| EKS 클러스터 / ALB / RDS / ElastiCache | 각 1 | |

### 그 외
- [ ] AWS Budgets **$5 / $20 / $50** + Cost Anomaly Detection
- [ ] 도메인 구매 → Route53 Hosted Zone
- [ ] `m6i.large`(또는 대체 비버스터블)의 `ap-northeast-2` 가용 여부
- [ ] ECR에 이미지 존재 확인 (Phase 2에서 완료 — SHA 태그)
- [ ] AWS CLI 계정·리전 확인

## 8. 실증 시나리오 ↔ 구성 매핑

| 실증 항목 | 요구 구성 | 관찰 지표 |
|---|---|---|
| **Kafka 브로커 장애 복구** | 3 AZ, 브로커 AZ별 1개, RF=3, min.insync=2 | 브로커 1대 종료 → 리더 재선출, 프로듀싱·컨슘 지속, consumer lag·복구 시간 |
| **HPA 수평 확장** | CA(max 6노드), API HPA 2→6 | Pod 증가 곡선, **노드 프로비저닝 지연 구간의 p95**, Pending 해소 시각 |
| **다중 Pod SSE 팬아웃** | API ≥2 Pod | 연결 Pod ≠ 소비 Pod 상황에서 알림 도달률 |
| **스케줄러 중복 억제** | API ≥2 Pod, ShedLock | 스윕 실행 로그가 Pod 1개에서만 |
| **ArgoCD self-heal** | ArgoCD `selfHeal + prune` | 수동 드리프트 주입 → 자동 복구, Synced/Healthy |
| **초과판매 방지** | 부하 + 조건부 UPDATE(ADR-003) | 판매 수량 = 재고, 중복 0 |
| **노드 장애 후 브로커 복구** | AZ별 노드그룹 + WaitForFirstConsumer | 노드 종료 → 같은 AZ 대체 노드 → EBS 재연결 |

**실증 범위 밖(명시)**: AZ 전체 장애, 리전 장애, NAT 장애 주입, 침투 테스트.
이들은 ADR-012의 분류 **B**(기본 운영 안전장치)에 해당해 **근거 문서로 대신**한다.

## 9. 한계

- **코드 미작성.** 이 문서는 설계이며, 실제 값(allocatable, 인스턴스 가용성, Strimzi 문법)은
  구현·기동 시점에 확인해 갱신한다.
- **단일 환경(demo)** 만 둔다. staging/prod 분리는 이 규모에서 과설계다.
- **비용 수치는 근사치**이며 실제 청구액으로 갱신한다.
- **Terraform 코드가 곧 문서와 일치한다는 보장은 없다.** 코드 작성 후 이 문서를 실제 구조에
  맞춰 갱신하는 것을 PR 체크리스트에 포함한다.
