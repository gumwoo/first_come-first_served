#!/usr/bin/env bash
# 클러스터 철거 — terraform이 모르는 리소스를 먼저 치우고, 마지막에 잔여를 점검한다.
#
# 왜 스크립트인가: `terraform-design.md` §6에 순서가 있는데도 사람이 밟으면 빠뜨린다.
#   * 2026-08-21: Ingress를 먼저 지우지 않아 VPC 삭제가 막혀 destroy를 **3번** 돌렸다
#   * 2026-08-16: §6-4(EBS 잔여 확인)를 건너뛰어 PVC 볼륨 **50GB가 6일간 방치 과금**됐다
#     (2026-08-22 철거 때 발견해 삭제)
# 두 사고 모두 "문서에 적혀 있는데 안 밟은 것"이다. 밟는 주체를 사람에서 스크립트로 옮긴다.
#
# ⚠️ bootstrap(ECR·ACM·Route53·tfstate·IAM)은 **건드리지 않는다**(§6). 재생성 비용이 크고
# 도메인·인증서는 클러스터 수명과 무관하다.
#
# 사용:
#   bash scripts/tear-down.sh              # 전체 철거
#   bash scripts/tear-down.sh --audit-only # 아무것도 지우지 않고 잔여만 점검
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
TFDIR="$ROOT/infra/terraform/platform/environments/demo"
REGION="${AWS_REGION:-ap-northeast-2}"
CLUSTER="flowticket"
AUDIT_ONLY=0
[ "${1:-}" = "--audit-only" ] && AUDIT_ONLY=1

tf() { terraform -chdir="$TFDIR" "$@"; }
have_cluster() { kubectl get nodes >/dev/null 2>&1; }

audit() {
  echo "==> 잔여 점검 ($REGION)"
  local fail=0
  chk() { # 이름, 개수
    printf "    %-24s %s\n" "$1" "$2"
    [ "${2:-0}" != "0" ] && fail=1
    return 0
  }
  chk "ALB/NLB"        "$(aws elbv2 describe-load-balancers --query 'length(LoadBalancers)' --output text 2>/dev/null)"
  chk "Target Group"   "$(aws elbv2 describe-target-groups --query 'length(TargetGroups)' --output text 2>/dev/null)"
  chk "NAT Gateway"    "$(aws ec2 describe-nat-gateways --filter Name=state,Values=available,pending --query 'length(NatGateways)' --output text 2>/dev/null)"
  chk "Elastic IP"     "$(aws ec2 describe-addresses --query 'length(Addresses)' --output text 2>/dev/null)"
  chk "EBS(available)" "$(aws ec2 describe-volumes --filters Name=status,Values=available --query 'length(Volumes)' --output text 2>/dev/null)"
  chk "EBS(in-use)"    "$(aws ec2 describe-volumes --filters Name=status,Values=in-use --query 'length(Volumes)' --output text 2>/dev/null)"
  chk "EKS 클러스터"    "$(aws eks list-clusters --query 'length(clusters)' --output text 2>/dev/null)"
  chk "RDS"            "$(aws rds describe-db-instances --query 'length(DBInstances)' --output text 2>/dev/null)"
  chk "ElastiCache"    "$(aws elasticache describe-cache-clusters --query 'length(CacheClusters)' --output text 2>/dev/null)"
  chk "EC2(실행중)"     "$(aws ec2 describe-instances --filters Name=instance-state-name,Values=running,pending --query 'length(Reservations[])' --output text 2>/dev/null)"
  chk "VPC(기본 제외)"  "$(aws ec2 describe-vpcs --filters Name=isDefault,Values=false --query 'length(Vpcs)' --output text 2>/dev/null)"
  return $fail
}

if [ "$AUDIT_ONLY" -eq 1 ]; then
  audit; exit $?
fi

echo "==> 0/6 ArgoCD Application 삭제"
# 먼저 지우지 않으면 아래에서 지운 워크로드를 ArgoCD가 **되살린다**(selfHeal).
if have_cluster; then
  kubectl delete application flowticket -n argocd --timeout=180s 2>/dev/null || true
else
  echo "    클러스터에 접근할 수 없다 — k8s 단계를 건너뛴다"
fi

if have_cluster; then
  echo "==> 1/6 Ingress 삭제 → ALB 제거"
  # ALB는 Terraform이 모르는 리소스라, 남으면 **VPC 삭제가 막힌다**(2026-08-21에 겪음).
  kubectl delete ingress --all -A --timeout=180s 2>/dev/null || true
  for i in $(seq 1 40); do
    n="$(aws elbv2 describe-load-balancers --query "length(LoadBalancers[?contains(DNSName,'k8s-')])" --output text 2>/dev/null | tr -d '\r')"
    [ "${n:-0}" = "0" ] && { echo "    ALB 제거 확인"; break; }
    sleep 15
  done

  echo "==> 2/6 Kafka CR 삭제 (StatefulSet PVC는 자동 삭제되지 않는다)"
  kubectl delete kafka --all -n kafka --timeout=180s 2>/dev/null || true
  kubectl delete kafkanodepool --all -n kafka --timeout=120s 2>/dev/null || true

  echo "==> 3/6 PVC 소유자 해제 후 PVC 삭제"
  # Prometheus가 PVC를 잡고 있으면 삭제가 타임아웃된다 — helm 릴리스를 먼저 내린다.
  helm uninstall kube-prometheus-stack -n monitoring --timeout 5m >/dev/null 2>&1 || true
  sleep 15
  kubectl delete pvc --all -A --timeout=180s 2>/dev/null || true
fi

echo "==> 4/6 고아 EBS 볼륨 정리"
# ⚠️ 2026-08-16 철거에서 이 단계를 건너뛰어 50GB가 6일간 과금됐다.
# 대상은 **쿠버네티스가 만든 것**으로 한정한다 — 무관한 볼륨을 지우지 않기 위해서다.
ORPHANS="$(aws ec2 describe-volumes --filters Name=status,Values=available \
  --query "Volumes[?Tags[?Key=='kubernetes.io/created-for/pvc/name']].VolumeId" --output text 2>/dev/null | tr '\t' '\n' | tr -d '\r')"
if [ -z "$ORPHANS" ]; then
  echo "    없음"
else
  for v in $ORPHANS; do
    [ -z "$v" ] && continue
    aws ec2 delete-volume --volume-id "$v" >/dev/null 2>&1 && echo "    삭제 $v"
  done
fi

echo "==> 5/6 terraform destroy"
if [ "$(tf state list 2>/dev/null | wc -l)" -eq 0 ]; then
  echo "    state가 비어 있다 — 건너뛴다"
else
  # ⚠️ 파이프를 쓰지 않는다. `| tail` 을 붙이면 종료 코드가 tail의 것이 되어
  # **실패한 destroy가 성공으로 보인다**(2026-08-16에 실제로 겪음).
  tf destroy -auto-approve -input=false
  rc=$?
  echo "    terraform 종료 코드: $rc"
  [ $rc -ne 0 ] && { echo "destroy가 실패했다 — 위 오류를 보고 잔여를 직접 정리하라" >&2; audit; exit 1; }
fi

echo "==> 6/6"
if audit; then
  echo
  echo "완료. 잔여 없음."
else
  echo
  echo "⚠️ 위에 0이 아닌 항목이 있다. 과금이 계속되므로 직접 확인하라." >&2
  exit 1
fi
