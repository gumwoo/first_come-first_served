#!/usr/bin/env bash
# ALB 계층 오류 알람 스택(infra/terraform/platform/environments/demo-alarms) 적용·철거.
#
# ALB는 AWS Load Balancer Controller가 Ingress를 보고 만들어 demo 스택이 모른다. 그래서 ALB가 생긴 뒤
# 그 식별자(ALB·대상 그룹 ARN 접미사)를 찾아 이 스택에 변수로 넘긴다. 왜 별도 스택인지는 demo-alarms/main.tf 머리말.
#
# 사용:
#   bash scripts/alb-alarms.sh apply     # bring-up.sh 뒤(Ingress가 ALB 주소를 받은 뒤). ALARM_EMAIL=... 이면 이메일 구독
#   bash scripts/alb-alarms.sh destroy   # tear-down.sh가 Ingress 삭제 전에 부른다. state가 비면 건너뛴다
#   bash scripts/alb-alarms.sh plan      # 적용 없이 계획만
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
DIR="$ROOT/infra/terraform/platform/environments/demo-alarms"
export AWS_DEFAULT_REGION="${AWS_REGION:-ap-northeast-2}"
MODE="${1:-}"

tf() { terraform -chdir="$DIR" "$@"; }

# demo와 같은 state 버킷을 쓴다(키만 다르다 — versions.tf). backend.hcl은 커밋하지 않는 파일이라 demo 것을 그대로 쓴다.
tf init -input=false -reconfigure -backend-config="$ROOT/infra/terraform/platform/environments/demo/backend.hcl" >/dev/null

case "$MODE" in
  apply|plan)
    ALB="$(kubectl get ingress flowticket -n flowticket -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || true)"
    [ -n "$ALB" ] || { echo "Ingress flowticket에 ALB 주소가 없다. bring-up.sh가 끝났는지 확인하라" >&2; exit 1; }
    LB_ARN="$(aws elbv2 describe-load-balancers --query "LoadBalancers[?DNSName=='$ALB'].LoadBalancerArn" --output text | tr -d '\r')"
    [ -n "$LB_ARN" ] && [ "$LB_ARN" != "None" ] || { echo "ALB $ALB 의 ARN을 찾지 못했다" >&2; exit 1; }
    LB_SUFFIX="${LB_ARN#*:loadbalancer/}"                       # app/<이름>/<id>
    TGS="$(aws elbv2 describe-target-groups --load-balancer-arn "$LB_ARN" --query 'TargetGroups[].TargetGroupArn' --output text | tr -d '\r')"
    [ -n "$TGS" ] || { echo "ALB에 대상 그룹이 없다" >&2; exit 1; }
    TG_JSON="$(for a in $TGS; do printf '%s\n' "${a##*:}"; done | jq -R . | jq -sc .)"   # ["targetgroup/<이름>/<id>", ...]
    echo "    ALB $LB_SUFFIX, 대상 그룹 $(echo "$TG_JSON" | jq length)개"
    ARGS=(-input=false -var "alb_arn_suffix=$LB_SUFFIX" -var "target_group_arn_suffixes=$TG_JSON" -var "alarm_email=${ALARM_EMAIL:-}")
    if [ "$MODE" = plan ]; then tf plan "${ARGS[@]}"; else tf apply -auto-approve "${ARGS[@]}"; fi
    ;;
  destroy)
    # 조회 실패와 빈 state를 구분한다(tear-down.sh 5단계와 같은 이유 — 실패를 "지울 것 없음"으로 읽지 않는다).
    if ! STATE="$(tf state list 2>&1)"; then
      echo "알람 스택 state 조회 실패:" >&2; echo "$STATE" | sed 's/^/      /' >&2; exit 1
    fi
    if [ -z "$STATE" ]; then echo "    알람 스택 state가 비어 있다. 건너뛴다"; exit 0; fi
    # destroy는 state에 있는 것을 지운다. 변수는 형식 검사만 통과하면 되고 실제 ALB가 없어도 된다(data 소스를 쓰지 않는 이유).
    tf destroy -auto-approve -input=false -var "alb_arn_suffix=app/none/0" -var 'target_group_arn_suffixes=[]'
    ;;
  *)
    echo "사용: bash scripts/alb-alarms.sh apply|plan|destroy" >&2; exit 2
    ;;
esac
