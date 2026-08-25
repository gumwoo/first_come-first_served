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

# ── helm 차트 버전 고정 ──────────────────────────────────────────────
# ⚠️ 하나도 빠짐없이 박는다. 하나라도 floating이면 **같은 커밋의 이 스크립트가 시점에 따라
# 다른 클러스터를 만든다** — "절차를 코드로 고정한다"는 이 스크립트의 전제가 무너진다.
# 2026-08-25까지 다섯 개 모두 버전이 없었고, 그래서 지금까지의 기동 결과는 엄밀히 말해
# "그때 최신이던 것들"의 조합이었다.
#
# 올릴 때: helm search repo <차트> --versions | head -5 로 확인하고 여기만 고친다.
# 환경변수로 임시 override 할 수 있다(예: CA_CHART_VERSION=9.58.0 bash scripts/bring-up.sh).
CA_CHART_VERSION="${CA_CHART_VERSION:-9.59.0}"          # cluster-autoscaler   app 1.35.0
LBC_CHART_VERSION="${LBC_CHART_VERSION:-3.5.0}"         # aws-load-balancer-controller app v3.5.0
STRIMZI_CHART_VERSION="${STRIMZI_CHART_VERSION:-1.2.0}" # strimzi-kafka-operator app 1.2.0
KPS_CHART_VERSION="${KPS_CHART_VERSION:-88.5.4}"        # kube-prometheus-stack app v0.93.1
ARGOCD_CHART_VERSION="${ARGOCD_CHART_VERSION:-10.4.0}"  # argo-cd             app v3.5.1

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
CA_ROLE="$(tf output -json irsa_role_arns | jq -r '.cluster_autoscaler')"
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
helm repo add autoscaler https://kubernetes.github.io/autoscaler >/dev/null 2>&1 || true
helm repo update >/dev/null

echo "==> 3/7 aws-load-balancer-controller"
# ServiceAccount 이름은 IRSA 신뢰 정책과 정확히 일치해야 한다. 어긋나면 권한 오류가 아니라
# "Ingress가 영영 ADDRESS를 못 받는" 형태로 나타난다.
helm upgrade --install aws-load-balancer-controller eks/aws-load-balancer-controller \
  --version "$LBC_CHART_VERSION" \
  -n kube-system \
  --set clusterName="$CLUSTER" \
  --set serviceAccount.create=true \
  --set serviceAccount.name=aws-load-balancer-controller \
  --set-string "serviceAccount.annotations.eks\.amazonaws\.com/role-arn=$LBC_ROLE" \
  --set region="$REGION" --set vpcId="$VPC_ID" \
  --wait --timeout 6m >/dev/null
echo "    ok"

echo "==> 4/7 cluster-autoscaler / strimzi / kube-prometheus-stack / argocd"
# Cluster Autoscaler. Terraform이 IRSA 역할·ASG 태그·ignore_changes까지 준비해 두었고
# 빠져 있던 것은 이 설치뿐이었다(ADR-012 §4, 2026-08-25 도입).
#
# ⚠️ 차트 버전을 **반드시 고정한다.** floating으로 두면 같은 커밋의 bring-up.sh가
# 시점에 따라 다른 것을 설치한다 — 이 스크립트의 존재 이유(절차를 코드로 고정)와 어긋난다.
#
# ⚠️ 더 중요한 것: Cluster Autoscaler는 **Kubernetes 마이너 버전과 짝을 맞춰야 한다**
# (CA v1.35 → k8s 1.35). 버전을 박기만 하고 클러스터와 어긋나면 조용히 오작동한다.
# 그래서 아래에서 차트의 appVersion과 API 서버 마이너를 대조하고, 다르면 **중단**한다.
#
# 클러스터 버전을 올릴 때 이 값도 함께 올린다:
#   helm search repo autoscaler/cluster-autoscaler --versions | grep ' 1\.<마이너>\.'
CA_CHART_VERSION="${CA_CHART_VERSION:-9.59.0}"   # appVersion 1.35.0

