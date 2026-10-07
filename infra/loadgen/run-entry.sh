#!/usr/bin/env bash
# 대기열 진입 시험 run 하나를 실행하고 출력을 run 디렉터리에 모은다(loadtest-100k-plan §7 "원시 데이터").
#
# 진입(k6)을 돌리고, --poll-hold N을 주면 발급된 대기 토큰을 대기자 폴링 발생기(poll-hold.mjs)가 받아 N초 동안 상태를 묻는다
# (프론트와 같은 규칙 — retryAfterMs + jitter). 두 프로세스라 진입 iteration이 대기를 붙들지 않는다. 대기열 SSE는 제거됐다
# (ADR-023 §2) — 예전 --sse-hold는 받지 않고, --no-sse는 이전 호출과의 호환을 위해 받기만 한다. 분산 실행이면 발생기마다 같은 --session/--run과 다른 --gen으로 실행한다 —
# 출력은 같은 run 디렉터리에 발생기 이름을 붙여 모인다.
#
# 발생기 자원(CPU·메모리·네트워크)도 함께 남긴다. 발생기 포화는 시험 무효 조건이고(§3.1), 그것을 판정할
# 원시 데이터가 여기서만 나온다(§8 Generator 축).
#
# 사용(발생기 인스턴스, Linux):
#   infra/loadgen/run-entry.sh --session 20261002-1400 --run step7-50k_constant-r1 --gen g1 \
#     --base https://flow-ticket.com/api --event 1733 --users ~/tokens.json --users-n 25000 --offset 0 \
#     --entry-seconds 10 --dist constant [--poll-hold 300]
#   --poll-hold N: 대기 토큰을 poll-hold.mjs가 받아 첫 토큰부터 N초 동안 상태를 묻는다(결과 <run>/poll-<gen>/).
#     발생기 종료(end-<gen>.json)는 k6와 폴링이 모두 끝난 시각이라 사후 검사 --until은 폴링 끝 뒤로 잡힌다.
#
# 분산 실행(발생기 G대가 한 run을 나눠 건다):
#   --gens G를 주면 --users-n은 **전체** 사용자 수, --offset은 전체의 시작 인덱스다. 각 발생기는 --gen gK(K=1..G)로
#   자기 몫을 계산한다 — 몫 = 전체/G(나머지는 마지막 발생기), 시작 = offset + (K−1)×(전체/G). --pre-vus·--max-vus는
#   발생기 하나의 값이다.
#   --start-at(UTC ISO)을 주면 k6 setup()이 그 시각까지 기다렸다가 도착을 시작한다(VU 할당이 끝난 뒤 장벽).
#   모든 발생기에 같은 --start-at을 준다. 이미 지난 시각이면 그 발생기는 시작하지 않는다.
#   scripts/loadtest/loadgen.sh exec -- 'infra/loadgen/run-entry.sh ... --gen $GEN --gens 3 --start-at 2026-10-07T01:00:00Z'
#   --warm-seconds W: 진입 시작(--start-at) 전 W초 동안 VU마다 keep-alive 연결을 미리 맺는다(queue-entry-rate.js WARM_SECONDS).
#     T0에 새 연결이 한꺼번에 열리며 실제 송신이 늦어지는 것을 막는다. 기본 0(끔).
#   --rate N: 발생기 한 대의 초당 도착 수(constant). 주지 않으면 round(몫 ÷ 진입 시간). 조금 높게 주면 몫이 진입 시간 안에서
#     모두 소진되고 남은 iteration은 요청 없이 끝난다(queue-entry-rate.js ARRIVAL_RATE).
#   --print-plan: 계산한 몫(gen·usersN·offset·startAt)만 JSON 한 줄로 내고 끝낸다(실행 전 확인·테스트용).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

