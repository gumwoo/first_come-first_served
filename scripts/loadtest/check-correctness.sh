#!/usr/bin/env bash
# 정합성 사후 검증(loadtest-100k-plan §3.3). run이 끝난 뒤, 철거 전에 실행한다.
#
#   bash scripts/loadtest/check-correctness.sh --out artifacts/loadtest/<session>/<run> --since 2026-10-02T05:00:00Z \
#     --until 2026-10-02T05:12:00Z
#
# --until은 run 종료 시각(UTC, 발생기가 멈춘 시각)이다. 내보낸 구간(prom/_meta.json)이 --since 이전부터 --until + 30초
# 이후까지를 덮어야 한다 — 파드 대조와 4단계(실시간 조건 사후 재확인)가 이 구간에 기댄다. 덮지 못하면 판정 불가(2)다.
# (감시기의 endedAt으로 대신하지 않는다 — 감시기가 일찍 멈췄으면 구간이 짧게 잡힌 채 통과한다.)
#
# --since는 run 시작 시각(UTC, run 메타의 startedAt과 같은 형식)이다. 이 시각 이후의 주문·이벤트·로그만 본다.
# DB에는 같은 UTC 벽시계로 바꿔 넘긴다 — 앱 컨테이너가 TZ=UTC이고 DB에도 UTC 벽시계가 쌓인다(api-deployment.yaml).
#
# 검사(결과는 <run>/correctness/ 아래):
#   sql.csv            — 초과판매, 일시적 이중 판매, 결제·좌석 상태 불일치, 멱등 위반, 미발행 아웃박스(correctness.sql)
#   queue-order.json   — 대기열 순서 위반(api 로그의 승격 감사 줄 대조, queue-order.mjs)
#   event-loss.txt     — 발행된 아웃박스 이벤트 중 소비 기록(Redis 멱등 키)이 없는 수
#   watch-summary.json — (같은 run 디렉터리) 실시간 감시기의 결과를 최종 판정에 넣는다. 3이면 위반, 0이 아니거나
#                        파일이 없거나 감시 구간(startedAt~endedAt)이 run을 덮지 않으면 판정 불가 — 감시기 0과
#                        4단계 통과가 함께 있어야 실시간 조건에 위반이 없었다.
#   prom-recheck.json  — 실시간 조건(over-admit, 카운터 어긋남, 초과판매)과 승격 처리 실패를 내보낸 구간 데이터로
#                        run 종료 + 30초까지 다시 본 결과(prom-recheck.mjs). 감시기는 run 끝을 보지 못하므로 여기서
#                        덮는다. 위반이면 1, 그 구간을 신선하게 관측하지 못했거나 승격 처리 실패가 있으면 2.
#   summary.txt        — 항목별 위반 수와 종합 판정
#
# 대기열 순서 대조는 run 동안 있었던 api 파드의 로그를 전부 읽을 수 있을 때만 판정한다(pod-coverage.mjs). 그래서
# 먼저 export-prom.mjs로 <run>/prom/을 남긴 뒤 실행한다 — api_pods.json·api_restarts.json이 없으면 검사 실패(2)다.
# kubelet이 컨테이너 로그를 회전해 지운 앞부분도 같은 대조에서 찾는다 — 파드별 현재 로그 파일의 첫 줄 시각이
# run 시작·컨테이너 시작보다 늦으면 run 구간이 잘린 것이다(pod-coverage.mjs).
#
# 실효 입장 초과는 판정식이 미확정이라(계획서 §3.3) 여기서 판정하지 않는다. 재료(승격·회수·이탈 감사 로그)는
# api.log에 함께 남는다.
#
# DB·Redis는 프라이빗이라 클러스터 안 일회용 파드로 붙는다. 자격증명은 api와 같은 ConfigMap·Secret에서 필요한 키만
# 받고, 이 스크립트는 보지도 출력하지도 않는다(seed-users.sh와 같은 방식).
# 종료 코드: 위반 없음 0, 위반 있음 1, 검사 자체 실패·판정 불가 2. 위반을 하나라도 찾았으면 검사 일부가 실패했어도
# 1이다 — 감시기(3)·queue-order(1)와 같이 위반이 우선한다. 검사 실패도 함께 있었으면 summary에 둘 다 적는다.
# run 직후 바로 돌리면 아직 발행 중인 아웃박스가 미발행으로 잡힐 수 있다. 아웃박스가 비워진 뒤
# (flowticket_outbox_oldest_pending_age_seconds 0) 실행한다.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="" SINCE="" UNTIL="" NS=flowticket TOL=1000
while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT="$2"; shift 2 ;;
    --since) SINCE="$2"; shift 2 ;;
    --until) UNTIL="$2"; shift 2 ;;
    --tolerance-ms) TOL="$2"; shift 2 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done
