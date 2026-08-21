#!/usr/bin/env bash
# 클러스터 기동 — terraform apply **이후**의 모든 절차를 한 번에.
#
# 왜 스크립트인가: 이 절차는 지금까지 문서의 명령어 목록이었고, 2026-08-21 기동에서
# 실제로 두 가지가 드러났다.
#   1) helm 인자(IRSA ARN·VPC ID)가 문서에 없어 매번 출력값에서 손으로 채워야 했다
#   2) **ArgoCD helm 설치를 통째로 빠뜨렸다** — Application을 apply할 때 CRD가 없어서야 알았다
# 순서 의존도 사람 기억에 맡겨져 있었다(네임스페이스가 ESO보다 먼저여야 한다는 것 등).
#
# terraform apply는 **일부러 포함하지 않는다.** 비용이 발생하고 되돌리기 어려운 유일한
# 단계라 의식적인 행위로 남긴다(ADR-012 비용 통제). state가 비어 있으면 여기서 멈춘다.
#
# 사용:
#   terraform -chdir=infra/terraform/platform/environments/demo apply
#   bash scripts/bring-up.sh              # 데이터 시딩까지
#   bash scripts/bring-up.sh --no-seed    # 인프라만
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
TFDIR="$ROOT/infra/terraform/platform/environments/demo"
REGION="${AWS_REGION:-ap-northeast-2}"
CLUSTER="flowticket"
SEED=1
[ "${1:-}" = "--no-seed" ] && SEED=0

tf() { terraform -chdir="$TFDIR" "$@"; }

echo "==> 0/7 전제 확인"
for c in terraform kubectl helm aws jq; do
  command -v "$c" >/dev/null || { echo "$c 가 없다" >&2; exit 1; }
done
if [ "$(tf state list 2>/dev/null | wc -l)" -eq 0 ]; then
  echo "terraform state가 비어 있다 — 먼저 apply 하라(비용이 발생하므로 이 스크립트는 하지 않는다):" >&2
  echo "  terraform -chdir=$TFDIR apply" >&2
  exit 1
fi
LBC_ROLE="$(tf output -json irsa_role_arns | jq -r '.load_balancer_controller')"
ZONE_ID="$(tf output -raw hosted_zone_id)"
DOMAIN="$(tf output -raw domain_name)"
echo "    cluster=$CLUSTER zone=$ZONE_ID domain=$DOMAIN"

echo "==> 1/7 kubeconfig"
aws eks update-kubeconfig --name "$CLUSTER" --region "$REGION" >/dev/null
VPC_ID="$(aws eks describe-cluster --name "$CLUSTER" --query 'cluster.resourcesVpcConfig.vpcId' --output text)"
kubectl get nodes --no-headers | awk '{print "    node " $1 " " $2}'

echo "==> 2/7 helm 저장소"
helm repo add eks https://aws.github.io/eks-charts >/dev/null 2>&1 || true
helm repo add strimzi https://strimzi.io/charts/ >/dev/null 2>&1 || true
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts >/dev/null 2>&1 || true
helm repo add argo https://argoproj.github.io/argo-helm >/dev/null 2>&1 || true
helm repo update >/dev/null

echo "==> 3/7 aws-load-balancer-controller"
# ServiceAccount 이름은 IRSA 신뢰 정책과 정확히 일치해야 한다. 어긋나면 권한 오류가 아니라
# "Ingress가 영영 ADDRESS를 못 받는" 형태로 나타난다.
helm upgrade --install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName="$CLUSTER" \
  --set serviceAccount.create=true \
  --set serviceAccount.name=aws-load-balancer-controller \
  --set-string "serviceAccount.annotations.eks\.amazonaws\.com/role-arn=$LBC_ROLE" \
  --set region="$REGION" --set vpcId="$VPC_ID" \
  --wait --timeout 6m >/dev/null
echo "    ok"