SESSION="" RUN="" GEN="g1" GEN_SET=0 BASE="" EVENT="" USERS="" USERS_N="" OFFSET=0
ENTRY_SECONDS=10 DIST=constant PRE_VUS="" MAX_VUS="" GENS="" START_AT="" ARRIVAL_RATE="" WARM_SECONDS=0 POLL_HOLD=0 PRINT_PLAN=0
while [ $# -gt 0 ]; do
  case "$1" in
    --session) SESSION="$2"; shift 2 ;;
    --run) RUN="$2"; shift 2 ;;
    --gen) GEN="$2"; GEN_SET=1; shift 2 ;;
    --base) BASE="$2"; shift 2 ;;
    --event) EVENT="$2"; shift 2 ;;
    --users) USERS="$2"; shift 2 ;;
    --users-n) USERS_N="$2"; shift 2 ;;
    --offset) OFFSET="$2"; shift 2 ;;
    --entry-seconds) ENTRY_SECONDS="$2"; shift 2 ;;
    --dist) DIST="$2"; shift 2 ;;
    --sse-hold) echo "--sse-hold: 대기열 SSE는 제거됐다(ADR-023 §2). 대기 상태는 폴링으로 잰다" >&2; exit 2 ;;
    --pre-vus) PRE_VUS="$2"; shift 2 ;;
    --max-vus) MAX_VUS="$2"; shift 2 ;;
    --no-sse) shift ;; # 호환: SSE는 더 없다
    --gens) GENS="$2"; shift 2 ;;
    --start-at) START_AT="$2"; shift 2 ;;
    --warm-seconds) WARM_SECONDS="$2"; shift 2 ;;
    --rate) ARRIVAL_RATE="$2"; shift 2 ;;
    --poll-hold) POLL_HOLD="$2"; shift 2 ;;
    --print-plan) PRINT_PLAN=1; shift ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done
for v in SESSION RUN BASE EVENT USERS USERS_N; do
  [ -n "${!v}" ] || { echo "--$(echo "$v" | tr 'A-Z_' 'a-z-') 가 필요하다" >&2; exit 2; }
done

