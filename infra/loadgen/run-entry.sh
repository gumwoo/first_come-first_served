#!/usr/bin/env bash
# 대기열 진입 시험 run 하나를 실행하고 출력을 run 디렉터리에 모은다(loadtest-100k-plan §7 "원시 데이터").
#
# 한 발생기에서 진입(k6)과 SSE 연결(sse-hold.mjs)을 함께 돌린다. 둘은 다른 프로세스라 진입 iteration이 SSE를
# 붙들지 않는다(§2.4 ②). 분산 실행이면 발생기마다 같은 --session/--run과 다른 --gen으로 실행한다 —
# 출력은 같은 run 디렉터리에 발생기 이름을 붙여 모인다.
#
# 발생기 자원(CPU·메모리·네트워크)도 함께 남긴다. 발생기 포화는 시험 무효 조건이고(§3.1), 그것을 판정할
# 원시 데이터가 여기서만 나온다(§8 Generator 축).
#
# 사용(발생기 인스턴스, Linux):
#   infra/loadgen/run-entry.sh --session 20261002-1400 --run step7-50k_constant-r1 --gen g1 \
#     --base https://flow-ticket.com/api --event 1733 --users ~/tokens.json --users-n 25000 --offset 0 \
#     --entry-seconds 10 --dist constant --sse-hold 300
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

SESSION="" RUN="" GEN="g1" BASE="" EVENT="" USERS="" USERS_N="" OFFSET=0
ENTRY_SECONDS=10 DIST=constant SSE_HOLD=300 PRE_VUS="" MAX_VUS="" NO_SSE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --session) SESSION="$2"; shift 2 ;;
    --run) RUN="$2"; shift 2 ;;
    --gen) GEN="$2"; shift 2 ;;
    --base) BASE="$2"; shift 2 ;;
    --event) EVENT="$2"; shift 2 ;;
    --users) USERS="$2"; shift 2 ;;
    --users-n) USERS_N="$2"; shift 2 ;;
    --offset) OFFSET="$2"; shift 2 ;;
    --entry-seconds) ENTRY_SECONDS="$2"; shift 2 ;;
    --dist) DIST="$2"; shift 2 ;;
    --sse-hold) SSE_HOLD="$2"; shift 2 ;;
    --pre-vus) PRE_VUS="$2"; shift 2 ;;
    --max-vus) MAX_VUS="$2"; shift 2 ;;
    --no-sse) NO_SSE=1; shift ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done
for v in SESSION RUN BASE EVENT USERS USERS_N; do
  [ -n "${!v}" ] || { echo "--$(echo "$v" | tr 'A-Z_' 'a-z-') 가 필요하다" >&2; exit 2; }
done

# k6의 open()은 상대경로를 스크립트 디렉터리(infra/k6) 기준으로 읽는다. 절대경로로 바꿔 넘긴다.
[ -f "$USERS" ] || { echo "사용자 토큰 파일이 없다: $USERS" >&2; exit 2; }
USERS="$(cd "$(dirname "$USERS")" && pwd)/$(basename "$USERS")"
for v in "$USERS_N" "$ENTRY_SECONDS" "$OFFSET" "$SSE_HOLD"; do
  case "$v" in ""|*[!0-9]*) echo "--users-n/--entry-seconds/--offset/--sse-hold는 정수여야 한다: $v" >&2; exit 2 ;; esac
done
# 앞자리 0(예: 08)은 JSON 숫자가 아니다. 10진수로 정규화한다.
USERS_N=$((10#$USERS_N)); ENTRY_SECONDS=$((10#$ENTRY_SECONDS)); OFFSET=$((10#$OFFSET)); SSE_HOLD=$((10#$SSE_HOLD))

OUT="$ROOT/artifacts/loadtest/$SESSION/$RUN"
mkdir -p "$OUT"
[ -e "$OUT/meta-$GEN.json" ] && { echo "이미 있는 run이다: $OUT/meta-$GEN.json — 회차 번호를 올린다" >&2; exit 1; }

# 실행 조건(§7). 결과 문서는 이 파일을 근거로 조건을 적는다.
cat > "$OUT/meta-$GEN.json" <<EOF
{
  "session": "$SESSION", "run": "$RUN", "generator": "$GEN", "host": "$(hostname)",
  "commit": "$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo unknown)",
  "dirty": $([ -n "$(git -C "$ROOT" status --porcelain --untracked-files=no 2>/dev/null)" ] && echo true || echo false),
  "startedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "base": "$BASE", "event": "$EVENT", "dist": "$DIST", "usersN": $USERS_N, "offset": $OFFSET,
  "entrySeconds": $ENTRY_SECONDS, "sseHoldSeconds": $SSE_HOLD, "sse": $([ "$NO_SSE" = 1 ] && echo false || echo true),
  "preVus": "${PRE_VUS:-default}", "maxVus": "${MAX_VUS:-default}"
}
EOF

# 발생기 자원 기록. 1초 간격. 시험이 끝나면 함께 멈춘다.
vmstat -n 1 > "$OUT/gen-vmstat-$GEN.log" 2>/dev/null &
VMSTAT=$!
( while true; do echo "$(date +%s) $(grep -E '^\s*(eth|ens|enp)' /proc/net/dev | tr -s ' ')"; sleep 1; done ) \
  > "$OUT/gen-netdev-$GEN.log" 2>/dev/null &
NETDEV=$!
trap 'kill $VMSTAT $NETDEV 2>/dev/null || true' EXIT

K6_ARGS=(run --log-format=raw
  -e K6_BASE_URL="$BASE" -e EVENT_ID="$EVENT" -e USERS="$USERS" -e USERS_N="$USERS_N"
  -e USER_OFFSET="$OFFSET" -e ENTRY_SECONDS="$ENTRY_SECONDS" -e DIST="$DIST"
  --out json="$OUT/entry-$GEN.json" --summary-export="$OUT/k6-summary-$GEN.json")
[ -n "$PRE_VUS" ] && K6_ARGS+=(-e PRE_VUS="$PRE_VUS")
[ -n "$MAX_VUS" ] && K6_ARGS+=(-e MAX_VUS="$MAX_VUS")

# k6가 중단·실패로 끝나도 그때까지의 원시 출력으로 지표는 계산해 둔다. 종료 코드는 마지막에 돌려준다.
set +e
if [ "$NO_SSE" = 1 ]; then
  k6 "${K6_ARGS[@]}" "$ROOT/infra/k6/queue-entry-rate.js" > "$OUT/k6-$GEN.log" 2>&1
else
  k6 "${K6_ARGS[@]}" -e EMIT_TOKENS=1 "$ROOT/infra/k6/queue-entry-rate.js" 2>&1 \
    | node "$HERE/sse-hold.mjs" --base "$BASE" --out "$OUT/sse-$GEN" --hold "$SSE_HOLD" > "$OUT/k6-$GEN.log"
fi
RUN_STATUS=$?
set -e

node "$HERE/entry-arrivals.mjs" --entry-seconds "$ENTRY_SECONDS" --users-n "$USERS_N" "$OUT/entry-$GEN.json" \
  > "$OUT/arrivals-$GEN.json"
echo "run 출력: $OUT"
[ "$RUN_STATUS" = 0 ] || echo "발생기가 0이 아닌 코드로 끝났다($RUN_STATUS) — 이 run의 유효성을 §3.1로 판정한다" >&2
exit "$RUN_STATUS"