CA_APP="$(helm show chart autoscaler/cluster-autoscaler --version "$CA_CHART_VERSION" 2>/dev/null \
  | awk '/^appVersion:/{print $2}' | tr -d '"' || true)"
[ -n "$CA_APP" ] || {
  echo "cluster-autoscaler 차트 $CA_CHART_VERSION 을 찾지 못했다 — 사용 가능한 버전:" >&2
  helm search repo autoscaler/cluster-autoscaler --versions 2>/dev/null | head -5 >&2
  exit 1; }
K8S_MINOR="$(kubectl version -o json 2>/dev/null | jq -r '.serverVersion.minor // empty' | tr -d '+' || true)"
CA_MINOR="$(echo "$CA_APP" | cut -d. -f2)"
# ⚠️ 못 읽었을 때 통과시키면 검사가 공허해진다 — 대조할 수 없다는 것 자체가 실패다.
[ -n "$K8S_MINOR" ] || {
  echo "API 서버의 Kubernetes 마이너 버전을 읽지 못해 CA 호환성을 대조할 수 없다 — 중단한다." >&2
  echo "  확인: kubectl version -o json" >&2
  exit 1; }
if [ "$K8S_MINOR" != "$CA_MINOR" ]; then
  echo "Cluster Autoscaler 버전이 클러스터와 어긋난다 — 중단한다." >&2
  echo "  클러스터 k8s 1.$K8S_MINOR / CA 앱 $CA_APP (차트 $CA_CHART_VERSION)" >&2
  echo "  맞는 차트를 고른 뒤 CA_CHART_VERSION 을 갱신하라:" >&2
  echo "    helm search repo autoscaler/cluster-autoscaler --versions | grep ' 1\\.$K8S_MINOR\\.'" >&2
  exit 1
fi
echo "    cluster-autoscaler 차트 $CA_CHART_VERSION (앱 $CA_APP) ↔ k8s 1.${K8S_MINOR:-?}"

# ⚠️ SA 이름(cluster-autoscaler)이 IRSA 신뢰 정책과 어긋나면 권한 오류가 아니라
# "노드가 조용히 안 늘어나는" 형태로 나타난다 — LB Controller와 같은 함정이다.
helm upgrade --install cluster-autoscaler autoscaler/cluster-autoscaler \
  --version "$CA_CHART_VERSION" \
  -n kube-system \
  -f "$ROOT/k8s/cluster-autoscaler/values.yaml" \
  --set autoDiscovery.clusterName="$CLUSTER" \
  --set-string "rbac.serviceAccount.annotations.eks\.amazonaws\.com/role-arn=$CA_ROLE" \
  --wait --timeout 6m >/dev/null
# ⚠️ 여기서 **경고가 아니라 실패**시킨다. 이 PR부터 CA는 선택이 아니라 기본 구성이다.
# 경고만 하면 exit 0인데 노드 오토스케일링이 죽어 있는 클러스터가 만들어지고, 그 상태로
# 다른 측정을 먼저 하면 조건이 조용히 오염된다(TS-034가 그렇게 나왔다).
CA_POD="$(kubectl -n kube-system get pod -l app.kubernetes.io/name=aws-cluster-autoscaler \
  -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"
[ -n "$CA_POD" ] || {
  echo "cluster-autoscaler 파드를 찾지 못했다 — helm 설치가 --wait로 끝났는데도 없다면 라벨을 확인하라:" >&2
  echo "  kubectl -n kube-system get pods -l app.kubernetes.io/name=aws-cluster-autoscaler --show-labels" >&2
  exit 1; }
