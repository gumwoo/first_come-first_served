#!/usr/bin/env bash
# 롤링 배포 무중단 측정 — IMP-015 §8의 미검증 조건을 재현한다.
#
# IMP-015가 검증한 것: 이미지 동일 + web 단독 롤링 → 3,600건 5xx 0.
# 검증하지 못한 것: **이미지 태그 변경 + web·api 동시 롤링** → 168건 중 502 1건.
# 이 스크립트는 후자를 §3과 같은 부하(25 rps)에서, 표본을 키워 재현한다.
#
# 왜 스크립트인가: IMP-015의 측정은 손으로 돌렸다("병렬 6워커, 약 25 req/s"). 그래서
#   1) 같은 조건으로 다시 돌릴 수 없고,
#   2) 클러스터를 띄운 뒤에야 절차를 만들게 되어 비용이 붙는 시간에 실수가 난다
#      (TS-034가 정확히 그렇게 나왔다 — 경로·생성기 자원·노드 경합 3연타).
# 절차를 코드로 고정해 두면 재현이 "다시 실행"이 된다.
#
# 사용:
#   bash scripts/rolling-deploy-test.sh                 # 직전 ECR 이미지로 web+api 동시 롤링
#   bash scripts/rolling-deploy-test.sh --tag <sha40>   # 특정 이미지로
#   bash scripts/rolling-deploy-test.sh --scope web     # web 단독(IMP-015 §3 조건 재현)
#   bash scripts/rolling-deploy-test.sh --rate 25 --duration 4m --warmup 45
#
# 종료 코드: 5xx가 1건이라도 있으면 non-zero. 이 스크립트가 주장하는 것은
# "측정을 돌렸다"가 아니라 **"무중단이었다"**이고, 종료 코드가 그 판정이어야 한다.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
REGION="${AWS_REGION:-ap-northeast-2}"
NS=flowticket
ARGO_NS=argocd
APP=flowticket
JOB=k6-rolling
CM=k6-rolling

RATE=25
DURATION=4m
WARMUP=45
SCOPE=both
TAG=""

while [ $# -gt 0 ]; do
  case "$1" in
    --tag)      TAG="$2"; shift 2;;
    --rate)     RATE="$2"; shift 2;;
    --duration) DURATION="$2"; shift 2;;
    --warmup)   WARMUP="$2"; shift 2;;
    --scope)    SCOPE="$2"; shift 2;;
    -h|--help)  sed -n '2,22p' "$0"; exit 0;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2;;
  esac
done
case "$SCOPE" in
  both) TARGETS="api web";;
  api)  TARGETS="api";;
  web)  TARGETS="web";;
  *) echo "--scope는 both|web|api" >&2; exit 2;;
esac

WORK="$(mktemp -d)"
AUTOMATED=""
RESTORE_NEEDED=0

cleanup() {
  rc=$?
  # ⚠️ 자동 동기화 복원은 **무슨 일이 있어도** 해야 한다. 여기서 빠지면 클러스터가 GitOps
  # 밖에 남는다 — 그게 정확히 TS-021 §6-1에서 며칠짜리 드리프트를 만든 상태다.
  if [ "$RESTORE_NEEDED" -eq 1 ]; then
    echo "==> ArgoCD 자동 동기화 복원"
    if kubectl -n "$ARGO_NS" patch application "$APP" --type=merge \
         -p "{\"spec\":{\"syncPolicy\":{\"automated\":$AUTOMATED}}}" >/dev/null 2>&1; then
      echo "    복원됨 — ArgoCD가 Git 태그로 되돌린다(측정 종료 후이므로 결과에 영향 없음)"
    else
      echo "!!! 자동 동기화 복원 실패 — 손으로 확인하라:" >&2
      echo "    kubectl -n $ARGO_NS get application $APP -o jsonpath='{.spec.syncPolicy}'" >&2
      rc=1
    fi
  fi
  rm -rf "$WORK"
  exit "$rc"
}
trap cleanup EXIT

say() { echo "==> $*"; }

# ── 0. 전제 ──────────────────────────────────────────────────────────
say "0/8 전제 확인"
for c in kubectl aws jq python; do
  command -v "$c" >/dev/null || { echo "$c 가 없다" >&2; exit 1; }
