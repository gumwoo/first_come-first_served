# platform — 휘발 계층 (VPC · EKS · RDS · ElastiCache)

이 스택은 **apply/destroy를 반복하는 계층**이다. 설계 근거는
[ADR-012](../../../docs/decisions/ADR-012-3az-eks-infra-cost-control.md),
구조는 [terraform-design.md](../../../docs/deployment/terraform-design.md).

> ⚠️ **`apply`는 상시 과금의 시작점이다.** 아래 사전 조건을 먼저 확인한다.

## 사전 조건

- [x] 서비스 쿼터 — vCPU 32(필요 12), EIP 5(필요 3) 확인 완료
- [x] Budgets 4단계 + Cost Anomaly Detection 설정 완료
- [x] `state-bootstrap`으로 state용 S3 버킷 생성 → `versions.tf`의 backend 주석 해제
      (버킷 `flowticket-tfstate-891612580915`, `environments/demo/versions.tf`의 `backend "s3"` 활성)
- [x] `bootstrap` apply(ACM·ECR·IAM) — **이 스택과 별개다.** 작성·apply 모두 완료
      (`infra/terraform/bootstrap/`, state 15개. 가장 최근 apply는 2026-08-25 ECR 라이프사이클)
- [x] LB Controller IAM 정책 — 저장소에 커밋됨
      ([policies/README.md](modules/eks/policies/README.md))

## 쓰는 법

```bash
cd infra/terraform/platform/environments/demo

# 1) 관리자 IP 지정 — cluster_public_access_cidrs 에 기본값이 없다(의도).
cp terraform.tfvars.example terraform.tfvars
curl -s https://checkip.amazonaws.com      # 여기서 나온 IP를 /32 로 넣는다

# 2) EKS 버전이 아직 지원 중인지 확인 — 이 값은 시간이 지나면 낡는다.
aws eks describe-cluster-versions --region ap-northeast-2
#    2026-08-06 실측: 1.36→2027-08-02 / 1.35→2027-03-27 / 1.34→2026-12-02 / 1.33 만료
#    애드온도 같은 버전에서 지원되는지 확인한다.
aws eks describe-addon-versions --kubernetes-version 1.36 --region ap-northeast-2   --addon-name vpc-cni       # coredns · kube-proxy · aws-ebs-csi-driver 도 각각

terraform init
terraform plan          # 반드시 눈으로 검토
terraform apply         # 과금 시작
```

> `0.0.0.0/0`은 validation으로 거부된다. "프라이빗 서브넷·최소 권한"을 설계 근거로 내세우면서
> API를 전 세계에 열어두면 문서와 코드가 충돌하기 때문이다.

apply 후:

```bash
aws eks update-kubeconfig --region ap-northeast-2 --name flowticket
```

### ⚠️ apply 직후 반드시 닫아야 하는 것 — Redis TLS

이 스택은 ElastiCache를 **전송 중 암호화(TLS) 켜진 상태로** 만든다.
**앱이 평문으로 붙으면 Redis 연결이 전부 실패하고, readiness에 `redis`가 들어 있어
Pod가 영원히 Ready가 되지 않는다.**

`k8s/` 매니페스트에 `REDIS_SSL_ENABLED=true`를 넣어야 한다(앱 yml이 읽는 이름과 한 쌍).
체크리스트: [`docs/deployment/app-changes-for-k8s-kafka.md`](../../../docs/deployment/app-changes-for-k8s-kafka.md) **C-7**.

### 만들어지는 것 중 요금이 남을 수 있는 것

컨트롤플레인 로그 그룹은 **Terraform이 보존 7일로 먼저 만든다.** EKS가 자동 생성하게 두면
보존이 "만료 없음"이라 **클러스터를 지운 뒤에도 로그 저장 요금이 남는다.**

### 봉투 암호화 — 고객 관리형 키를 쓰지 않는다(의도)

EKS는 **Kubernetes 1.28 이상에서 모든 API 데이터를 AWS 소유 KMS 키로 기본 봉투 암호화**한다
(설정·권한·추가 비용 없음). 고객 관리형 키(CMK)가 더 주는 것은 암호화가 아니라 **키 정책·감사·
폐기 통제권**이고, 이 프로젝트에는 그것을 요구하는 근거가 없다. 반면 **CMK가 삭제되면 클러스터는
복구 불가**가 되는데, 이 스택은 데모마다 만들고 지우므로 그 사고 확률이 오히려 높다.
필요해지면 나중에 붙일 수 있다(기존 클러스터에도 연결 가능) — 단 **연결 후에는 해제·교체가 안 된다.**

## 부하테스트 때 인스턴스 바꾸기

`t3.large`는 버스터블이라 지속 부하에서 **CPU 크레딧이 결과에 개입**한다. 그러면 처리량 저하가
코드 때문인지 크레딧 때문인지 구분할 수 없어 측정이 무효가 된다(ADR-012 §5).

```bash
terraform apply -var 'node_instance_type=m6i.large'
```

## destroy — 순서가 있다 ⚠️

```bash
kubectl delete ingress --all --all-namespaces   # ALB 제거. 안 하면 VPC 삭제가 막힌다
kubectl delete kafka --all -n <ns>              # Strimzi CR
kubectl get pvc -A                              # PVC가 남았는지 확인 후 삭제
# EBS 볼륨이 실제로 사라졌는지 콘솔/CLI로 확인
terraform destroy
```

destroy 후 잔여 점검: **ALB · NAT · Elastic IP · EBS · 스냅샷**.
특히 **EIP는 쿼터가 5인데 3을 쓰므로**, 남으면 다음 apply가 `3+3 > 5`로 실패한다
(증상이 쿼터 에러라 원인이 헷갈린다).

## 모듈

| 모듈 | 역할 | 눈여겨볼 점 |
|---|---|---|
| `vpc` | 3 AZ · 서브넷 9개 · NAT ×3 · 라우팅 | App 서브넷이 `/19`인 이유는 **VPC CNI가 Pod에 서브넷 IP를 직접 할당**하기 때문 |
| `endpoints` | S3 Gateway + ECR Interface | 없어도 동작한다 — NAT 우회용 **개선** 항목 |
| `eks` | 클러스터 · **AZ별 노드그룹 3개** · 애드온 · IRSA | 노드그룹 분리는 **EBS의 AZ 종속** 때문 |
| `rds` | PostgreSQL, 프라이빗 | 비밀번호를 **RDS가 관리**한다 — Terraform state에 남지 않는다 |
| `elasticache` | Redis, 프라이빗 | 대기열·토큰·SSE 팬아웃용 |

## 이 스택이 만들지 않는 것

**ArgoCD가 관리한다**(ADR-009): Strimzi Operator, Kafka CR, 앱(api/web), 관측 스택.
**부트스트랩 Helm으로 별도 설치**: AWS Load Balancer Controller, Cluster Autoscaler, ArgoCD 자신.
IRSA 역할 ARN은 `terraform output irsa_role_arns`로 얻어 서비스 어카운트에 annotate한다.
