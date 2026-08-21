#!/usr/bin/env bash
# External Secrets Operator 부트스트랩. 클러스터를 새로 만들 때 이것 하나만 실행한다.
#
# 왜 스크립트인가: ESO는 자기 자신을 GitOps로 관리할 수 없다(시크릿을 만드는 주체가 없으면
# 앱이 뜨지 않는다). 그래서 최초 설치는 어딘가에서 한 번 손으로 시작해야 하고, 그 "한 번"을
# 사람의 기억이 아니라 저장소에 남긴다.
#
# 왜 sed 렌더링인가: RDS 마스터 시크릿 이름은 `rds!db-<DBI resource id>` 형식이라
# **RDS를 재생성하면 바뀐다.** 매니페스트에 박아두면 인프라 전체 재생성 시 조용히 깨진다.
# Terraform 출력에서 매번 읽어 채우면 사람이 이 파일을 고칠 일이 없다.
#
# 전제: terraform apply 완료(IRSA 역할 `<cluster>-external-secrets` 존재), kubeconfig 설정됨.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
TFDIR="$ROOT/infra/terraform/platform/environments/demo"
# Slack webhook URL이 담긴 SSM 파라미터. **값이 아니라 경로만** 저장소에 남는다.
SLACK_PARAM="/flowticket/SLACK_ALERT_WEBHOOK_URL"

echo "==> 1/5 RDS 마스터 시크릿 ARN 조회 (Terraform 출력)"
RDS_SECRET_ARN="$(terraform -chdir="$TFDIR" output -raw db_secret_arn)"
[ -n "$RDS_SECRET_ARN" ] || { echo "db_secret_arn 이 비어 있다 — terraform apply 를 먼저 하라" >&2; exit 1; }
echo "    $RDS_SECRET_ARN"

echo "==> 2/5 ESO 설치 (helm)"
helm repo add external-secrets https://charts.external-secrets.io >/dev/null 2>&1 || true
helm repo update external-secrets >/dev/null
helm upgrade --install external-secrets external-secrets/external-secrets \
  -n external-secrets --create-namespace \
  -f "$HERE/values.yaml" --wait --timeout 6m

echo "==> 3/5 ClusterSecretStore 적용"
kubectl apply -f "$HERE/secretstore.yaml"

echo "==> 4/5 ExternalSecret 적용 (ARN 렌더링)"
# __RDS_SECRET_ARN__ 은 Git에 남는 플레이스홀더다. 여기서만 실제 값으로 치환된다.
sed "s|__RDS_SECRET_ARN__|${RDS_SECRET_ARN}|g" "$HERE/externalsecret-api.yaml" | kubectl apply -f -

echo "==> 5/5 Alertmanager Slack webhook ExternalSecret 적용"
# 파라미터가 없으면 여기서 멈춘다. 뒤로 미루면 증상이 "Alertmanager 파드가 ContainerCreating에서
# 멈춤"으로 나타나 원인에서 한 칸 떨어진 곳에서 터진다 — 여기서 이름을 대고 죽는 편이 낫다.
# 값은 읽지 않는다(--with-decryption 없이 이름만 조회).
if ! aws ssm get-parameter --name "$SLACK_PARAM" --query 'Parameter.Name' --output text >/dev/null 2>&1; then
  cat >&2 <<EOF
SSM 파라미터가 없다: $SLACK_PARAM
Alertmanager가 이 값을 파일로 마운트하므로, 없으면 파드가 뜨지 않는다.
먼저 넣어라(값은 이 저장소에 남기지 않는다):
  aws ssm put-parameter --name $SLACK_PARAM --type SecureString --value '<Slack incoming webhook URL>'
EOF
  exit 1
fi
# monitoring 네임스페이스는 kube-prometheus-stack helm이 만들지만, 이 스크립트가 먼저 돌 수 있다.
kubectl create namespace monitoring --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -f "$HERE/externalsecret-alertmanager.yaml"

echo
echo "==> 동기화 확인"
kubectl wait --for=condition=Ready externalsecret/flowticket-api-secrets -n flowticket --timeout=120s
kubectl wait --for=condition=Ready externalsecret/flowticket-alert-slack -n monitoring --timeout=120s
kubectl get secret flowticket-api-secrets -n flowticket -o json |
  grep -oE '"[A-Z_]+": "' | tr -d '": ' | sort | nl
echo
echo "완료. 위 키 목록이 11개면 정상이다(값은 출력하지 않는다)."
echo "Alertmanager용 secret(monitoring/flowticket-alert-slack)도 동기화됐다."
