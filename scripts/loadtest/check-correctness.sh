#!/usr/bin/env bash
# 정합성 사후 검증(loadtest-100k-plan §3.3). run이 끝난 뒤, 철거 전에 실행한다.
#
#   bash scripts/loadtest/check-correctness.sh --out artifacts/loadtest/<session>/<run> --since 2026-10-02T05:00:00Z
#
# --since는 run 시작 시각(UTC, run 메타의 startedAt과 같은 형식)이다. 이 시각 이후의 주문·이벤트·로그만 본다.
# DB에는 같은 UTC 벽시계로 바꿔 넘긴다 — 앱 컨테이너가 TZ=UTC이고 DB에도 UTC 벽시계가 쌓인다(api-deployment.yaml).
#
# 검사(결과는 <run>/correctness/ 아래):
#   sql.csv            — 초과판매, 일시적 이중 판매, 결제·좌석 상태 불일치, 멱등 위반, 미발행 아웃박스(correctness.sql)
#   queue-order.json   — 대기열 순서 위반(api 로그의 승격 감사 줄 대조, queue-order.mjs)
#   event-loss.txt     — 발행된 아웃박스 이벤트 중 소비 기록(Redis 멱등 키)이 없는 수
#   summary.txt        — 항목별 위반 수와 종합 판정
#
# 대기열 순서 대조는 run 동안 있었던 api 파드의 로그를 전부 읽을 수 있을 때만 판정한다(pod-coverage.mjs). 그래서
# 먼저 export-prom.mjs로 <run>/prom/을 남긴 뒤 실행한다 — api_pods.json·api_restarts.json이 없으면 검사 실패(2)다.
#
# 실효 입장 초과는 판정식이 미확정이라(계획서 §3.3) 여기서 판정하지 않는다. 재료(승격·회수·이탈 감사 로그)는
# api.log에 함께 남는다.
#
# DB·Redis는 프라이빗이라 클러스터 안 일회용 파드로 붙는다. 자격증명은 api와 같은 ConfigMap·Secret에서 필요한 키만
# 받고, 이 스크립트는 보지도 출력하지도 않는다(seed-users.sh와 같은 방식).
# 종료 코드: 위반 없음 0, 위반 있음 1, 검사 자체 실패 2(위반도 함께 찾았으면 summary에 둘 다 적는다).
# run 직후 바로 돌리면 아직 발행 중인 아웃박스가 미발행으로 잡힐 수 있다. 아웃박스가 비워진 뒤
# (flowticket_outbox_oldest_pending_age_seconds 0) 실행한다.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="" SINCE="" NS=flowticket TOL=1000
while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT="$2"; shift 2 ;;
    --since) SINCE="$2"; shift 2 ;;
    --tolerance-ms) TOL="$2"; shift 2 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done
