# platform — 휘발 계층 (VPC · EKS · RDS · ElastiCache)

이 스택은 **apply/destroy를 반복하는 계층**이다. 설계 근거는
[ADR-012](../../../docs/decisions/ADR-012-3az-eks-infra-cost-control.md),
구조는 [terraform-design.md](../../../docs/deployment/terraform-design.md).

> ⚠️ **`apply`는 상시 과금의 시작점이다.** 아래 사전 조건을 먼저 확인한다.

## 사전 조건

- [x] 서비스 쿼터 — vCPU 32(필요 12), EIP 5(필요 3) 확인 완료
- [x] Budgets 4단계 + Cost Anomaly Detection 설정 완료
- [ ] `state-bootstrap`으로 state용 S3 버킷 생성 → `versions.tf`의 backend 주석 해제
- [ ] `bootstrap` apply(ACM·ECR·IAM) — **이 스택과 별개이며 아직 미작성**
- [ ] `modules/eks/policies/aws-load-balancer-controller.json` 내려받기
      ([policies/README.md](modules/eks/policies/README.md))

## 쓰는 법

```bash
cd infra/terraform/platform/environments/demo
terraform init
terraform plan          # 반드시 눈으로 검토
terraform apply         # 과금 시작
```

apply 후:

```bash
aws eks update-kubeconfig --region ap-northeast-2 --name flowticket
```

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
| `rds` | PostgreSQL, 프라이빗 | 비밀번호는 Secrets Manager에만, output은 **ARN만** |
| `elasticache` | Redis, 프라이빗 | 대기열·토큰·SSE 팬아웃용 |

## 이 스택이 만들지 않는 것

**ArgoCD가 관리한다**(ADR-009): Strimzi Operator, Kafka CR, 앱(api/web), 관측 스택.
**부트스트랩 Helm으로 별도 설치**: AWS Load Balancer Controller, Cluster Autoscaler, ArgoCD 자신.
IRSA 역할 ARN은 `terraform output irsa_role_arns`로 얻어 서비스 어카운트에 annotate한다.