# 노드그룹 인식은 로그로만 확인할 수 있고 기동 직후엔 아직 안 찍혀 있다 — 잠시 기다린다.
NG_SEEN=0
for i in $(seq 1 12); do
  NG_SEEN="$(kubectl -n kube-system logs "$CA_POD" --tail=500 2>/dev/null | grep -c "Registering Node Group" || true)"
  [ "${NG_SEEN:-0}" -gt 0 ] && break
  sleep 10
done
[ "${NG_SEEN:-0}" -gt 0 ] || {
  echo "cluster-autoscaler가 노드그룹을 하나도 인식하지 못했다 — 중단한다." >&2
  echo "  IRSA 권한이나 ASG 태그(k8s.io/cluster-autoscaler/{enabled,owned}) 문제다." >&2
  echo "  확인: kubectl -n kube-system logs $CA_POD --tail=50" >&2
  exit 1; }
echo "    cluster-autoscaler: $CA_POD (노드그룹 ${NG_SEEN}개 인식)"

kubectl create namespace kafka --dry-run=client -o yaml | kubectl apply -f - >/dev/null
helm upgrade --install strimzi strimzi/strimzi-kafka-operator \
  --version "$STRIMZI_CHART_VERSION" \
  -n kafka --wait --timeout 6m >/dev/null
helm upgrade --install kube-prometheus-stack prometheus-community/kube-prometheus-stack \
  --version "$KPS_CHART_VERSION" \
  -n monitoring --create-namespace \
  -f "$ROOT/k8s/monitoring/kube-prometheus-stack.values.yaml" --wait --timeout 12m >/dev/null
# ⚠️ 2026-08-21에 이걸 빠뜨렸다. Application을 apply할 때 CRD가 없어서야 드러났다.
helm upgrade --install argocd argo/argo-cd \
  --version "$ARGOCD_CHART_VERSION" \
  -n argocd --create-namespace \
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
  # ⚠️ file://로 넘기지 않는다. Git Bash의 /tmp는 실제로 %LOCALAPPDATA%\Temp인데 aws는
  # Windows 바이너리라 file:///tmp/... 를 Windows 경로 그대로 해석해 파일을 찾지 못한다.
  # 2026-08-25 기동에서 정확히 이 이유로 7/7이 실패했고, **도메인이 파괴된 옛 ALB를 가리킨
  # 채 남았다** — 바로 위 주석이 경고하던 그 상태다.
  # jq로 만들어 인자로 직접 넘기면 경로 해석도, 손수 이스케이프할 일도 없다.
  CHANGE_BATCH="$(jq -nc --arg n "$DOMAIN" --arg z "$ALB_ZONE" --arg d "$ALB" \
    '{Changes:[{Action:"UPSERT",ResourceRecordSet:{Name:$n,Type:"A",
      AliasTarget:{HostedZoneId:$z,DNSName:$d,EvaluateTargetHealth:true}}}]}')"
  aws route53 change-resource-record-sets --hosted-zone-id "$ZONE_ID" --change-batch "$CHANGE_BATCH" >/dev/null
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
# ⚠️ 여기서 반드시 실패시켜야 한다. 이 스크립트가 주장하는 것은 "기동 절차"가 아니라
# **"기동 절차 + 확인"**이고, 종료 코드 0은 그 확인까지 통과했다는 뜻이어야 한다.
# 그러지 않으면 --no-seed 경로에서 5분 내내 502가 나도 0으로 끝난다(뒤에 시딩이 없으므로
# 아무도 눈치채지 못한다).
if [ "$CODE" != "200" ]; then
  echo "기동 확인 실패: https://$DOMAIN → HTTP $CODE (5분 대기 후에도 200이 아니다)" >&2
  echo "  확인할 것: kubectl get pods -n flowticket / ALB 타깃 헬스 / Route53 전파" >&2
  exit 1
fi

if [ "$SEED" -eq 1 ]; then
  echo
  bash "$HERE/seed-demo-data.sh"
else
  echo
  echo "데이터 시딩은 건너뛴다(--no-seed). 필요하면: bash scripts/seed-demo-data.sh"
fi