[ -n "$OUT" ] && [ -n "$SINCE" ] || { echo "--out과 --since가 필요하다" >&2; exit 2; }
# 형식을 좁혀 SQL에 그대로 넣어도 안전하게 한다(따옴표·세미콜론이 들어올 수 없다).
[[ "$SINCE" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || {
  echo "--since는 YYYY-MM-DDTHH:MM:SSZ(UTC)여야 한다: $SINCE" >&2; exit 2; }
SINCE_DB="${SINCE/T/ }"; SINCE_DB="${SINCE_DB%Z}"
D="$OUT/correctness"
mkdir -p "$D" || exit 2

# 일회용 파드 사양(seed-users.sh와 같은 이유로 stdinOnce를 직접 넣는다).
psql_pod() {
  local name="$1"; shift
  local overrides
  overrides="$(cat <<'EOF'
{"spec":{"restartPolicy":"Never","containers":[{"name":"psql","image":"postgres:16","stdin":true,"stdinOnce":true,
 "env":[
  {"name":"PGHOST","valueFrom":{"configMapKeyRef":{"name":"flowticket-api-config","key":"DB_HOST"}}},
  {"name":"PGPORT","valueFrom":{"configMapKeyRef":{"name":"flowticket-api-config","key":"DB_PORT"}}},
  {"name":"PGDATABASE","valueFrom":{"configMapKeyRef":{"name":"flowticket-api-config","key":"DB_NAME"}}},
  {"name":"PGUSER","valueFrom":{"secretKeyRef":{"name":"flowticket-api-secrets","key":"DB_USERNAME"}}},
  {"name":"PGPASSWORD","valueFrom":{"secretKeyRef":{"name":"flowticket-api-secrets","key":"DB_PASSWORD"}}},
  {"name":"PGSSLMODE","value":"prefer"}],
 "command":["psql","-v","ON_ERROR_STOP=1","-q","-At","-F,","-f","-"]}]}}
EOF
)"
  kubectl run "$name" -n "$NS" --rm -i --quiet --restart=Never --pod-running-timeout=5m \
    --image=postgres:16 --overrides="$overrides"
}

FAIL=0 BROKEN=0

echo "==> 1/3 SQL 검사"
if { printf "\\\\set since '%s'\n" "$SINCE_DB"; cat "$HERE/correctness.sql"; } | psql_pod "loadtest-check-sql" > "$D/sql.csv"; then
  # 검사 8개가 모두 "<이름>,<수>" 한 줄씩 나와야 한다. 모자라면 일부 검사가 돌지 않은 것이다.
  if [ "$(grep -cE '^[a-z_]+,[0-9]+$' "$D/sql.csv")" -ne 8 ]; then
    echo "    SQL 결과가 8줄이 아니다" >&2; BROKEN=1
  else
    awk -F, '$2 > 0 { bad=1 } END { exit bad }' "$D/sql.csv" || FAIL=1
  fi
else
  echo "    SQL 검사 실패" >&2; BROKEN=1
fi

echo "==> 2/3 대기열 순서(api 로그)"
# run 동안 있었던 파드의 로그를 지금 전부 읽을 수 있는지 먼저 본다. 빠진 파드가 있으면 대조는 하되 판정 불가다.
if kubectl -n "$NS" get pods -l app=flowticket-api \
     -o jsonpath='{range .items[*]}{.metadata.name} {.status.containerStatuses[?(@.name=="api")].restartCount}{"\n"}{end}' \
     > "$D/api-pods-now.txt"; then
  node "$HERE/pod-coverage.mjs" --pods "$OUT/prom/api_pods.json" --restarts "$OUT/prom/api_restarts.json" \
    --existing "$D/api-pods-now.txt" > "$D/pod-coverage.json" || { echo "    로그를 읽을 수 없는 파드가 있다(pod-coverage.json)" >&2; BROKEN=1; }
else
  echo "    api 파드 목록 조회 실패" >&2; BROKEN=1
fi
if kubectl -n "$NS" logs -l app=flowticket-api --since-time="$SINCE" --tail=-1 --prefix \
     --max-log-requests=20 > "$D/api.log"; then
  node "$HERE/queue-order.mjs" --tolerance-ms "$TOL" "$D/api.log" > "$D/queue-order.json"
  # 2: 승격 기록 0건·읽지 못한 승격 줄(판정 불가) 또는 실행 실패
  case $? in 0) ;; 1) FAIL=1 ;; *) BROKEN=1 ;; esac
else
  echo "    api 로그 수집 실패(클러스터 접근)" >&2; BROKEN=1
fi