done
kubectl get ns "$NS" >/dev/null 2>&1 || {
  echo "클러스터에 $NS 네임스페이스가 없다 — scripts/bring-up.sh 먼저" >&2; exit 1; }
DOMAIN="$(kubectl -n "$NS" get ingress flowticket -o jsonpath='{.spec.rules[0].host}' 2>/dev/null || true)"
[ -n "$DOMAIN" ] || { echo "Ingress에서 도메인을 읽지 못했다" >&2; exit 1; }
# 측정 전 상태가 이미 깨져 있으면 롤링 탓으로 오독하게 된다. 여기서 막는다.
CODE="$(curl -s -o /dev/null -w '%{http_code}' "https://$DOMAIN" --max-time 20 || true)"
[ "$CODE" = "200" ] || { echo "측정 전 상태가 정상이 아니다: https://$DOMAIN → $CODE" >&2; exit 1; }
echo "    domain=$DOMAIN scope=$SCOPE rate=$RATE duration=$DURATION warmup=${WARMUP}s"

# ── 1. 롤링에 쓸 이미지 결정 ────────────────────────────────────────
say "1/8 이미지 태그 결정"
cur_tag() {
  kubectl -n "$NS" get deploy "flowticket-$1" \
    -o jsonpath='{.spec.template.spec.containers[0].image}' | sed 's/.*://'
}
CUR_API="$(cur_tag api)"
CUR_WEB="$(cur_tag web)"
echo "    현재 api=$CUR_API web=$CUR_WEB"

if [ -z "$TAG" ]; then
  # 두 리포에 **모두** 있는 태그 중, 현재 돌고 있는 두 태그가 **아닌** 가장 최근 것.
  #
  # ⚠️ 여기서 두 가지를 반드시 지켜야 한다. 어기면 스크립트가 검증하려는 조건 자체가 깨진다.
  #
  # 1) 정렬은 imagePushedAt으로만 한다. 태그가 커밋 SHA라 **사전순은 시간순과 무관하다.**
  #    (이전 구현이 sort_by(imagePushedAt)으로 뽑아 놓고 sort -u로 그 순서를 날렸다.)
  # 2) CUR_API와 CUR_WEB을 **둘 다** 제외한다. CUR_API만 걸러내면 CUR_WEB이 선택될 수 있고,
  #    그러면 --scope both에서 api는 롤링되지만 web은 "같은 태그로 교체"라 아무 일도 안 난다.
  #    즉 **"이미지 변경 + web·api 동시 롤링"이 아닌 조건으로 측정이 진행된다.**
  aws ecr describe-images --repository-name flowticket-api --region "$REGION" \
    --query 'imageDetails[].{pushed:imagePushedAt,tags:imageTags}' --output json > "$WORK/api.json" 2>/dev/null || echo '[]' > "$WORK/api.json"
  aws ecr describe-images --repository-name flowticket-web --region "$REGION" \
    --query 'imageDetails[].imageTags[]' --output json > "$WORK/web.json" 2>/dev/null || echo '[]' > "$WORK/web.json"
  # pushed는 ISO8601이고 같은 계정·리전이라 오프셋이 동일하므로 문자열 정렬로 시간순이 된다.
  TAG="$(jq -r --slurpfile web "$WORK/web.json" --arg cura "$CUR_API" --arg curw "$CUR_WEB" '
      ($web[0] // []) as $w
      | [ .[] | select(.tags != null) | . as $i | .tags[] | {tag: ., pushed: $i.pushed} ]
      | map(select(.tag | test("^[0-9a-f]{40}$")))
      | map(select(.tag != $cura and .tag != $curw))
      | map(select(.tag as $t | $w | index($t)))
      | sort_by(.pushed)
      | last
      | if . == null then "" else .tag end
    ' "$WORK/api.json" 2>/dev/null || true)"
fi
if [ -z "$TAG" ]; then
  echo "롤링에 쓸 다른 이미지를 찾지 못했다. --tag <sha40>으로 지정하라." >&2
  echo "  두 리포에 공통으로 있으면서 현재 api($CUR_API)·web($CUR_WEB)과 다른 태그가 필요하다." >&2
  exit 1
fi
# --tag로 직접 준 경우에도 같은 조건을 강제한다. 여기서 통과시키면 "롤링했는데 아무것도
# 안 바뀐" 상태로 측정이 돌아가고, 그 결과는 무중단의 증거가 되지 못한다.
for t in $TARGETS; do
  case "$t" in api) c="$CUR_API";; web) c="$CUR_WEB";; esac
  if [ "$TAG" = "$c" ]; then
    echo "지정한 태그가 현재 $t 태그와 같다($TAG) — $t는 롤링되지 않는다." >&2
    echo "  --scope $SCOPE의 조건을 만족하지 못하므로 중단한다." >&2
    exit 1
  fi