echo "==> 4/7 strimzi / kube-prometheus-stack / argocd"
kubectl create namespace kafka --dry-run=client -o yaml | kubectl apply -f - >/dev/null
helm upgrade --install strimzi strimzi/strimzi-kafka-operator -n kafka --wait --timeout 6m >/dev/null
helm upgrade --install kube-prometheus-stack prometheus-community/kube-prometheus-stack \
  -n monitoring --create-namespace \
  -f "$ROOT/k8s/monitoring/kube-prometheus-stack.values.yaml" --wait --timeout 12m >/dev/null
# ⚠️ 2026-08-21에 이걸 빠뜨렸다. Application을 apply할 때 CRD가 없어서야 드러났다.
helm upgrade --install argocd argo/argo-cd -n argocd --create-namespace \
  -f "$ROOT/k8s/argocd/values.yaml" --wait --timeout 10m >/dev/null
echo "    ok"

echo "==> 5/7 네임스페이스 → ESO 부트스트랩"
# ⚠️ 순서가 중요하다. bootstrap.sh가 flowticket 네임스페이스에 ExternalSecret을 만드는데,
# 그 네임스페이스는 원래 앱 배포(다음 단계)가 만든다. 앞으로 빼지 않으면 반드시 실패한다.
kubectl apply -f "$ROOT/k8s/base/namespace.yaml" >/dev/null
bash "$ROOT/k8s/external-secrets/bootstrap.sh"

echo "==> 6/7 워크로드 배포"
kubectl apply -k "$ROOT/k8s/kafka" >/dev/null
kubectl apply -k "$ROOT/k8s/monitoring" >/dev/null
kubectl apply -f "$ROOT/k8s/argocd/application.yaml" >/dev/null
echo "    ArgoCD가 앱을 동기화할 때까지 대기"
kubectl wait --for=jsonpath='{.status.health.status}'=Healthy application/flowticket -n argocd --timeout=10m

echo "==> 7/7 Route53을 새 ALB로 갱신"
# 클러스터를 재생성하면 ALB 이름이 바뀐다 — 이 갱신을 빠뜨리면 도메인이 옛 ALB를 가리킨 채 남는다.
for i in $(seq 1 40); do
  ALB="$(kubectl get ingress flowticket -n flowticket -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || true)"
  [ -n "$ALB" ] && break
  sleep 15
done
[ -n "$ALB" ] || { echo "Ingress가 ALB 주소를 받지 못했다 — alb-controller 로그를 보라" >&2; exit 1; }
ALB_ZONE="$(aws elbv2 describe-load-balancers --query "LoadBalancers[?DNSName=='$ALB'].CanonicalHostedZoneId" --output text)"
CUR="$(aws route53 list-resource-record-sets --hosted-zone-id "$ZONE_ID" \
  --query "ResourceRecordSets[?Name=='${DOMAIN}.'&&Type=='A'].AliasTarget.DNSName" --output text)"
if [ "${CUR%.}" = "$ALB" ]; then
  echo "    이미 최신($ALB)"
else
  TMP="$(mktemp)"
  cat > "$TMP" <<JSON
{"Changes":[{"Action":"UPSERT","ResourceRecordSet":{
  "Name":"$DOMAIN","Type":"A",
  "AliasTarget":{"HostedZoneId":"$ALB_ZONE","DNSName":"$ALB","EvaluateTargetHealth":true}}}]}
JSON
  aws route53 change-resource-record-sets --hosted-zone-id "$ZONE_ID" --change-batch "file://$TMP" >/dev/null
  rm -f "$TMP"
  echo "    갱신됨 → $ALB"
fi

echo
echo "==> 확인"
kubectl get pods -A --no-headers | awk '{print $4}' | sort | uniq -c | sed 's/^/    /'
for i in $(seq 1 20); do
  CODE="$(curl -s -o /dev/null -w '%{http_code}' "https://$DOMAIN" --max-time 20 || true)"
  [ "$CODE" = "200" ] && break
  sleep 15
done
echo "    https://$DOMAIN → $CODE"

if [ "$SEED" -eq 1 ]; then
  echo
  bash "$HERE/seed-demo-data.sh"
else
  echo
  echo "데이터 시딩은 건너뛴다(--no-seed). 필요하면: bash scripts/seed-demo-data.sh"
fi