echo "==> 3/3 이벤트 유실(아웃박스 PUBLISHED vs 소비자 멱등 키)"
# 소비자는 처리한 이벤트를 dedup:order-event:<eventId>로 24시간 남긴다(OrderEventConsumer). 24시간 안에 돌린다.
if printf "\\\\copy (SELECT id FROM outbox_events WHERE status = 'PUBLISHED' AND created_at >= '%s') TO STDOUT\n" "$SINCE_DB" \
     | psql_pod "loadtest-check-outbox" > "$D/published-ids.txt"; then
  n_pub="$(grep -c . "$D/published-ids.txt" || true)"
  if [ "$n_pub" -eq 0 ]; then
    echo "published=0 missing=0" > "$D/event-loss.txt"
  else
    REDIS_OVERRIDES="$(cat <<'EOF'
{"spec":{"restartPolicy":"Never","containers":[{"name":"redis","image":"redis:7","stdin":true,"stdinOnce":true,
 "env":[
  {"name":"RHOST","valueFrom":{"configMapKeyRef":{"name":"flowticket-api-config","key":"REDIS_HOST"}}},
  {"name":"RPORT","valueFrom":{"configMapKeyRef":{"name":"flowticket-api-config","key":"REDIS_PORT"}}}],
 "command":["sh","-c","redis-cli --tls -h \"$RHOST\" -p \"$RPORT\" --no-raw"]}]}}
EOF
)"
    # 한 줄에 EXISTS 하나. 결과는 줄마다 (integer) 0|1.
    sed 's/^/EXISTS dedup:order-event:/' "$D/published-ids.txt" \
      | kubectl run loadtest-check-redis -n "$NS" --rm -i --quiet --restart=Never --pod-running-timeout=5m \
          --image=redis:7 --overrides="$REDIS_OVERRIDES" > "$D/dedup-exists.txt"
    n_found="$(grep -c '(integer) 1' "$D/dedup-exists.txt" || true)"
    n_answers="$(grep -c '(integer)' "$D/dedup-exists.txt" || true)"
    echo "published=$n_pub answered=$n_answers consumed=$n_found missing=$((n_pub - n_found))" > "$D/event-loss.txt"
    if [ "$n_answers" -ne "$n_pub" ]; then BROKEN=1
    elif [ "$n_found" -ne "$n_pub" ]; then FAIL=1; fi
  fi
else
  echo "    아웃박스 조회 실패" >&2; BROKEN=1
fi

{
  echo "since=$SINCE"
  echo "--- sql (검사,위반 수)"; cat "$D/sql.csv" 2>/dev/null
  # require는 상대경로를 모듈 이름으로 읽는다. 파일로 읽는다.
  echo "--- queue-order"; node -e 'try{const r=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));console.log(r.admits===0?"판정 불가(승격 기록 0건)":"admits="+r.admits+" violations="+r.violations+" malformed="+r.malformed)}catch{console.log("판정 불가")}' "$D/queue-order.json" 2>/dev/null
  echo "--- pod-coverage"; node -e 'try{const r=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));console.log(r.complete?"모든 파드의 로그를 읽음("+r.seen.length+"개)":"판정 불가 — 빠진 파드 "+r.missing.length+", run 중 재시작 "+r.restartedInRun.length+", 이후 재시작·불명 "+r.restartedAfterOrUnknown.length)}catch{console.log("판정 불가(확인 실패)")}' "$D/pod-coverage.json" 2>/dev/null
  echo "--- event-loss"; cat "$D/event-loss.txt" 2>/dev/null
  echo "--- 실효 입장 초과: 판정식 미확정(계획서 §3.3) — 판정하지 않음"
  # 검사 일부가 실패해도 이미 찾은 위반은 함께 보인다.
  if [ "$BROKEN" -ne 0 ] && [ "$FAIL" -ne 0 ]; then echo "판정: 정합성 위반 + 검사 일부 실패(결과 불완전)"
  elif [ "$BROKEN" -ne 0 ]; then echo "판정: 검사 실패(결과 불완전)"
  elif [ "$FAIL" -ne 0 ]; then echo "판정: 정합성 위반"
  else echo "판정: 위반 없음"; fi
} > "$D/summary.txt"
cat "$D/summary.txt"

[ "$BROKEN" -ne 0 ] && exit 2
[ "$FAIL" -ne 0 ] && exit 1
exit 0