done
echo "    롤링 대상 태그=$TAG"

# ⚠️ 정직하게 기록한다. 이 태그가 현재와 **다른 다이제스트**여야 이미지 pull이 실제로 일어난다.
# 같은 내용에 다른 태그만 단 것이면 레이어가 캐시돼 pull이 즉시 끝나고, 우리가 의심하는
# "pull 지연 → Ready 지연" 경로를 재현하지 못한다. 그 경우 결과는 조건 미달로 읽어야 한다.
#
# **롤링 대상 전부**를 확인한다. api만 보면, api는 새 이미지인데 web은 같은 다이제스트인
# 경우를 놓친다 — web 쪽에서는 pull 지연 가설이 재현되지 않는데도 "확인함"으로 남는다.
digest_of() {
  aws ecr describe-images --repository-name "flowticket-$1" --region "$REGION" \
    --image-ids imageTag="$2" --query 'imageDetails[0].imageDigest' --output text 2>/dev/null || echo "?"
}
DIGEST_NOTE=""
for t in $TARGETS; do
  case "$t" in api) c="$CUR_API";; web) c="$CUR_WEB";; esac
  if [ "$(digest_of "$t" "$c")" = "$(digest_of "$t" "$TAG")" ]; then
    DIGEST_NOTE="$DIGEST_NOTE $t=동일"
    echo "    ⚠️ $t 다이제스트 동일 — 레이어 캐시로 pull 지연을 재현하지 못한다"
  else
    DIGEST_NOTE="$DIGEST_NOTE $t=상이"
    echo "    $t 다이제스트 상이 — 실제 pull이 일어난다"
  fi
done
DIGEST_NOTE="${DIGEST_NOTE# }"

# ── 2. ArgoCD 자동 동기화 일시 중지 ─────────────────────────────────
say "2/8 ArgoCD 자동 동기화 일시 중지"
# selfHeal: true가 켜져 있어 kubectl set image를 곧바로 되돌린다. 그러면 측정 구간에
# **두 번째 롤링**이 겹쳐 들어와 원인 분리가 불가능해진다. 원본을 보관했다가 trap에서 복원한다.
AUTOMATED="$(kubectl -n "$ARGO_NS" get application "$APP" \
  -o jsonpath='{.spec.syncPolicy.automated}' 2>/dev/null || true)"
if [ -n "$AUTOMATED" ] && [ "$AUTOMATED" != "null" ]; then
  kubectl -n "$ARGO_NS" patch application "$APP" --type=json \
    -p '[{"op":"remove","path":"/spec/syncPolicy/automated"}]' >/dev/null
  RESTORE_NEEDED=1
  echo "    중지됨(원본 보관: $AUTOMATED) — 종료 시 자동 복원"
else
  echo "    이미 꺼져 있음 — 건드리지 않는다"
fi

# ── 3. 부하 생성기 기동 ──────────────────────────────────────────────
say "3/8 k6 Job 기동"
EVENT_ID="$(curl -s "https://$DOMAIN/api/events?status=ON_SALE&size=20" --max-time 20 \
  | jq -r '.data.items[0].id // empty' | tr -d '\r' || true)"
if [ -n "$EVENT_ID" ]; then echo "    대상 이벤트=$EVENT_ID"
else echo "    이벤트 미지정 — k6가 setup()에서 탐색한다"; fi