[ -n "$OUT" ] && [ -n "$SINCE" ] && [ -n "$UNTIL" ] || { echo "--out, --since, --until이 필요하다" >&2; exit 2; }
# 형식을 좁혀 SQL에 그대로 넣어도 안전하게 한다(따옴표·세미콜론이 들어올 수 없다).
[[ "$SINCE" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || {
  echo "--since는 YYYY-MM-DDTHH:MM:SSZ(UTC)여야 한다: $SINCE" >&2; exit 2; }
[[ "$UNTIL" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || {
  echo "--until은 YYYY-MM-DDTHH:MM:SSZ(UTC)여야 한다: $UNTIL" >&2; exit 2; }
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

echo "==> 0/4 내보낸 구간 확인(prom/_meta.json)"
# 파드 대조와 4단계(실시간 조건 사후 재확인)는 내보낸 구간이 run 전체 + 꼬리 30초를 덮어야 믿을 수 있다.
if node -e '
const fs = require("fs");
const [out, since, until] = process.argv.slice(1);
const fail = (m) => { console.log(m); process.exit(2); };
let meta;
try { meta = JSON.parse(fs.readFileSync(out + "/prom/_meta.json", "utf8")); }
catch { fail("prom/_meta.json을 읽지 못했다 — export-prom.mjs를 먼저 돌린다(도중에 죽었으면 다시 돌린다)"); }
const s = Number(meta.startSec) * 1000, e = Number(meta.endSec) * 1000;
if (!Number.isFinite(s) || !Number.isFinite(e)) fail("prom/_meta.json에 startSec·endSec가 없다 — export-prom.mjs로 다시 내보낸다");
const need = Date.parse(until) + 30000;
console.log("export=" + new Date(s).toISOString() + "~" + new Date(e).toISOString() + " run=" + since + "~" + until);
if (s > Date.parse(since)) fail("내보낸 구간이 run 시작보다 늦게 시작한다 — --start를 run 시작 이전으로 다시 내보낸다");
if (e < need) fail("내보낸 구간이 run 종료 + 30초(" + new Date(need).toISOString() + ")를 덮지 않는다 — --end를 늘려 다시 내보낸다");
' "$OUT" "$SINCE" "$UNTIL" > "$D/export-window.txt" 2>&1; then :
else
  BROKEN=1
  echo "    $(tail -n 1 "$D/export-window.txt")" >&2
fi

echo "==> 1/4 SQL 검사"
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

echo "==> 2/4 대기열 순서(api 로그)"
if kubectl -n "$NS" logs -l app=flowticket-api --since-time="$SINCE" --tail=-1 --prefix \
     --max-log-requests=20 > "$D/api.log"; then
  node "$HERE/queue-order.mjs" --tolerance-ms "$TOL" "$D/api.log" > "$D/queue-order.json"
  # 2: 승격 기록 0건·읽지 못한 승격 줄(판정 불가) 또는 실행 실패
  case $? in 0) ;; 1) FAIL=1 ;; *) BROKEN=1 ;; esac
else
  echo "    api 로그 수집 실패(클러스터 접근)" >&2; BROKEN=1
fi

# run 동안 있었던 파드의 로그를 전부 읽었는지 본다. 빠진 파드가 있으면 대조는 하되 판정 불가다.
# 로그를 받은 **뒤에** 파드 목록을 읽는다. 순서가 반대면 목록을 읽은 뒤 로그를 받기 전에 지워진 파드가 "읽음"으로
# 남는다. 이 순서에서는 로그를 받은 뒤 지워진 파드가 "빠짐"으로 잡혀 판정 불가 쪽으로만 틀린다.
if kubectl -n "$NS" get pods -l app=flowticket-api \
     -o jsonpath='{range .items[*]}{.metadata.name} {.status.containerStatuses[?(@.name=="api")].restartCount} {.status.containerStatuses[?(@.name=="api")].state.running.startedAt}{"\n"}{end}' \
     > "$D/api-pods-now.txt"; then
  # 파드별 현재 로그 파일의 첫 줄 시각(--since-time 없이 앞에서부터 1KiB만). 읽지 못하면 빈 값 = 확인 불가.
  : > "$D/api-first-lines.txt"
  while read -r pod _; do
    [ -n "$pod" ] || continue
    first="$(kubectl -n "$NS" logs "$pod" -c api --timestamps --limit-bytes=1024 </dev/null 2>/dev/null | head -n 1 | cut -d' ' -f1)"
    echo "$pod $first" >> "$D/api-first-lines.txt"
  done < "$D/api-pods-now.txt"
  node "$HERE/pod-coverage.mjs" --pods "$OUT/prom/api_pods.json" --restarts "$OUT/prom/api_restarts.json" \
    --existing "$D/api-pods-now.txt" --first-lines "$D/api-first-lines.txt" --since "$SINCE" \
    --meta "$OUT/prom/_meta.json" > "$D/pod-coverage.json" || { echo "    로그를 다 읽지 못한 파드가 있다(pod-coverage.json)" >&2; BROKEN=1; }
else
  echo "    api 파드 목록 조회 실패" >&2; BROKEN=1
fi

echo "==> 3/4 이벤트 유실(아웃박스 PUBLISHED vs 소비자 멱등 키)"
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

echo "==> 4/4 실시간 조건 사후 재확인(내보낸 구간 — run 종료 + 30초까지)"
# 감시기는 run 끝을 보지 못한다. over-admit·카운터 어긋남·초과판매·승격 처리 실패를 내보낸 데이터로 다시 보고,
# 그 구간을 실제로 신선하게 관측했는지(샘플 나이·점 연속성)도 확인한다(prom-recheck.mjs).
node "$HERE/prom-recheck.mjs" --prom-dir "$OUT/prom" --since "$SINCE" --until "$UNTIL" > "$D/prom-recheck.json"
case $? in
  0) ;;
  1) echo "    내보낸 구간에서 실시간 조건 위반을 찾았다(prom-recheck.json)" >&2; FAIL=1 ;;
  *) echo "    실시간 조건을 다시 확인하지 못했다 — 관측 공백·승격 처리 실패·파일 문제(prom-recheck.json)" >&2; BROKEN=1 ;;
