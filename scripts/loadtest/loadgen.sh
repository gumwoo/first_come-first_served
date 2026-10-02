#!/usr/bin/env bash
# 클러스터 밖 부하 발생기를 SSM으로 다룬다(loadtest-100k-plan §4). 인바운드·SSH가 없으므로 이 경로뿐이다.
#
#   scripts/loadtest/loadgen.sh status                      # 발생기 목록과 SSM 연결 상태
#   scripts/loadtest/loadgen.sh checkout <commit-sha>       # 모든 발생기에서 실행할 커밋을 고정한다
#   scripts/loadtest/loadgen.sh push <로컬 파일> <발생기 경로> # 사용자 토큰 파일 등을 S3 경유로 나눠 준다
#   scripts/loadtest/loadgen.sh exec [--gen N] -- <명령>     # 모든(또는 N번째) 발생기에서 명령 실행
#   scripts/loadtest/loadgen.sh pull <session-id>           # 발생기의 run 출력을 로컬 artifacts/로 받는다
#
# exec는 발생기 번호를 GEN 환경변수(g1, g2, …)로 넘긴다. run-entry.sh의 --gen에 그대로 쓴다.
# 결과 버킷은 철거 때 함께 지워진다. run이 끝날 때마다 pull 한다(§7).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
REGION="${AWS_REGION:-ap-northeast-2}"
REMOTE_ROOT=/opt/flowticket

ids() {
  # 이름 순(flowticket-loadgen-1, -2, …)으로 고정해 GEN 번호가 실행마다 바뀌지 않게 한다.
  aws ec2 describe-instances --region "$REGION" \
    --filters Name=tag:Project,Values=flowticket Name=tag:Role,Values=loadgen Name=instance-state-name,Values=running \
    --query 'Reservations[].Instances[].[Tags[?Key==`Name`]|[0].Value, InstanceId]' --output text \
    | sort -V | awk '{print $2}'
}

bucket() {
  aws s3api list-buckets --query "Buckets[?starts_with(Name,'flowticket-loadgen-')].Name | [0]" --output text
}

# 발생기 하나에 셸 명령을 보내고 끝날 때까지 기다려 출력을 찍는다. 실패하면 0이 아닌 값으로 끝난다.
run_on() {
  local id="$1" gen="$2" cmd="$3"
  # SSM 실행 셸은 로그인 셸이 아니라 limits.d가 적용되지 않는다. 연결 수 한도를 여기서 올린다.
  local full="ulimit -n 1000000; export GEN=$gen; cd $REMOTE_ROOT; $cmd"
  local cid
  cid="$(aws ssm send-command --region "$REGION" --instance-ids "$id" --document-name AWS-RunShellScript \
    --parameters "$(jq -cn --arg c "$full" '{commands: [$c], executionTimeout: ["14400"]}')" \
    --timeout-seconds 600 --query 'Command.CommandId' --output text)"
  local status errors=0
  while true; do
    if status="$(aws ssm get-command-invocation --region "$REGION" --command-id "$cid" --instance-id "$id" \
        --query Status --output text 2>/dev/null)"; then
      errors=0
    else
      # 등록 직후 잠깐은 조회가 실패한다. 계속 실패하면(자격 증명 만료 등) 무한 대기하지 않고 끝낸다.
      errors=$((errors + 1)); status=Pending
      [ "$errors" -ge 20 ] && { echo "--- $gen ($id): 상태 조회가 계속 실패한다(자격 증명 확인)" >&2; return 1; }
    fi
    case "$status" in Pending|InProgress|Delayed) sleep 3 ;; *) break ;; esac
  done
  echo "--- $gen ($id): $status"
  aws ssm get-command-invocation --region "$REGION" --command-id "$cid" --instance-id "$id" \
    --query '[StandardOutputContent, StandardErrorContent]' --output text
  [ "$status" = "Success" ]
}

# 모든 발생기에 병렬로 보낸다. 분산 실행은 동시에 시작해야 같은 시험 창이 된다.
run_all() {
  local only="$1" cmd="$2" i=0 pids=() fail=0
  for id in $(ids); do
    i=$((i + 1))
    [ -n "$only" ] && [ "$only" != "$i" ] && continue
    run_on "$id" "g$i" "$cmd" > "/tmp/loadgen-g$i.out" 2>&1 &
    pids+=("$!:$i")
  done
  [ "${#pids[@]}" -gt 0 ] || { echo "실행 중인 발생기가 없다" >&2; return 1; }
  for p in "${pids[@]}"; do
    wait "${p%%:*}" || fail=1
    cat "/tmp/loadgen-g${p##*:}.out"
  done
  return $fail
}

cmd="${1:-}"; shift || true
case "$cmd" in
  status)
    for id in $(ids); do
      aws ssm describe-instance-information --region "$REGION" --filters "Key=InstanceIds,Values=$id" \
        --query 'InstanceInformationList[0].[InstanceId, PingStatus]' --output text
    done
    ;;
  checkout)
    sha="${1:?커밋 SHA가 필요하다}"
    run_all "" "git fetch --quiet origin && git checkout --quiet $sha && git rev-parse HEAD"
    ;;
  push)
    src="${1:?로컬 파일이 필요하다}"; dst="${2:?발생기 경로가 필요하다}"
    b="$(bucket)"; key="inputs/$(basename "$src")"
    aws s3 cp --region "$REGION" "$src" "s3://$b/$key" --only-show-errors
    run_all "" "aws s3 cp s3://$b/$key $dst --only-show-errors && ls -l $dst"
    ;;
  exec)
    only=""
    if [ "${1:-}" = "--gen" ]; then only="$2"; shift 2; fi
    [ "${1:-}" = "--" ] && shift
    run_all "$only" "$*"
    ;;
  pull)
    session="${1:?session-id가 필요하다}"
    b="$(bucket)"
    # 일부 발생기만 쓴 run(exec --gen N)이면 나머지에는 디렉터리가 없다. 그건 실패가 아니다.
    run_all "" "if [ -d artifacts/loadtest/$session ]; then aws s3 sync artifacts/loadtest/$session s3://$b/runs/$session --only-show-errors && echo synced; else echo 'no runs here'; fi"
    mkdir -p "$ROOT/artifacts/loadtest/$session"
    aws s3 sync --region "$REGION" "s3://$b/runs/$session" "$ROOT/artifacts/loadtest/$session" --only-show-errors
    echo "받음: $ROOT/artifacts/loadtest/$session"
    ;;
  *)
    sed -n '2,10p' "$0"; exit 2 ;;
esac