kubectl -n "$NS" delete job "$JOB" --ignore-not-found >/dev/null
kubectl -n "$NS" create configmap "$CM" \
  --from-file=rolling-availability.js="$ROOT/infra/k6/rolling-availability.js" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null

# 매니페스트의 env를 인자로 덮어써서 적용한다. 매니페스트를 직접 고치면 다음 실행이
# 이전 실행의 값을 물려받아 "같은 조건으로 다시 돌린다"는 전제가 깨진다.
python - "$ROOT/infra/k6/k6-rolling-job.yaml" "$WORK/job.yaml" \
  "https://$DOMAIN/api" "$RATE" "$DURATION" "$EVENT_ID" <<'PYEOF'
import io, re, sys
src, dst, base, rate, dur, ev = sys.argv[1:7]
s = io.open(src, encoding="utf-8").read()

def setenv(name, val):
    global s
    pat = r'(\{ name: %s, value: )"[^"]*"( \})' % name
    new, n = re.subn(pat, lambda m: m.group(1) + '"%s"' % val + m.group(2), s, count=1)
    if n != 1:
        raise SystemExit("env %s 치환 실패 — k6-rolling-job.yaml의 env 형식이 바뀌었다" % name)
    s = new

setenv("BASE_URL", base)
setenv("RATE", rate)
setenv("RUN_FOR", dur)
setenv("EVENT_ID", ev)
io.open(dst, "w", encoding="utf-8", newline="").write(s)
PYEOF

kubectl apply -f "$WORK/job.yaml" >/dev/null
kubectl -n "$NS" wait --for=condition=Ready pod -l app=k6-rolling --timeout=120s >/dev/null
K6POD="$(kubectl -n "$NS" get pod -l app=k6-rolling -o jsonpath='{.items[0].metadata.name}')"
echo "    pod=$K6POD"

# ── 4. 워밍업 ────────────────────────────────────────────────────────
say "4/8 워밍업 ${WARMUP}s (롤링 전 기준선)"
# 롤링 직전 구간의 5xx가 0이어야 "롤링이 원인"이라고 말할 수 있다. 이 구간이 없으면
# 측정 전체가 이미 불안정했을 가능성을 배제하지 못한다.
sleep "$WARMUP"
BASELINE_BAD="$(kubectl -n "$NS" logs "$K6POD" 2>/dev/null | grep -c 'NON2XX' || true)"
echo "    롤링 전 non-2xx = $BASELINE_BAD"
if [ "${BASELINE_BAD:-0}" -gt 0 ]; then
  echo "    ⚠️ 롤링 전부터 실패가 있다 — 이번 측정으로 롤링을 탓할 수 없다"
fi

# ── 5. 롤링 유발 ─────────────────────────────────────────────────────
say "5/8 이미지 교체 → 롤링 시작"
T0="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
ECR_PREFIX="$(kubectl -n "$NS" get deploy flowticket-api \
  -o jsonpath='{.spec.template.spec.containers[0].image}' | sed 's#/flowticket-api:.*##')"
for t in $TARGETS; do
  kubectl -n "$NS" set image "deploy/flowticket-$t" "$t=$ECR_PREFIX/flowticket-$t:$TAG" >/dev/null
done
echo "    T0=$T0  교체 대상: $TARGETS"

# ── 6. 롤링 추적 ─────────────────────────────────────────────────────
say "6/8 롤링 진행 추적"
for t in $TARGETS; do
  kubectl -n "$NS" rollout status "deploy/flowticket-$t" --timeout=10m
done
T1="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# ⚠️ rollout status가 빨리 끝났다고 롤링이 없었다고 볼 수 없다 — IMP-015 §4에서 6초 만에
# 끝나 의심했고, ReplicaSet 이력으로 실제 교체를 확인했다. 그 확인을 자동화한다.
{
  echo "# ReplicaSet 이력 — 교체가 실제로 일어났는지"
  for t in $TARGETS; do
    echo "## flowticket-$t"
    kubectl -n "$NS" get rs -l "app=flowticket-$t" \
      -o custom-columns=NAME:.metadata.name,DESIRED:.spec.replicas,READY:.status.readyReplicas,CREATED:.metadata.creationTimestamp \
      --sort-by=.metadata.creationTimestamp 2>/dev/null || true
  done
} | tee "$WORK/replicasets.txt"

