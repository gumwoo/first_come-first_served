#!/usr/bin/env bash
# 정합성 사후 검증(loadtest-100k-plan §3.3). run이 끝난 뒤, 철거 전에 실행한다.
#
#   bash scripts/loadtest/check-correctness.sh --out artifacts/loadtest/<session>/<run> --since 2026-10-02T05:00:00Z \
#     --until 2026-10-02T05:12:00Z --generators 3
#
# --generators는 이 run에 띄운 발생기 수다. run 디렉터리의 발생기 기록(meta-<gen>.json) 수와 맞아야 한다 —
# 인스턴스째 사라진 발생기는 결과 회수에 나타나지 않아, 이 대조 없이는 보이지 않는다.
# --until은 run 종료 시각(UTC, 발생기가 멈춘 시각)이다. 내보낸 구간(prom/_meta.json)이 --since 이전부터 --until + 45초
# 이후까지를 덮어야 한다 — 파드 대조와 4단계(실시간 조건 사후 재확인)가 이 구간에 기댄다. 덮지 못하면 판정 불가(2)다.
# (감시기의 endedAt으로 대신하지 않는다 — 감시기가 일찍 멈췄으면 구간이 짧게 잡힌 채 통과한다.)
#
# --api-logs <디렉터리>(선택): run 동안 collect-api-logs.mjs로 받아 둔 api 로그(파드별 .log + manifest.json). 주면 사후 `kubectl logs`
#   대신 이것으로 대기열 순서·실효 입장 초과를 판정하고, 파드 대조도 "지금 살아 있는 파드"가 아니라 "run과 겹친 파드를 다 받았는가"로
#   한다(pod-coverage.mjs --collected). HPA 축소로 지워진 파드의 감사 줄을 잃지 않으려고 쓴다(Platform 축 run).
#
# --since는 run 시작 시각(UTC, run 메타의 startedAt과 같은 형식)이다. 이 시각 이후의 주문·이벤트·로그만 본다.
# DB에는 같은 UTC 벽시계로 바꿔 넘긴다 — 앱 컨테이너가 TZ=UTC이고 DB에도 UTC 벽시계가 쌓인다(api-deployment.yaml).
#
# 검사(결과는 <run>/correctness/ 아래):
#   sql.csv            — 초과판매, 일시적 이중 판매, 결제·좌석 상태 불일치, 멱등 위반, 미발행 아웃박스(correctness.sql)
#   queue-order.json   — 대기열 순서 위반(api 로그의 승격 감사 줄 대조, queue-order.mjs)
#   admission-overlap.json — 실효 입장 초과(감사 줄로 재구성한 토큰별 슬롯 점유 구간의 동시 수 > 정원,
#                        admission-overlap.mjs). 정원은 prom/queue_capacity_by_pod.json에서 읽는다(파드·시각 모두 한 값이어야 한다)
#   event-loss.txt     — 발행된 아웃박스 이벤트 중 소비 기록(Redis 멱등 키)이 없는 수
#   watch-summary.json — (같은 run 디렉터리) 실시간 감시기의 결과를 최종 판정에 넣는다. 감시 구간(startedAt~endedAt)이
#                        run을 덮을 때만 그 결과를 이 run의 것으로 본다: 3이면 위반, 0이면 통과, 그 밖은 판정 불가.
#                        단, 먼저 violation.json을 본다: 위반 시각이 [since, until + 30초] 안이면 감시 결과 상태와
#                        상관없이 위반이다. 위반 기록이 있는데 결과가 3이 아니면(두 파일이 섞임) 판정 불가다.
#                        덮지 않으면 판정 불가다(다른 run의 3, 위반 시각을 확인할 수 없는 3 포함 — 그 위반이
#                        실제였다면 4단계 재확인이 같은 식·같은 스크랩 데이터로 대체로 다시 잡을 것으로 본다(추론:
#                        step 10초 < 스크랩 15초라 보통은 점에 잡히지만, 스크랩 지터로 두 샘플이 10초보다 가까우면
#                        건너뛸 수 있어 보장은 아니다)). 파일이 없어도 판정 불가. 감시기 0과
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
# 실효 입장 초과는 감사 로그(승격·회수·이탈)로 토큰별 점유 구간을 재구성해 판정한다(admission-overlap.mjs).
#
# DB·Redis는 프라이빗이라 클러스터 안 일회용 파드로 붙는다. 자격증명은 api와 같은 ConfigMap·Secret에서 필요한 키만
# 받고, 이 스크립트는 보지도 출력하지도 않는다(seed-users.sh와 같은 방식).
# 종료 코드: 위반 없음 0, 위반 있음 1, 검사 자체 실패·판정 불가·인자 오류 2. 위반을 하나라도 찾았으면 검사 일부가 실패했어도
# 1이다 — 감시기(3)·queue-order(1)와 같이 위반이 우선한다. 검사 실패도 함께 있었으면 summary에 둘 다 적는다.
# 예외: admission-overlap 위반은 감사 줄이 빠졌거나 깨졌을 수 있는 run에서는 거짓일 수 있어 판정 불가(2)로 낮춘다 —
# 승격 처리 실패가 있던 run(그 기록을 확인하지 못한 경우 포함), 파드 로그를 다 읽었다고 확인하지 못한 run(pod-coverage),
# 형식이 깨진 감사 줄이 있는 run.
# run 직후 바로 돌리면 아직 발행 중인 아웃박스가 미발행으로 잡힐 수 있다. 아웃박스가 비워진 뒤
# (flowticket_outbox_oldest_pending_age_seconds 0) 실행한다.
set -uo pipefail

