#!/usr/bin/env bash
# Downstream E2E(입장자) 시험 run 하나를 발생기 한 대에서 실행하고 출력을 run 디렉터리에 모은다.
# 시험 설계·역할은 infra/k6/booking-e2e.js, 판정은 scripts/loadtest/booking-expect.mjs·check-booking.sh.
#
#   infra/loadgen/run-booking.sh --session 20261007-e2e --run e2e-r1 --gen g1 \
#     --base https://flow-ticket.com/api --event 1826 --users ~/tokens.json --start-at 2026-10-07T12:00:00Z [--offset 0] [--entry-lead 20]
#
# 출력(<run>/): meta-<gen>.json·end-<gen>.json(run-entry.sh와 같은 형식 — check-correctness.sh가 시작·종료를 대조한다),
#   k6-<gen>.log(원 로그), results-<gen>.jsonl(VU마다 결과 한 줄 — 토큰 없음), k6-summary-<gen>.json, gen-vmstat·gen-netdev(발생기 자원 — §3.1 무효 판정).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

SESSION="" RUN="" GEN="g1" BASE="" EVENT="" USERS="" START_AT="" OFFSET=0 ENTRY_LEAD=20
while [ $# -gt 0 ]; do
  case "$1" in
    --session) SESSION="$2"; shift 2 ;;
    --run) RUN="$2"; shift 2 ;;
    --gen) GEN="$2"; shift 2 ;;
    --base) BASE="$2"; shift 2 ;;
    --event) EVENT="$2"; shift 2 ;;
    --users) USERS="$2"; shift 2 ;;
    --start-at) START_AT="$2"; shift 2 ;;
    --offset) OFFSET="$2"; shift 2 ;;
    --entry-lead) ENTRY_LEAD="$2"; shift 2 ;;
    *) echo "모르는 인자: $1" >&2; exit 2 ;;
  esac
done
for v in SESSION RUN BASE EVENT USERS START_AT; do
  [ -n "${!v}" ] || { echo "--$(echo "$v" | tr 'A-Z_' 'a-z-')가 필요하다" >&2; exit 2; }
done
[[ "$EVENT" =~ ^[1-9][0-9]*$ ]] || { echo "--event는 양의 정수다: $EVENT" >&2; exit 2; }
[[ "$OFFSET" =~ ^[0-9]+$ ]] || { echo "--offset은 0 이상의 정수다: $OFFSET" >&2; exit 2; }
[[ "$START_AT" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || { echo "--start-at은 UTC ISO(초)다: $START_AT" >&2; exit 2; }
[ -r "$USERS" ] || { echo "토큰 파일을 읽을 수 없다: $USERS" >&2; exit 2; }
[[ "$SESSION$RUN$GEN" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "session·run·gen은 영숫자·._-만 쓴다" >&2; exit 2; }

OUT="$ROOT/artifacts/loadtest/$SESSION/$RUN"
mkdir -p "$OUT"
[ -e "$OUT/meta-$GEN.json" ] && { echo "이미 있는 run이다: $OUT/meta-$GEN.json — 회차 번호를 올린다" >&2; exit 1; }

cat > "$OUT/meta-$GEN.json" <<EOF
{
  "session": "$SESSION", "run": "$RUN", "generator": "$GEN", "host": "$(hostname)", "kind": "booking-e2e",
  "commit": "$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo unknown)",
  "dirty": $(if ! git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1; then echo null; elif [ -n "$(git -C "$ROOT" status --porcelain --untracked-files=no 2>/dev/null)" ]; then echo true; else echo false; fi),
  "startedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "base": "$BASE", "event": "$EVENT", "offset": $OFFSET, "startAt": "$START_AT", "entryLeadSeconds": $ENTRY_LEAD,
  "clockSync": "$( (chronyc tracking 2>/dev/null | grep -E 'System time|Leap status' | tr -s ' ' | tr '\n' ';') || echo unknown)"
}
EOF

vmstat -n 1 > "$OUT/gen-vmstat-$GEN.log" 2>/dev/null &
VMSTAT=$!
( while true; do echo "$(date +%s) $(grep -E '^\s*(eth|ens|enp)' /proc/net/dev | tr -s ' ')"; sleep 1; done ) \
  > "$OUT/gen-netdev-$GEN.log" 2>/dev/null &
NETDEV=$!
trap 'kill $VMSTAT $NETDEV 2>/dev/null || true' EXIT

set +e
k6 run --log-format=raw -e K6_BASE_URL="$BASE" -e EVENT_ID="$EVENT" -e USERS="$USERS" -e USER_OFFSET="$OFFSET" \
  -e START_AT="$START_AT" -e ENTRY_LEAD_SECONDS="$ENTRY_LEAD" --summary-export="$OUT/k6-summary-$GEN.json" \
  "$ROOT/infra/k6/booking-e2e.js" > "$OUT/k6-$GEN.log" 2>&1
RUN_STATUS=$?
set -e
printf '{ "generator": "%s", "endedAt": "%s", "status": %s }\n' "$GEN" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$RUN_STATUS" \
  > "$OUT/end-$GEN.json"
grep '^E2E ' "$OUT/k6-$GEN.log" > "$OUT/results-$GEN.jsonl" || true
echo "run 출력: $OUT (결과 $(wc -l < "$OUT/results-$GEN.jsonl")줄)"
[ "$RUN_STATUS" = 0 ] || echo "k6가 0이 아닌 코드로 끝났다($RUN_STATUS) — 이 run의 유효성을 사전 등록의 무효 조건으로 판정한다" >&2
exit "$RUN_STATUS"