esac

echo "==> 실시간 감시기 결과(watch-summary.json)"
# 감시기 0과 4단계(사후 재확인) 통과가 함께 있어야 실시간 조건에 위반이 없었다. 감시기 결과를 최종 판정에 넣는다.
node -e '
let s;
try { s = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8")); }
catch { console.log("watch-summary.json이 없다 — 실시간 감시기를 같은 run 디렉터리로 돌리지 않았다"); process.exit(2); }
console.log("exitCode=" + s.exitCode + " " + s.verdict + " (" + s.startedAt + " ~ " + s.endedAt + ")");
if (s.exitCode === 3) process.exit(1);
// 이 run을 감시한 결과인지 확인한다 — run 시작 전에 시작해 run 종료 뒤에 끝났어야 한다(다른 run의 결과·일찍 멈춘 감시 배제).
const st = Date.parse(s.startedAt), en = Date.parse(s.endedAt);
if (!(st <= Date.parse(process.argv[2])) || !(en >= Date.parse(process.argv[3]))) {
  console.log("감시 구간이 run(" + process.argv[2] + " ~ " + process.argv[3] + ")을 덮지 않는다");
  process.exit(2);
}
process.exit(s.exitCode === 0 ? 0 : 2);
' "$OUT/watch-summary.json" "$SINCE" "$UNTIL" > "$D/watch-verdict.txt" 2>&1
case $? in
  0) ;;
  1) echo "    실시간 감시기가 위반을 기록했다" >&2; FAIL=1 ;;
  *) echo "    실시간 감시기 결과가 위반 없음(0)이 아니다 — $(tail -n 1 "$D/watch-verdict.txt")" >&2; BROKEN=1 ;;