# k6의 open()은 상대경로를 스크립트 디렉터리(infra/k6) 기준으로 읽는다. 절대경로로 바꿔 넘긴다.
[ -f "$USERS" ] || { echo "사용자 토큰 파일이 없다: $USERS" >&2; exit 2; }
USERS="$(cd "$(dirname "$USERS")" && pwd)/$(basename "$USERS")"
if [ -n "$ARRIVAL_RATE" ]; then
  case "$ARRIVAL_RATE" in *[!0-9]*|0) echo "--rate는 양의 정수여야 한다: $ARRIVAL_RATE" >&2; exit 2 ;; esac
  [ "$DIST" = constant ] || { echo "--rate는 --dist constant에서만 쓴다" >&2; exit 2; }
  ARRIVAL_RATE=$((10#$ARRIVAL_RATE))
fi
for v in "$USERS_N" "$ENTRY_SECONDS" "$OFFSET" "$WARM_SECONDS" "$POLL_HOLD"; do
  case "$v" in ""|*[!0-9]*) echo "--users-n/--entry-seconds/--offset/--warm-seconds/--poll-hold는 정수여야 한다: $v" >&2; exit 2 ;; esac
done
# 앞자리 0(예: 08)은 JSON 숫자가 아니다. 10진수로 정규화한다.
USERS_N=$((10#$USERS_N)); ENTRY_SECONDS=$((10#$ENTRY_SECONDS)); OFFSET=$((10#$OFFSET))
WARM_SECONDS=$((10#$WARM_SECONDS)); POLL_HOLD=$((10#$POLL_HOLD))

# 분산 실행이면 전체 사용자 수와 시작 인덱스에서 이 발생기의 몫을 계산한다.
USERS_TOTAL=$USERS_N OFFSET_BASE=$OFFSET
if [ -n "$GENS" ]; then
  case "$GENS" in ""|*[!0-9]*) echo "--gens는 정수여야 한다: $GENS" >&2; exit 2 ;; esac
  GENS=$((10#$GENS))
  # 여러 발생기에서 --gen을 빠뜨리면 모두 기본값 g1로 같은 몫(같은 사용자)을 건다 — 명시를 강제한다.
  [ "$GEN_SET" = 1 ] || { echo "--gens를 쓸 때는 --gen gK를 반드시 준다(빠뜨리면 발생기들이 같은 사용자를 중복으로 쓴다)" >&2; exit 2; }
  [[ "$GEN" =~ ^g([0-9]+)$ ]] || { echo "--gens를 쓸 때 --gen은 gK 형식이어야 한다: $GEN" >&2; exit 2; }
  K=$((10#${BASH_REMATCH[1]}))
  [ "$GENS" -ge 1 ] && [ "$K" -ge 1 ] && [ "$K" -le "$GENS" ] || { echo "--gen $GEN이 --gens $GENS 범위 밖이다" >&2; exit 2; }
  SHARE=$((USERS_TOTAL / GENS))
  # 발생기 수가 전체 사용자보다 많으면 몫이 0인 발생기가 생긴다 — 일부만 도는 run이 되지 않게 모든 발생기에서 거부한다.
  [ "$SHARE" -ge 1 ] || { echo "발생기 수($GENS)가 전체 사용자($USERS_TOTAL)보다 많다" >&2; exit 2; }
  OFFSET=$((OFFSET_BASE + (K - 1) * SHARE))
  USERS_N=$SHARE
  [ "$K" -eq "$GENS" ] && USERS_N=$((USERS_TOTAL - (GENS - 1) * SHARE))
  [ "$USERS_N" -gt 0 ] || { echo "발생기 몫이 0이다(전체 $USERS_TOTAL, 발생기 $GENS)" >&2; exit 2; }
fi
if [ -n "$START_AT" ]; then
  [[ "$START_AT" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?Z$ ]] || {
    echo "--start-at은 UTC ISO(YYYY-MM-DDTHH:MM:SSZ)여야 한다: $START_AT" >&2; exit 2; }
  # 달력상 없는 날짜(2026-13-45, 02-30)는 형식 검사를 통과하므로 따로 거부한다.
  node -e 'const x=process.argv[1];const d=new Date(x);if(isNaN(d)||d.toISOString().slice(0,19)!==x.slice(0,19))process.exit(1)' "$START_AT" || {
    echo "--start-at이 달력상 없는 시각이다: $START_AT" >&2; exit 2; }
elif [ "$WARM_SECONDS" != 0 ]; then
  echo "--warm-seconds는 --start-at과 함께 쓴다(진입 시작 시각을 기준으로 앞당긴다)" >&2; exit 2
fi

if [ "$PRINT_PLAN" = 1 ]; then
  SA=null; [ -n "$START_AT" ] && SA="\"$START_AT\""
  printf '{"gen":"%s","gens":%s,"usersN":%s,"offset":%s,"usersTotal":%s,"startAt":%s,"warmSeconds":%s,"arrivalRate":%s}\n' \
    "$GEN" "${GENS:-null}" "$USERS_N" "$OFFSET" "$USERS_TOTAL" "$SA" "$WARM_SECONDS" "${ARRIVAL_RATE:-null}"
  exit 0
fi

# 시작 대기 시간 검사: 이미 지났거나 k6 setupTimeout(SETUP_TIMEOUT, 기본 900s)보다 멀면 기다리기 전에 거부한다
# (k6에서 실패하면 토큰 파싱·VU 할당을 다 하고 나서야, 멀면 setupTimeout 뒤에야 끝난다).
if [ -n "$START_AT" ]; then
  WARM_SECONDS="$WARM_SECONDS" node -e 'const t=Date.parse(process.argv[1]);const lim=parseInt(process.env.SETUP_TIMEOUT||"900",10);const w=(t-Date.now())/1000;if(w<0){console.error("--start-at이 이미 "+(-w).toFixed(1)+"초 지났다");process.exit(1)}const warm=Number(process.env.WARM_SECONDS||0);if(w<warm){console.error("--start-at까지 "+w.toFixed(1)+"초 — 연결 미리 맺기("+warm+"초)를 시작할 시각이 지났다");process.exit(1)}if(w>lim-30){console.error("--start-at까지 "+w.toFixed(0)+"초 — SETUP_TIMEOUT("+lim+"s)에서 init 여유 30초를 뺀 값보다 멀다");process.exit(1)}' "$START_AT" || exit 2
fi

OUT="$ROOT/artifacts/loadtest/$SESSION/$RUN"
mkdir -p "$OUT"
[ -e "$OUT/meta-$GEN.json" ] && { echo "이미 있는 run이다: $OUT/meta-$GEN.json — 회차 번호를 올린다" >&2; exit 1; }

# 실행 조건(§7). 결과 문서는 이 파일을 근거로 조건을 적는다.
cat > "$OUT/meta-$GEN.json" <<EOF
{
  "session": "$SESSION", "run": "$RUN", "generator": "$GEN", "host": "$(hostname)",
  "commit": "$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo unknown)",
  "dirty": $(if ! git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1; then echo null; elif [ -n "$(git -C "$ROOT" status --porcelain --untracked-files=no 2>/dev/null)" ]; then echo true; else echo false; fi),
  "startedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "base": "$BASE", "event": "$EVENT", "dist": "$DIST", "usersN": $USERS_N, "offset": $OFFSET,
  "entrySeconds": $ENTRY_SECONDS, "sse": false, "pollHoldSeconds": $POLL_HOLD, "arrivalRate": ${ARRIVAL_RATE:-null},
  "preVus": "${PRE_VUS:-default}", "maxVus": "${MAX_VUS:-default}",
  "gens": ${GENS:-null}, "usersTotal": $USERS_TOTAL, "offsetBase": $OFFSET_BASE, "startAt": $([ -n "$START_AT" ] && echo "\"$START_AT\"" || echo null), "warmSeconds": $WARM_SECONDS,
  "clockSync": "$( (chronyc tracking 2>/dev/null | grep -E 'System time|Leap status' | tr -s ' ' | tr '\n' ';') || echo unknown)"
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
[ -n "$START_AT" ] && K6_ARGS+=(-e START_AT="$START_AT")
[ "$WARM_SECONDS" != 0 ] && K6_ARGS+=(-e WARM_SECONDS="$WARM_SECONDS")
[ -n "$ARRIVAL_RATE" ] && K6_ARGS+=(-e ARRIVAL_RATE="$ARRIVAL_RATE")

# k6가 중단·실패로 끝나도 그때까지의 원시 출력으로 지표는 계산해 둔다. 종료 코드는 마지막에 돌려준다.
set +e
if [ "$POLL_HOLD" = 0 ]; then
  k6 "${K6_ARGS[@]}" "$ROOT/infra/k6/queue-entry-rate.js" > "$OUT/k6-$GEN.log" 2>&1
  RUN_STATUS=$?
else
  # 진입 응답의 대기 토큰을 폴링 발생기로 넘긴다. 종료 코드는 k6 쪽(파이프 첫 단계)을 돌려준다.
  k6 "${K6_ARGS[@]}" -e EMIT_TOKENS=1 "$ROOT/infra/k6/queue-entry-rate.js" 2>&1 \
    | node "$HERE/poll-hold.mjs" --base "$BASE" --out "$OUT/poll-$GEN" --hold "$POLL_HOLD" > "$OUT/k6-$GEN.log"
  RUN_STATUS=${PIPESTATUS[0]}
fi
set -e
# 발생기가 멈춘 시각(§3.3 사후 검사의 --until 대조). 이 파일이 없으면 사후 검사는 run 종료 시각을 확인할 수 없어
# 판정 불가로 끝난다(발생기가 중간에 죽은 run).
printf '{ "generator": "%s", "endedAt": "%s", "status": %s }\n' "$GEN" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$RUN_STATUS" \
  > "$OUT/end-$GEN.json"

T0_ARGS=(); [ -n "$START_AT" ] && T0_ARGS=(--t0 "$START_AT")
node "$HERE/entry-arrivals.mjs" --entry-seconds "$ENTRY_SECONDS" --users-n "$USERS_N" "${T0_ARGS[@]}" "$OUT/entry-$GEN.json" \
  > "$OUT/arrivals-$GEN.json"
echo "run 출력: $OUT"
[ "$RUN_STATUS" = 0 ] || echo "발생기가 0이 아닌 코드로 끝났다($RUN_STATUS) — 이 run의 유효성을 §3.1로 판정한다" >&2
exit "$RUN_STATUS"