# 생성기가 스스로 병목이었는지 — TS-034에 대한 답을 미리 확보한다.
# 이게 없으면 "생성기 탓 아니냐"는 반박에 답할 수 없다.
kubectl top pod -n "$NS" "$K6POD" --no-headers 2>/dev/null | sed 's/^/    생성기 사용량: /' \
  || echo "    생성기 사용량: 기록 실패(metrics-server 확인)"

# ── 7. 측정 종료 대기 ────────────────────────────────────────────────
say "7/8 부하 종료 대기"
kubectl -n "$NS" wait --for=condition=complete "job/$JOB" --timeout=15m >/dev/null 2>&1 || true
kubectl -n "$NS" logs "$K6POD" > "$WORK/k6.log" 2>&1 || true

# ── 8. 판정 ──────────────────────────────────────────────────────────
say "8/8 결과"
OUT="$ROOT/rolling-test-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$OUT"
cp "$WORK/k6.log" "$WORK/replicasets.txt" "$OUT/" 2>/dev/null || true
{
  echo "scope=$SCOPE rate=$RATE duration=$DURATION warmup=${WARMUP}s"
  echo "tag: api $CUR_API / web $CUR_WEB -> $TAG (다이제스트 $DIGEST_NOTE)"
  echo "rollout: $T0 -> $T1"
  echo "baseline_non2xx(롤링 전): $BASELINE_BAD"
} > "$OUT/conditions.txt"

SUMMARY="$(grep -o 'SUMMARY_JSON .*' "$WORK/k6.log" | tail -1 | sed 's/^SUMMARY_JSON //' || true)"
if [ -z "$SUMMARY" ]; then
  echo "k6 요약을 얻지 못했다 — 로그: $OUT/k6.log" >&2
  exit 1
fi
TOTAL="$(echo "$SUMMARY" | jq -r .total)"
BAD="$(echo "$SUMMARY" | jq -r .non2xx)"

echo
echo "    총 요청   $TOTAL"
echo "    non-2xx   $BAD"
echo "    p95/p99   $(echo "$SUMMARY" | jq -r .p95)ms / $(echo "$SUMMARY" | jq -r .p99)ms"
echo "    롤링      $T0 → $T1  (대상: $TARGETS → $TAG, 다이제스트 $DIGEST_NOTE)"
echo "    조건·로그 $OUT/"
echo

if [ "$BAD" -gt 0 ]; then
  echo "    실패한 요청 — 롤링 시작(T0) 기준 상대 시각:"
  T0S="$(date -u -d "$T0" +%s)"
  grep -o 'NON2XX ts=[^ ]* status=[^ ]* dur=[^ ]* err=[^ ]*' "$WORK/k6.log" | while read -r line; do
    ts="$(printf '%s\n' "$line" | sed -n 's/.*ts=\([^ ]*\).*/\1/p')"
    d=$(( $(date -u -d "$ts" +%s) - T0S ))
    rest="$(printf '%s\n' "$line" | sed 's/^NON2XX ts=[^ ]* //')"
    if [ "$d" -ge 0 ]; then echo "      T0+${d}s  $rest"; else echo "      T0${d}s  $rest"; fi
  done
  echo
  echo "결과: 무중단 아님 — $TOTAL건 중 $BAD건 실패. 상세: $OUT/" >&2
  exit 1
fi

echo "결과: 무중단 — $TOTAL건 전량 2xx."
# IMP-015 §8의 판정 기준을 여기서도 되풀이한다. 결과를 부풀리는 것은 보통
# 문서가 아니라 "성공했다"는 한 줄에서 시작한다.
echo "  ⚠️ 이것은 이 조건(scope=$SCOPE, $RATE rps, →$TAG, $DIGEST_NOTE) 한 번의 결과다."
echo "     IMP-015 §8의 판정 기준대로, 0건이 나왔다고 502의 원인이 규명된 것은 아니다."