esac

{
  echo "since=$SINCE"
  echo "--- sql (검사,위반 수)"; cat "$D/sql.csv" 2>/dev/null
  # require는 상대경로를 모듈 이름으로 읽는다. 파일로 읽는다.
  echo "--- queue-order"; node -e 'try{const r=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));console.log(r.admits===0?"판정 불가(승격 기록 0건)":"admits="+r.admits+" violations="+r.violations+" malformed="+r.malformed)}catch{console.log("판정 불가")}' "$D/queue-order.json" 2>/dev/null
  echo "--- pod-coverage"; node -e 'try{const r=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));console.log(r.complete?"지워진 파드·재시작·run 구간 로그 회전 없음("+r.seen.length+"개)":"판정 불가 — 빠진 파드 "+r.missing.length+", run 중 재시작 "+r.restartedInRun.length+", 이후 재시작·불명 "+r.restartedAfterOrUnknown.length+", 로그 회전·불명 "+r.rotatedOrUnknown.length)}catch{console.log("판정 불가(확인 실패)")}' "$D/pod-coverage.json" 2>/dev/null
  echo "--- 내보낸 구간(파드 대조·승격 처리 실패의 전제)"; cat "$D/export-window.txt" 2>/dev/null
  echo "--- event-loss"; cat "$D/event-loss.txt" 2>/dev/null
  echo "--- 실시간 조건 사후 재확인(내보낸 구간, run 종료 + 30초까지)"; node -e 'try{const r=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));console.log(r.violations.length?"위반: "+r.violations.join("; ")+(r.problems.length?" / 판정 불가 사유도 있음: "+r.problems.join("; "):""):r.problems.length?"판정 불가: "+r.problems.join("; "):"위반 없음(관측 점 "+r.observedPoints+"개, 샘플 나이 최대 "+r.sampleAgeMaxSec+"초)")}catch{console.log("판정 불가(확인 실패)")}' "$D/prom-recheck.json" 2>/dev/null
  echo "--- 실시간 감시기(watch-summary.json)"; cat "$D/watch-verdict.txt" 2>/dev/null
  echo "--- 실효 입장 초과: 판정식 미확정(계획서 §3.3) — 판정하지 않음"
  # 검사 일부가 실패해도 이미 찾은 위반은 함께 보인다.
  if [ "$BROKEN" -ne 0 ] && [ "$FAIL" -ne 0 ]; then echo "판정: 정합성 위반 + 검사 일부 실패(결과 불완전)"
  elif [ "$BROKEN" -ne 0 ]; then echo "판정: 검사 실패(결과 불완전)"
  elif [ "$FAIL" -ne 0 ]; then echo "판정: 정합성 위반"
  else echo "판정: 위반 없음"; fi
} > "$D/summary.txt"
cat "$D/summary.txt"

[ "$FAIL" -ne 0 ] && exit 1
[ "$BROKEN" -ne 0 ] && exit 2
exit 0
