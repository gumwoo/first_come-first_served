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
# 3단계에서 채집한 "이 클러스터가 소유한" EBS 볼륨 ID. 4단계는 이 목록만 지운다.
OWNED_VOLS="$(mktemp)"
trap 'rm -f "$OWNED_VOLS"' EXIT

tf() { terraform -chdir="$TFDIR" "$@"; }
have_cluster() { kubectl get nodes >/dev/null 2>&1; }

audit() {
  echo "==> 잔여 점검 ($REGION, FlowTicket 소유분만)"
  # ⚠️ 리전 전체를 세면 안 된다. 같은 리전에 무관한 RDS가 하나 생기면 이 스크립트가
  # "철거 실패"라고 말하게 되고, 그러면 의미가 **"FlowTicket 잔여 없음"이 아니라
  # "이 리전에 아무것도 없음"**이 된다.
  #
  # Terraform이 만든 것은 default_tags의 `Project=flowticket`으로 정확히 걸러진다
  # (versions.tf provider 블록). Terraform 밖에서 만들어지는 둘은 이름으로 판별한다:
  #   * ALB  — aws-load-balancer-controller가 `k8s-<ns>-<ingress>-...` 형태로 만든다
  #   * EBS  — EBS CSI 드라이버가 만든다(PVC 태그로 식별)
  local fail=0
  chk() {
    printf "    %-26s %s
" "$1" "${2:-0}"
    [ "${2:-0}" != "0" ] && fail=1
    return 0
  }
  local TAG='Name=tag:Project,Values=flowticket'

  chk "EC2(실행중)"      "$(aws ec2 describe-instances --filters "$TAG" Name=instance-state-name,Values=running,pending --query 'length(Reservations[])' --output text 2>/dev/null)"
  chk "NAT Gateway"      "$(aws ec2 describe-nat-gateways --filter "$TAG" Name=state,Values=available,pending --query 'length(NatGateways)' --output text 2>/dev/null)"
  chk "Elastic IP"       "$(aws ec2 describe-addresses --filters "$TAG" --query 'length(Addresses)' --output text 2>/dev/null)"
  chk "VPC"              "$(aws ec2 describe-vpcs --filters "$TAG" --query 'length(Vpcs)' --output text 2>/dev/null)"
  chk "EBS(전체)"        "$(aws ec2 describe-volumes --filters "$TAG" --query 'length(Volumes)' --output text 2>/dev/null)"
  # PVC 볼륨은 Terraform 밖에서 생겨 Project 태그가 없다 — 별도로 센다.
  chk "EBS(PVC, 미사용)" "$(aws ec2 describe-volumes --filters Name=status,Values=available --query "length(Volumes[?Tags[?Key=='kubernetes.io/created-for/pvc/name']])" --output text 2>/dev/null)"
  chk "EKS 클러스터"      "$(aws eks list-clusters --query "length(clusters[?@=='$CLUSTER'])" --output text 2>/dev/null)"
  chk "RDS"              "$(aws rds describe-db-instances --query "length(DBInstances[?contains(DBInstanceIdentifier,'$CLUSTER')])" --output text 2>/dev/null)"
  chk "ElastiCache"      "$(aws elasticache describe-cache-clusters --query "length(CacheClusters[?contains(CacheClusterId,'$CLUSTER')])" --output text 2>/dev/null)"
  chk "ALB(k8s-*)"       "$(aws elbv2 describe-load-balancers --query "length(LoadBalancers[?starts_with(LoadBalancerName,'k8s-')])" --output text 2>/dev/null)"
  chk "Target Group"     "$(aws elbv2 describe-target-groups --query "length(TargetGroups[?starts_with(TargetGroupName,'k8s-')])" --output text 2>/dev/null)"
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

  echo "==> 3/6 볼륨 ID 채집 → PVC 삭제"
  # ⚠️ **삭제 대상을 여기서 확정한다.** 태그로 고르면 추측이 된다 — EBS 볼륨은 Terraform이
  # 아니라 EBS CSI 드라이버가 만들어 `Project=flowticket` 공통 태그가 붙지 않고,
  # `kubernetes.io/created-for/pvc/name`만으로 고르면 **같은 계정의 다른 클러스터 볼륨까지**
  # 대상이 된다. 이 클러스터의 PV가 실제로 가리키는 ID를 읽어두면 그 문제가 사라진다.
  kubectl get pv -o jsonpath='{range .items[*]}{.spec.csi.volumeHandle}{"
"}{end}' 2>/dev/null     | tr -d '' | grep -E '^vol-' > "$OWNED_VOLS" || true
  echo "    이 클러스터 소유 볼륨 $(grep -c . "$OWNED_VOLS" 2>/dev/null || echo 0)개 기록"

  # Prometheus가 PVC를 잡고 있으면 삭제가 타임아웃된다 — helm 릴리스를 먼저 내린다.
  helm uninstall kube-prometheus-stack -n monitoring --timeout 5m >/dev/null 2>&1 || true
  sleep 15
  kubectl delete pvc --all -A --timeout=180s 2>/dev/null || true
fi

echo "==> 4/6 고아 EBS 볼륨 정리"
# ⚠️ 2026-08-16 철거에서 이 단계를 건너뛰어 50GB가 6일간 과금됐다.
# **3단계에서 채집한 ID만 지운다.** 목록이 없으면(클러스터에 이미 접근 불가) 지우지 않고
# 후보만 보고한다 — 파괴 자동화는 소유를 증명하지 못하면 멈추는 편이 낫다.
if [ ! -s "$OWNED_VOLS" ]; then
  echo "    소유 볼륨 목록이 없다(클러스터 접근 불가). 자동 삭제하지 않는다."
  CAND="$(aws ec2 describe-volumes --filters Name=status,Values=available     --query "Volumes[?Tags[?Key=='kubernetes.io/created-for/pvc/name']].[VolumeId,Size,Tags[?Key=='kubernetes.io/created-for/pvc/name']|[0].Value]"     --output text 2>/dev/null | tr -d '')"
  if [ -n "$CAND" ]; then
    echo "    ⚠️ 쿠버네티스가 만든 미사용 볼륨이 있다. **소유를 확인한 뒤** 직접 지워라:" >&2
    echo "$CAND" | sed 's/^/      /' >&2
  else
    echo "    미사용 볼륨 없음"
  fi
else
  n=0
  while read -r v; do
    [ -z "$v" ] && continue
    st="$(aws ec2 describe-volumes --volume-ids "$v" --query 'Volumes[0].State' --output text 2>/dev/null | tr -d '')"
    case "$st" in
      available) aws ec2 delete-volume --volume-id "$v" >/dev/null 2>&1 && { echo "    삭제 $v"; n=$((n+1)); } ;;
      "") ;;  # 이미 사라짐(PVC 삭제 시 함께 정리된 경우)
      *) echo "    건너뜀 $v (state=$st)" ;;
    esac
  done < "$OWNED_VOLS"
  echo "    삭제 ${n}개"
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