# 이 파일을 링크로 실행해도 옆의 도구를 찾도록 실제 경로로 푼다(readlink -f가 없으면 그대로 쓴다).
SELF="$(readlink -f "${BASH_SOURCE[0]}" 2>/dev/null || echo "${BASH_SOURCE[0]}")"
HERE="$(cd "$(dirname "$SELF")" && pwd)"
OUT="" SINCE="" UNTIL="" GENS="" NS=flowticket TOL=1000 API_LOGS=""
while [ $# -gt 0 ]; do
  case "$1" in
    --out|--since|--until|--generators|--tolerance-ms|--api-logs)
      # 값 없이 끝에 오면 set -u 때문에 "$2: unbound variable"로 죽어 종료 1(= 위반)이 된다. 인자 오류는 2다.
      [ $# -ge 2 ] || { echo "$1에 값이 없다" >&2; exit 2; }
      case "$1" in
        --out) OUT="$2" ;;
        --since) SINCE="$2" ;;
        --until) UNTIL="$2" ;;
        --generators) GENS="$2" ;;
        --tolerance-ms) TOL="$2" ;;
        --api-logs) API_LOGS="$2" ;;
      esac
      shift 2 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done
[ -n "$OUT" ] && [ -n "$SINCE" ] && [ -n "$UNTIL" ] && [ -n "$GENS" ] || {
  echo "--out, --since, --until, --generators가 필요하다" >&2; exit 2; }
