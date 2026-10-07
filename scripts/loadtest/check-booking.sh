#!/usr/bin/env bash
# Downstream E2E(입장자) 시험의 시나리오 기대값 판정. 기존 정합성 검사(check-correctness.sh)와 함께 돌린다 — 그쪽은
# "위반이 있는가"(초과판매·멱등 위반·이벤트 유실 등)를 보고, 이쪽은 "역할마다 설계한 결과로 수렴했는가"를 본다.
#
#   bash scripts/loadtest/check-booking.sh --out artifacts/loadtest/<session>/<run> --event 1826
#
# hold·주문 만료 회수가 끝난 뒤에 돌린다: T0 + hold TTL(300초) + 회수 주기(60초) + 여유. 그 전이면 E(포기) 사용자의
# hold·주문·좌석이 아직 HELD·PENDING이라 위반으로 나온다.
#
# 결과(<run>/booking/): client.json(클라이언트 기대값, booking-expect.mjs client), expect.sql(실행한 SQL),
#   db.csv("<검사>,<위반 수>"), summary.txt.
# 종료 코드: 통과 0, 위반 1, 무효·판정 불가 2. 무효는 사전 조건이 깨진 경우다 — 공연에 다른 사용자 주문이 있음,
# mock이 아닌 승인 결제(세션 오버레이 미적용), 클라이언트 쪽 무효(미입장·401·인원 부족).
#
# DB는 프라이빗이라 클러스터 안 일회용 파드로 붙는다. 자격증명은 api와 같은 ConfigMap·Secret에서 필요한 키만 받고,
# 이 스크립트는 보지도 출력하지도 않는다(check-correctness.sh와 같은 방식). SQL은 SELECT만 한다.
set -uo pipefail

SELF="$(readlink -f "${BASH_SOURCE[0]}" 2>/dev/null || echo "${BASH_SOURCE[0]}")"
HERE="$(cd "$(dirname "$SELF")" && pwd)"
OUT="" EVENT="" NS=flowticket
while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT="$2"; shift 2 ;;
    --event) EVENT="$2"; shift 2 ;;
    *) echo "모르는 인자: $1" >&2; exit 2 ;;
  esac
done
[ -n "$OUT" ] && [ -n "$EVENT" ] || { echo "--out, --event가 필요하다" >&2; exit 2; }
[[ "$EVENT" =~ ^[1-9][0-9]*$ ]] || { echo "--event는 양의 정수다: $EVENT" >&2; exit 2; }
shopt -s nullglob
RES=("$OUT"/results-*.jsonl)
[ "${#RES[@]}" = 1 ] || { echo "결과 파일(results-<gen>.jsonl)이 정확히 하나여야 한다: ${#RES[@]}개" >&2; exit 2; }
D="$OUT/booking"
mkdir -p "$D"

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

echo "==> 1/2 클라이언트 기대값"
node "$HERE/booking-expect.mjs" client "${RES[0]}" > "$D/client.json"
CLIENT=$?
echo "    $(node -e 'const j=require(process.argv[1]);console.log(j.verdict, "무효", j.invalid.length, "위반", j.violations.length)' "$D/client.json" 2>/dev/null || echo "client.json을 읽지 못했다")"

echo "==> 2/2 DB 기대값"
DB=2
if node "$HERE/booking-expect.mjs" sql --event "$EVENT" "${RES[0]}" > "$D/expect.sql" \
   && psql_pod "loadtest-check-booking" < "$D/expect.sql" > "$D/db.csv"; then
  # 사전 조건(무효)과 기대값(위반)을 가른다. 검사 줄 수가 SQL의 SELECT 수와 같아야 한다(중간에 끊긴 출력 방지).
  DB="$(node -e '
const fs = require("fs");
const [csv, sql] = process.argv.slice(1);
const rows = fs.readFileSync(csv, "utf8").trim().split(/\r?\n/).filter(Boolean).map((l) => l.split(","));
const expected = (fs.readFileSync(sql, "utf8").match(/^SELECT /gm) || []).length;
if (rows.length !== expected || rows.some((r) => r.length !== 2 || !/^\d+$/.test(r[1]))) { console.log(2); process.exit(); }
const bad = rows.filter((r) => r[1] !== "0").map((r) => r[0]);
console.log(bad.some((n) => n === "orders_by_other_users" || n === "approved_not_mock") ? 2 : bad.length ? 1 : 0);
' "$D/db.csv" "$D/expect.sql")"
  sed 's/^/    /' "$D/db.csv"
else
  echo "    SQL 실행 실패 — 판정 불가" >&2
fi

V=0
{ [ "$CLIENT" = 2 ] || [ "$DB" = 2 ]; } && V=2
[ "$V" = 0 ] && { [ "$CLIENT" = 1 ] || [ "$DB" = 1 ]; } && V=1
case "$V" in 0) VERDICT="통과" ;; 1) VERDICT="위반" ;; *) VERDICT="무효·판정 불가" ;; esac
{
  echo "client=$CLIENT db=$DB verdict=$VERDICT"
  [ -s "$D/db.csv" ] && grep -v ',0$' "$D/db.csv" | sed 's/^/db 위반: /'
  node -e 'const j=require(process.argv[1]);for(const x of j.invalid)console.log("client 무효: "+x);for(const x of j.violations)console.log("client 위반: "+x)' "$D/client.json" 2>/dev/null
} > "$D/summary.txt"
cat "$D/summary.txt"
exit "$V"