[[ "$GENS" =~ ^[1-9][0-9]*$ ]] || { echo "--generators는 1 이상의 정수다: $GENS" >&2; exit 2; }
# 형식을 좁혀 SQL에 그대로 넣어도 안전하게 한다(따옴표·세미콜론이 들어올 수 없다).
[[ "$SINCE" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || {
  echo "--since는 YYYY-MM-DDTHH:MM:SSZ(UTC)여야 한다: $SINCE" >&2; exit 2; }
[[ "$UNTIL" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || {
  echo "--until은 YYYY-MM-DDTHH:MM:SSZ(UTC)여야 한다: $UNTIL" >&2; exit 2; }
# 정규식은 형식만 본다. 달력상 없는 시각(2월 30일, 25시)은 왕복 변환으로 거른다 — Date.parse는 넘겨 읽고,
# DB에는 그대로 넘어가 엉뚱한 구간이 된다.
node -e 'for (const x of process.argv.slice(1)) { const d = new Date(x); if (isNaN(d) || d.toISOString() !== x.replace("Z", ".000Z")) { console.error("달력상 없는 시각: " + x); process.exit(2); } }' "$SINCE" "$UNTIL" || exit 2
[[ "$UNTIL" > "$SINCE" ]] || { echo "--until은 --since보다 뒤여야 한다" >&2; exit 2; }
SINCE_DB="${SINCE/T/ }"; SINCE_DB="${SINCE_DB%Z}"
D="$OUT/correctness"
mkdir -p "$D" || exit 2
# 이 실행이 도중에 끝나도 이전 run의 종합 판정이 남아 이 run의 결과로 읽히지 않게 먼저 지운다.
rm -f "$D/summary.txt"
# 옆의 도구를 못 찾으면(링크를 실제 경로로 풀지 못한 경우 등) 각 단계가 node 오류(1)로 끝나 거짓 위반이 된다. 시작 전에 막는다.
for f in correctness.sql queue-order.mjs admission-overlap.mjs pod-coverage.mjs prom-recheck.mjs; do
  [ -f "$HERE/$f" ] || { echo "검사 도구를 찾지 못했다: $HERE/$f — 실제 경로로 실행한다" >&2; exit 2; }
done

# 판정 도구(queue-order·admission-overlap)의 종료 코드 0·1이 결과 JSON과 맞는가. 0이면 위반 0·승격 기록 있음·형식 깨진 줄 0,
# 1이면 위반 > 0이어야 한다. 맞지 않으면(모듈 로드 실패 같은 실행 오류, main이 돌지 않음) 위반·위반 없음이 아니라 판정 불가다.
result_matches() {
  node -e '
const r = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8"));
const num = (x) => typeof x === "number" && Number.isFinite(x);
const ok = num(r.violations) && num(r.admits) && num(r.malformed) &&
  (process.argv[2] === "1" ? r.violations > 0 : r.violations === 0 && r.admits > 0 && r.malformed === 0);
process.exit(ok ? 0 : 1);' "$1" "$2" 2>/dev/null
}

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
# AO: 실효 입장 초과(admission-overlap) 결과. 파드 대조(pod-coverage) 뒤에 확정한다(아래). POD_OK: 파드 로그를 다 읽었는가.
# AO_RAW: 낮추기 전 도구의 종료 코드(사유를 빠짐없이 남기려고 따로 둔다).
AO="" AO_RAW="" POD_OK=0

echo "==> 0/4 내보낸 구간 확인(prom/_meta.json)"
# 파드 대조와 4단계(실시간 조건 사후 재확인)는 내보낸 구간이 run 전체 + 꼬리를 덮어야 믿을 수 있다(재확인 30초, 파드 대조 여유 45초 — 큰 쪽).
if node -e '
const fs = require("fs");
const [out, since, until] = process.argv.slice(1);
const fail = (m) => { console.log(m); process.exit(2); };
let meta;
try { meta = JSON.parse(fs.readFileSync(out + "/prom/_meta.json", "utf8")); }
catch { fail("prom/_meta.json을 읽지 못했다 — export-prom.mjs를 먼저 돌린다(도중에 죽었으면 다시 돌린다)"); }
const s = Number(meta.startSec) * 1000, e = Number(meta.endSec) * 1000;
if (!Number.isFinite(s) || !Number.isFinite(e)) fail("prom/_meta.json에 startSec·endSec가 없다 — export-prom.mjs로 다시 내보낸다");
// 꼬리 30초(실시간 조건 재확인)와 pod-coverage 여유 45초 중 큰 쪽을 덮어야 한다.
const need = Date.parse(until) + 45000;
console.log("export=" + new Date(s).toISOString() + "~" + new Date(e).toISOString() + " run=" + since + "~" + until);
if (s > Date.parse(since)) fail("내보낸 구간이 run 시작보다 늦게 시작한다 — --start를 run 시작 이전으로 다시 내보낸다");
if (e < need) fail("내보낸 구간이 run 종료 + 45초(" + new Date(need).toISOString() + ", pod-coverage 여유)를 덮지 않는다 — --end를 늘려 다시 내보낸다");
' "$OUT" "$SINCE" "$UNTIL" > "$D/export-window.txt" 2>&1; then :
else
  BROKEN=1
  echo "    $(tail -n 1 "$D/export-window.txt")" >&2
fi
# --since·--until은 사람이 준다. 발생기가 남긴 시작(meta-<gen>.json)·종료(end-<gen>.json) 기록과 대조한다 —
# --until을 실제 종료보다 이르게 주면 run 끝 구간이 잘린 채 판정된다. 종료 기록이 없는 발생기가 있으면 판정 불가.
if node -e '
const fs = require("fs");
const [out, since, until, gens] = process.argv.slice(1);
const fail = (m) => { console.log(m); process.exit(2); };
// run-entry.sh는 date -u +%Y-%m-%dT%H:%M:%SZ로 쓴다. 그 형식이고 달력상 있는 시각만 받는다(Date.parse는 "0"도 읽는다).
const iso = (x) => typeof x === "string" && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(x) && new Date(x).toISOString() === x.replace("Z", ".000Z");
const files = fs.readdirSync(out);
const metas = files.filter((f) => /^meta-.+\.json$/.test(f));
// 시작 기록 없이 종료 기록만 있는 발생기도 이 run의 발생기다(시작 기록을 덜 회수했거나 섞임) — 판정 불가.
const orphanEnds = files.filter((f) => /^end-.+\.json$/.test(f) && !files.includes("meta-" + f.slice(4)));
if (orphanEnds.length) fail("시작 기록(meta) 없이 종료 기록만 있는 발생기가 있다: " + orphanEnds.join(", "));
if (metas.length === 0) fail("발생기 실행 기록(meta-<gen>.json)이 없다 — run-entry.sh의 run 디렉터리를 --out으로 준다");
// 인스턴스째 사라진 발생기는 결과 회수(loadgen.sh pull — 지금 running인 인스턴스만)에 나타나지 않아 기록이 통째로
// 없다. 그래서 띄운 발생기 수(--generators)와 기록 수를 대조한다.
if (metas.length !== Number(gens)) fail("발생기 기록이 " + metas.length + "개인데 --generators는 " + gens + "다 — 사라졌거나 덜 회수된 발생기가 있다");
let firstStart = Infinity, lastEnd = -Infinity;
const statuses = [];
for (const m of metas) {
  const gen = m.slice(5, -5);
  let meta, end;
  try { meta = JSON.parse(fs.readFileSync(out + "/" + m, "utf8")); } catch { fail(m + "을 읽지 못했다"); }
  try { end = JSON.parse(fs.readFileSync(out + "/end-" + gen + ".json", "utf8")); }
  catch { fail("발생기 " + gen + "의 종료 기록(end-" + gen + ".json)이 없다 — 발생기가 중간에 멈췄거나 결과를 덜 회수했다"); }
  if (!iso(meta && meta.startedAt) || !iso(end && end.endedAt)) fail("발생기 " + gen + "의 시작·종료 시각이 형식(YYYY-MM-DDTHH:MM:SSZ)에 맞지 않거나 달력상 없는 시각이다");
  const st = Date.parse(meta.startedAt), en = Date.parse(end.endedAt);
  if (en < st) fail("발생기 " + gen + "의 종료(" + end.endedAt + ")가 시작(" + meta.startedAt + ")보다 앞선다");
  // 종료 상태는 판정에 쓰지 않고 남기기만 한다. 0이 아니면 §3.1(시험 무효) 판단의 근거가 된다.
  statuses.push(gen + "=" + end.status);
  firstStart = Math.min(firstStart, st); lastEnd = Math.max(lastEnd, en);
}
console.log("generator status: " + statuses.join(", ") + (statuses.some((x) => !/=0$/.test(x)) ? " (0이 아닌 발생기가 있다 — §3.1 무효 조건을 본다)" : ""));
console.log("generators=" + metas.length + " start=" + new Date(firstStart).toISOString() + " end=" + new Date(lastEnd).toISOString() + " given=" + since + "~" + until);
if (Date.parse(since) > firstStart) fail("--since가 발생기 시작(" + new Date(firstStart).toISOString() + ")보다 늦다");
if (Date.parse(until) < lastEnd) fail("--until이 발생기 종료(" + new Date(lastEnd).toISOString() + ")보다 이르다 — run 끝 구간이 잘린다");
' "$OUT" "$SINCE" "$UNTIL" "$GENS" > "$D/run-window.txt" 2>&1; then :
else
  BROKEN=1
  echo "    $(tail -n 1 "$D/run-window.txt")" >&2
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
# 이전 결과가 summary에 섞이지 않게, 로그 수집 성공 여부와 상관없이 먼저 지운다.
rm -f "$D/queue-order.json" "$D/queue-order.err" "$D/admission-overlap.json" "$D/admission-overlap.err"
# 받아 둔 로그가 있으면 그것을 쓴다(파드별 파일을 합친다 — 줄 형식은 kubectl logs --prefix와 같다).
collect_logs() {
  if [ -n "$API_LOGS" ]; then
    [ -s "$API_LOGS/manifest.json" ] || { echo "    받아 둔 로그에 manifest.json이 없다: $API_LOGS(수집기가 정상 종료하지 않았다)" >&2; return 1; }
    cat "$API_LOGS"/*.log > "$D/api.log"
  else
    kubectl -n "$NS" logs -l app=flowticket-api --since-time="$SINCE" --tail=-1 --prefix --max-log-requests=20 > "$D/api.log"
  fi
}
if collect_logs; then
  node "$HERE/queue-order.mjs" --tolerance-ms "$TOL" "$D/api.log" > "$D/queue-order.json"
  QO=$?
  # 판정 불가 사유는 queue-order.err에 남겨 summary에도 보인다.
  if { [ "$QO" = 0 ] || [ "$QO" = 1 ]; } && ! result_matches "$D/queue-order.json" "$QO"; then
    echo "queue-order의 종료 코드가 $QO인데 결과(queue-order.json)와 맞지 않는다(없거나 읽지 못함 포함) — 판정 불가" >> "$D/queue-order.err"; QO=2
  elif [ "$QO" != 0 ] && [ "$QO" != 1 ] && [ "$QO" != 2 ]; then
    echo "queue-order가 예상 밖의 종료 코드 $QO로 끝났다(실행 실패) — 판정 불가" >> "$D/queue-order.err"; QO=2
  fi
  # 2: 승격 기록 0건·읽지 못한 승격 줄(판정 불가) 또는 실행 실패
  case $QO in 0) ;; 1) FAIL=1 ;; *) BROKEN=1 ;; esac
  # 실효 입장 초과(§3.3): 감사 줄로 토큰별 슬롯 점유 구간을 재구성해 동시 점유 수 > 정원인지 본다.
  # 정원은 내보낸 구간의 파드별 queue_capacity에서 읽는다. 구간 안에서, 또는 파드 사이에 값이 둘 이상이면(측정 중 정원 변경·
  # 파드마다 다른 설정) 판정 불가(이전 결과·사유는 2단계 시작에서 이미 지웠다).
  if CAP="$(node -e '
const b = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8"));
const vals = new Set();
for (const s of (b.data && b.data.result) || []) for (const [, v] of s.values || []) vals.add(v);
if (b.status !== "success") { console.error("queue_capacity_by_pod 조회 상태가 " + b.status + "다"); process.exit(2); }
if (vals.size !== 1) { console.error("파드별 queue_capacity 값이 " + vals.size + "개다"); process.exit(2); }
const c = Number([...vals][0]);
if (!Number.isInteger(c) || c < 1) { console.error("queue_capacity 값이 1 이상 정수가 아니다: " + [...vals][0]); process.exit(2); }
console.log(c);' "$OUT/prom/queue_capacity_by_pod.json" 2>"$D/admission-overlap.err")"; then
    # run 시작 시점에 이미 입장 중인 토큰이 있으면, 그 토큰은 승격 줄이 run 앞이라 로그에 없고 run 안에서 끝나지 않으면
    # 회수 줄도 없어 점유를 통째로 놓친다. 계획서의 회차 리셋(admitexp·admitcount DEL)을 지켰는지 확인한다 —
    # 이벤트별로 run 시작 시각 이하의 마지막 queue_admitted 점이 0이 아니면 판정 불가(시계열이 없거나 run 시작 뒤에야
    # 생긴 시계열은 통과 — 계획서 §3.3의 한계).
    if ! node -e '
const b = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8"));
if (b.status !== "success") { console.error("queue_admitted 조회 상태가 " + b.status + "다"); process.exit(2); }
const since = Date.parse(process.argv[2]) / 1000;
for (const s of (b.data && b.data.result) || []) {
  let last = null;
  for (const [t, v] of s.values || []) if (Number(t) <= since && (last === null || Number(t) > Number(last[0]))) last = [t, v];
  if (last && (typeof last[1] !== "string" || !/^[-+0-9.eE]+$/.test(last[1]) || Number(last[1]) !== 0)) { console.error((typeof last[1] === "string" && /^\d+$/.test(last[1]) ? "run 시작 시점에 입장 중인 토큰 " + last[1] + "개" : "run 시작 시점 입장 수 값을 읽지 못했다: " + JSON.stringify(last[1])) + "(event=" + (s.metric && s.metric.event) + ", 시각 " + new Date(Number(last[0]) * 1000).toISOString() + ")"); process.exit(2); }
}' "$OUT/prom/queue_admitted.json" "$SINCE" 2>>"$D/admission-overlap.err"; then
      echo "    run 시작 시점에 이미 입장 중인 토큰이 있거나 확인하지 못했다(회차 리셋 확인) — 실효 입장 초과 판정 불가" >&2; BROKEN=1
    fi
    node "$HERE/admission-overlap.mjs" --capacity "$CAP" --since "$SINCE" --until "$UNTIL" --tolerance-ms "$TOL" \
      "$D/api.log" > "$D/admission-overlap.json" 2>>"$D/admission-overlap.err"
    AO=$?; AO_RAW=$AO
    # 도구의 0·1은 결과 JSON과 맞아야 믿는다(result_matches). 맞지 않거나 0·1·2가 아닌 종료 코드면 판정 불가이고 사유를 남긴다.
    if { [ "$AO" = 0 ] || [ "$AO" = 1 ]; } && ! result_matches "$D/admission-overlap.json" "$AO"; then
      echo "admission-overlap의 종료 코드가 $AO인데 결과(admission-overlap.json)와 맞지 않는다(없거나 읽지 못함 포함) — 판정 불가" >> "$D/admission-overlap.err"
      AO=2; AO_RAW=2
    elif [ "$AO" != 0 ] && [ "$AO" != 1 ] && [ "$AO" != 2 ]; then
      echo "admission-overlap이 예상 밖의 종료 코드 $AO로 끝났다(실행 실패) — 판정 불가" >> "$D/admission-overlap.err"
      AO=2; AO_RAW=2
    fi
    # 승격 처리 실패가 있던 run에서는 승격·회수 루프가 도중에 끊겨 감사 줄이 빠질 수 있다. 회수 줄이 빠진 토큰은 run 끝까지
    # 점유한 것으로 세어져 거짓 위반이 된다. 그래서 그런 run의 위반(1)은 판정 불가(2)로 낮춘다(진짜 위반이어도 0이 되지는 않는다).
    # 승격 처리 실패 기록을 읽지 못했거나, 조회가 실패했거나, run 구간 [since, until + 30초] 안에 점이 없으면 "실패가 없었다"를
    # 확인하지 못한 것이라 똑같이 낮춘다(4단계 prom-recheck의 "구간 안" 기준과 같다). 값은 숫자 문자열만 읽는다.
    if [ "$AO" = 1 ] && REASON="$(node -e '
const say = (m) => { console.log(m); process.exit(0); };
try {
  const b = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8"));
  if (!b || b.status !== "success") say("승격 처리 실패 조회 상태가 " + (b && b.status) + "라 감사 줄 누락을 배제할 수 없다");
  const lo = Date.parse(process.argv[2]) / 1000, hi = Date.parse(process.argv[3]) / 1000 + 30;
  let points = 0;
  for (const s of (b.data && b.data.result) || []) for (const [t, v] of s.values || []) {
    if (!Number.isFinite(Number(t))) say("승격 처리 실패 기록에 시각을 읽지 못한 점이 있어 감사 줄 누락을 배제할 수 없다");
    if (!(Number(t) >= lo && Number(t) <= hi)) continue;
    points++;
    if (typeof v !== "string" || !/^[-+0-9.eE]+$/.test(v) || !(Number(v) === 0)) say("승격 처리 실패가 있었거나 그 값을 읽지 못해 감사 줄이 빠졌을 수 있다");
  }
  if (points === 0) say("승격 처리 실패 기록에 run 구간 안의 점이 없어 감사 줄 누락을 배제할 수 없다");
} catch { say("승격 처리 실패 기록(prom/queue_admit_tick_failures_rate.json)을 읽지 못해 감사 줄 누락을 배제할 수 없다"); }
process.exit(1);' "$OUT/prom/queue_admit_tick_failures_rate.json" "$SINCE" "$UNTIL")"; then
      echo "$REASON — 위반을 판정 불가로 낮춘다" >> "$D/admission-overlap.err"
      AO=2
    fi
    # FAIL/BROKEN 반영은 파드 대조 뒤(아래)에 한다.
  else
    echo "    정원 값을 확인하지 못했다(prom/queue_capacity_by_pod.json) — 실효 입장 초과 판정 불가" >&2; BROKEN=1
  fi
else
  echo "    api 로그 수집 실패(클러스터 접근)" >&2; BROKEN=1
fi

# run 동안 있었던 파드의 로그를 전부 읽었는지 본다. 빠진 파드가 있으면 대조는 하되 판정 불가다.
# 이전 결과가 summary에 섞이지 않게(목록 조회가 실패해도) 먼저 지운다.
rm -f "$D/pod-coverage.json"
# 로그를 받은 **뒤에** 파드 목록을 읽는다. 순서가 반대면 목록을 읽은 뒤 로그를 받기 전에 지워진 파드가 "읽음"으로
# 남는다. 이 순서에서는 로그를 받은 뒤 지워진 파드가 "빠짐"으로 잡혀 판정 불가 쪽으로만 틀린다.
if [ -n "$API_LOGS" ]; then
  # 받아 둔 로그: run과 겹친 파드를 모두 끝까지 받았는가(지금 살아 있는지는 상관없다).
  node "$HERE/pod-coverage.mjs" --pods "$OUT/prom/api_pods.json" --restarts "$OUT/prom/api_restarts.json" \
    --collected "$API_LOGS/manifest.json" --since "$SINCE" --until "$UNTIL" --meta "$OUT/prom/_meta.json" \
    > "$D/pod-coverage.json" && POD_OK=1 || { echo "    받아 두지 못한 파드 로그가 있다(pod-coverage.json)" >&2; BROKEN=1; }
elif kubectl -n "$NS" get pods -l app=flowticket-api \
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
    --existing "$D/api-pods-now.txt" --first-lines "$D/api-first-lines.txt" --since "$SINCE" --until "$UNTIL" \
    --meta "$OUT/prom/_meta.json" > "$D/pod-coverage.json" && POD_OK=1 || { echo "    로그를 다 읽지 못한 파드가 있다(pod-coverage.json)" >&2; BROKEN=1; }
else
  echo "    api 파드 목록 조회 실패" >&2; BROKEN=1
fi

# 실효 입장 초과 판정을 여기서 확정한다. 회수·이탈 줄은 그 회수를 처리한 파드의 로그에만 남으므로, 지워진 파드·재시작 전
# 컨테이너·회전된 로그의 줄이 빠지면 그 토큰이 run 끝까지(또는 run 시작부터) 점유로 세어져 거짓 위반이 된다. 그래서 파드
# 로그를 다 읽었다고 확인하지 못한 run의 위반(1)은 판정 불가(2)로 낮춘다(대기열 순서는 줄이 빠져도 거짓 위반이 생기지 않는다).
if [ -n "$AO" ]; then
  # 승격 처리 실패로 이미 낮췄어도 사유는 따로 남긴다.
  if [ "$AO_RAW" = 1 ] && [ "$POD_OK" != 1 ]; then
    echo "run 동안 있었던 파드의 로그를 다 읽었다고 확인하지 못해(pod-coverage) 회수·이탈 줄이 빠졌을 수 있다 — 위반을 판정 불가로 낮춘다" >> "$D/admission-overlap.err"
    AO=2
  fi
  case $AO in 0) ;; 1) FAIL=1 ;; *) BROKEN=1 ;; esac
fi

echo "==> 3/4 이벤트 유실(아웃박스 PUBLISHED vs 소비자 멱등 키)"
# 이전 결과가 summary에 섞이지 않게 먼저 지운다.
rm -f "$D/event-loss.txt"
# 소비자는 처리한 이벤트를 dedup:order-event:<eventId>로 24시간 남긴다(OrderEventConsumer). 24시간 안에 돌린다.
if printf "\\\\copy (SELECT id FROM outbox_events WHERE status = 'PUBLISHED' AND created_at >= '%s') TO STDOUT\n" "$SINCE_DB" \
     | psql_pod "loadtest-check-outbox" > "$D/published-ids.txt"; then
  n_pub="$(grep -c . "$D/published-ids.txt" || true)"
  if [ "$n_pub" -eq 0 ]; then
    echo "published=0 missing=0" > "$D/event-loss.txt"
  else
    # redis:7 이미지에는 CA 묶음이 없어 ElastiCache 인증서 검증이 실패한다(측정 세션 20261005-1440에서 확인).
    # CA 묶음이 있는 alpine 이미지로 검증을 켠 채 접속한다(--insecure로 검증을 끄지 않는다).
    REDIS_OVERRIDES="$(cat <<'EOF'
{"spec":{"restartPolicy":"Never","containers":[{"name":"redis","image":"redis:7-alpine","stdin":true,"stdinOnce":true,
 "env":[
  {"name":"RHOST","valueFrom":{"configMapKeyRef":{"name":"flowticket-api-config","key":"REDIS_HOST"}}},
  {"name":"RPORT","valueFrom":{"configMapKeyRef":{"name":"flowticket-api-config","key":"REDIS_PORT"}}}],
 "command":["sh","-c","redis-cli --tls --cacert /etc/ssl/certs/ca-certificates.crt -h \"$RHOST\" -p \"$RPORT\" --no-raw"]}]}}
EOF
)"
    # 한 줄에 EXISTS 하나. 결과는 줄마다 (integer) 0|1.
    sed 's/^/EXISTS dedup:order-event:/' "$D/published-ids.txt" \
      | kubectl run loadtest-check-redis -n "$NS" --rm -i --quiet --restart=Never --pod-running-timeout=5m \
          --image=redis:7-alpine --overrides="$REDIS_OVERRIDES" > "$D/dedup-exists.txt"
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
rm -f "$D/prom-recheck.json" "$D/prom-recheck.err"
node "$HERE/prom-recheck.mjs" --prom-dir "$OUT/prom" --since "$SINCE" --until "$UNTIL" > "$D/prom-recheck.json"
PR=$?
# 종료 코드 0·1이 결과와 맞아야 믿는다(0이면 위반·판정 불가 사유 없음, 1이면 위반 있음). 모듈 로드 실패 같은 실행 오류의 1을
# 위반으로 세지 않는다. 사유는 prom-recheck.err에 남겨 summary에도 보인다.
if { [ "$PR" = 0 ] || [ "$PR" = 1 ]; } && ! node -e '
const r = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8"));
const ok = Array.isArray(r.violations) && Array.isArray(r.problems) &&
  (process.argv[2] === "1" ? r.violations.length > 0 : r.violations.length === 0 && r.problems.length === 0);
process.exit(ok ? 0 : 1);' "$D/prom-recheck.json" "$PR" 2>/dev/null; then
  echo "prom-recheck의 종료 코드가 $PR인데 결과(prom-recheck.json)와 맞지 않는다(없거나 읽지 못함 포함) — 판정 불가" >> "$D/prom-recheck.err"; PR=2
elif [ "$PR" != 0 ] && [ "$PR" != 1 ] && [ "$PR" != 2 ]; then
  echo "prom-recheck가 예상 밖의 종료 코드 $PR로 끝났다(실행 실패) — 판정 불가" >> "$D/prom-recheck.err"; PR=2
fi
case $PR in
  0) ;;
  1) echo "    내보낸 구간에서 실시간 조건 위반을 찾았다(prom-recheck.json)" >&2; FAIL=1 ;;
  *) echo "    실시간 조건을 다시 확인하지 못했다 — 관측 공백·승격 처리 실패·파일 문제(prom-recheck.json)" >&2; BROKEN=1 ;;
esac

echo "==> 실시간 감시기 결과(watch-summary.json)"
# 감시기 0과 4단계(사후 재확인) 통과가 함께 있어야 실시간 조건에 위반이 없었다. 감시기 결과를 최종 판정에 넣는다.
# 해석 중 예외(빈 파일·null 등)는 모두 판정 불가(2)다 — node가 예외로 죽으면 종료 1(= 위반)로 읽히므로 잡는다.
node -e '
try {
  const fs = require("fs");
  const [summaryPath, sinceArg, untilArg, violationPath] = process.argv.slice(1);
  const since = Date.parse(sinceArg), until = Date.parse(untilArg);
  // 감시기는 toISOString()으로 쓴다. 그 형식이고 달력상 있는 시각만 받는다 — Date.parse는 "0"·"9999"도 너그럽게
  // 읽고, 2월 30일·24시는 넘겨 읽는다(왕복 변환이 같아야 한다).
  const ISO = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,3})?Z$/;
  const real = (x) => { if (!ISO.test(String(x))) return false; const d = new Date(x); return !isNaN(d) && d.toISOString() === (/\.\d{3}Z$/.test(x) ? x : x.replace(/(\.\d{1,2})?Z$/, (m, f) => (f ? f.padEnd(4, "0") : ".000") + "Z")); };

  // 1) 위반 기록을 먼저 본다. 감시기가 이 run 구간(+ 꼬리 30초) 안에서 위반을 기록했다면, 감시 결과 파일의 상태
  //    (덮음 여부, 결과 코드, 시각 형식, 같은 디렉터리에서 다시 돌려 덮어쓴 summary)와 상관없이 이 run의 위반이다.
  //    위반은 판정 불가보다 우선한다.
  let v = null;
  try { v = JSON.parse(fs.readFileSync(violationPath, "utf8")); } catch {}
  const vt = v && real(v.t) ? Date.parse(v.t) : NaN;
  if (vt >= since && vt <= until + 30000) {
    console.log("위반 시각 " + v.t + "이 이 run 구간 안이다(violation.json)");
    process.exit(1);
  }

  // 2) 감시 결과. 이 run을 덮는 감시의 결과만 이 run의 것으로 본다.
  let s;
  try { s = JSON.parse(fs.readFileSync(summaryPath, "utf8")); }
  catch { console.log("watch-summary.json이 없거나 읽을 수 없다 — 실시간 감시기를 같은 run 디렉터리로 돌리지 않았다"); process.exit(2); }
  if (!s || typeof s !== "object") { console.log("watch-summary.json 내용이 객체가 아니다"); process.exit(2); }
  console.log("exitCode=" + s.exitCode + " " + s.verdict + " (" + s.startedAt + " ~ " + s.endedAt + ")");
  if (!real(s.startedAt) || !real(s.endedAt)) {
    console.log("감시 구간 시각(startedAt·endedAt)이 ISO UTC 형식이 아니거나 달력상 없는 시각이다");
    process.exit(2);
  }
  const covers = Date.parse(s.startedAt) <= since && Date.parse(s.endedAt) >= until;
  // 감시기는 위반이 있을 때만 violation.json을 쓰고, 그러면 결과는 반드시 3이다. 위반 기록이 있는데 결과가 3이 아니면
  // 두 파일이 다른 감시에서 왔다(섞임) — 어느 쪽도 믿을 수 없으므로 판정 불가다.
  if (fs.existsSync(violationPath) && s.exitCode !== 3) {
    console.log("violation.json이 있는데 감시 결과가 3이 아니다(" + s.exitCode + ") — 감시 결과 파일이 섞였다");
    process.exit(2);
  }
  if (s.exitCode === 3) {
    // 위반 기록이 구간 안이었다면 위에서 끝났다. 여기서는 위반 시각이 구간 밖이거나 확인할 수 없다.
    if (covers) {
      console.log("감시가 run을 덮고 위반을 기록했다(violation.json의 시각은 " + (Number.isFinite(vt) ? "구간 밖 " + v.t : "확인 불가") + ")");
      process.exit(1);
    }
    console.log("감시가 run을 덮지 않고, 위반 시각이 " + (Number.isFinite(vt) ? "이 run 구간 밖(" + v.t + ")" : "확인할 수 없다(violation.json 없음·형식 오류)") + " — 다른 run의 위반일 수 있다");
    process.exit(2);
  }
  if (!covers) {
    console.log("감시 구간이 run(" + sinceArg + " ~ " + untilArg + ")을 덮지 않는다");
    process.exit(2);
  }
  process.exit(s.exitCode === 0 ? 0 : 2);
} catch (e) {
  console.log("watch-summary.json 해석 실패: " + (e && e.message));
  process.exit(2);
}
' "$OUT/watch-summary.json" "$SINCE" "$UNTIL" "$OUT/violation.json" > "$D/watch-verdict.txt" 2>&1
case $? in
  0) ;;
  1) echo "    실시간 감시기가 위반을 기록했다" >&2; FAIL=1 ;;
  *) echo "    실시간 감시기 결과를 이 run의 통과로 볼 수 없다 — $(tail -n 1 "$D/watch-verdict.txt")" >&2; BROKEN=1 ;;
esac

{
  echo "since=$SINCE"
  echo "--- sql (검사,위반 수)"; cat "$D/sql.csv" 2>/dev/null
  # require는 상대경로를 모듈 이름으로 읽는다. 파일로 읽는다.
  # 판정 불가 사유(run 시작 시점 입장 토큰, 정원 불일치, 승격 처리 실패·파드 로그 누락으로 낮춤, 도구 자체의 판정 불가)는
  # .err에 남는다. summary에도 보인다.
  echo "--- admission-overlap(실효 입장 초과)"; [ -s "$D/admission-overlap.err" ] && sed 's/^/  사유: /' "$D/admission-overlap.err"; node -e 'try{const r=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));console.log(r.admits===0?"판정 불가(승격 기록 0건)":"capacity="+r.capacity+" admits="+r.admits+" violations="+r.violations+" malformed="+r.malformed+" duplicateAdmits="+r.duplicateAdmits+" endsBeforeAdmit="+r.endsBeforeAdmit+" maxConcurrent="+JSON.stringify(Object.fromEntries(Object.entries(r.events).map(([k,v])=>[k,v.maxConcurrent]))))}catch{console.log("판정 불가")}' "$D/admission-overlap.json" 2>/dev/null
  echo "--- queue-order"; [ -s "$D/queue-order.err" ] && sed 's/^/  사유: /' "$D/queue-order.err"; node -e 'try{const r=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));console.log(r.admits===0?"판정 불가(승격 기록 0건)":"admits="+r.admits+" violations="+r.violations+" malformed="+r.malformed)}catch{console.log("판정 불가")}' "$D/queue-order.json" 2>/dev/null
  echo "--- pod-coverage"; node -e 'try{const r=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));console.log(r.complete?"지워진 파드·재시작·run 구간 로그 회전 없음("+r.seen.length+"개)":"판정 불가 — 빠진 파드 "+r.missing.length+", run 중 재시작 "+r.restartedInRun.length+", 이후 재시작·불명 "+r.restartedAfterOrUnknown.length+", 로그 회전·불명 "+r.rotatedOrUnknown.length)}catch{console.log("판정 불가(확인 실패)")}' "$D/pod-coverage.json" 2>/dev/null
  echo "--- 내보낸 구간(파드 대조·승격 처리 실패의 전제)"; cat "$D/export-window.txt" 2>/dev/null
  echo "--- run 시각 대조(발생기 시작·종료 기록 vs --since·--until)"; cat "$D/run-window.txt" 2>/dev/null
  echo "--- event-loss"; cat "$D/event-loss.txt" 2>/dev/null
  echo "--- 실시간 조건 사후 재확인(내보낸 구간, run 종료 + 30초까지)"; [ -s "$D/prom-recheck.err" ] && sed 's/^/  사유: /' "$D/prom-recheck.err"; node -e 'try{const r=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));console.log(r.violations.length?"위반: "+r.violations.join("; ")+(r.problems.length?" / 판정 불가 사유도 있음: "+r.problems.join("; "):""):r.problems.length?"판정 불가: "+r.problems.join("; "):"위반 없음(관측 점 "+r.observedPoints+"개, 샘플 나이 최대 "+r.sampleAgeMaxSec+"초)")}catch{console.log("판정 불가(확인 실패)")}' "$D/prom-recheck.json" 2>/dev/null
  echo "--- 실시간 감시기(watch-summary.json)"; cat "$D/watch-verdict.txt" 2>/dev/null
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
