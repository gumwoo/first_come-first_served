#!/usr/bin/env bash
# ElastiCache 페일오버 중 **앱의 거동과 유실 범위**를 측정한다.
#
# 왜 필요한가: ADR-012 §10이 Redis Multi-AZ를 **A(실증) 범주**에 넣었고
# terraform-design이 관찰 항목까지 지정했다 — "대기열 순번 보존 여부, SSE 재연결, 유실 범위".
# 2026-08-26 확인 결과 **설정은 켜져 있고(AutomaticFailover=enabled) 실증 문서가 없었다.**
#
# ⚠️ 좌석 조회(/events/{id}/seats)로 하면 의미가 없다. 그 경로는 DB·캐시를 타지
# **대기열 Redis 자료구조를 타지 않는다.** Redis가 진실원인 경로를 때려야 유실이 보인다.
#   POST /events/{id}/queue/token  → 대기열 진입(Redis ZSet 쓰기)
#   GET  /queue/status?token=...   → 순번 조회(Redis ZSet 읽기)
#
# ⚠️ RDS 페일오버와 **같이 하지 않는다.** 원인이 섞인다.
#
# ⚠️ ADR-012가 미리 적어 둔 대로 **Redis 복제는 비동기다.** 최근 쓰기가 유실될 수 있고,
# 그때 "요청은 200인데 대기열에서 사라진" 상태가 된다. 그래서 5xx만 세지 않고
# **status가 유지되는지**를 따로 센다(k6의 position_missing_total).
#
# 사용:
#   bash scripts/redis-failover-test.sh                  # 25 rps × 4분
#   bash scripts/redis-failover-test.sh --rate 25 --duration 6m
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
NS=flowticket
JOB=k6-redisfail
CM=k6-redisfail
REGION="${AWS_REGION:-ap-northeast-2}"
RG=flowticket-redis

RATE=25
# ⚠️ 대기열 admit-ttl(300초)보다 짧게 잡는다. 길면 토큰이 자연 만료돼 페일오버와 무관한
# "상태 유실"이 잡히고, 그걸 유실로 오독하게 된다.
DURATION=4m
WARMUP=45

while [ $# -gt 0 ]; do
  case "$1" in
    --rate)     RATE="$2"; shift 2;;
    --duration) DURATION="$2"; shift 2;;
    --warmup)   WARMUP="$2"; shift 2;;
    -h|--help)  sed -n '2,24p' "$0"; exit 0;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2;;
  esac
done

WORK="$(mktemp -d)"
cleanup() {
  rc=$?
  [ -n "${WATCH_PID:-}" ] && kill "$WATCH_PID" 2>/dev/null || true
  # ⚠️ Job을 지우기 **전에** 로그를 회수한다. 2026-08-26 첫 실행에서 사후 조회 단계의
  # jq 오류로 스크립트가 죽었고, trap이 Job을 지워 **4분짜리 측정 로그가 통째로 사라졌다.**
  # 부수적인 단계가 주 측정을 파괴하면 안 된다.
  if [ -n "${K6POD:-}" ] && [ ! -s "${WORK:-/nonexistent}/k6.log" ]; then
    kubectl -n "$NS" logs "$K6POD" > "$WORK/k6.log" 2>/dev/null || true
  fi
  if [ "$rc" -ne 0 ] && [ -s "${WORK:-/nonexistent}/k6.log" ]; then
    SALVAGE="$ROOT/redis-failover-salvage-$(date -u +%Y%m%dT%H%M%SZ)"
    mkdir -p "$SALVAGE" && cp "$WORK"/*.log "$SALVAGE/" 2>/dev/null || true
    echo "!!! 비정상 종료 — 수집된 로그를 보존했다: $SALVAGE" >&2
  fi
  kubectl -n "$NS" delete job "$JOB" --ignore-not-found >/dev/null 2>&1 || true
  rm -rf "$WORK"
  exit "$rc"
}
trap cleanup EXIT
say() { echo "==> $*"; }
. "$ROOT/scripts/lib/cluster-http.sh"
# ⚠️ MSYS_NO_PATHCONV=1이 없으면 Git Bash가 /flowticket/... 을 Windows 경로로 바꿔
# ParameterNotFound가 난다(seed-demo-data.sh·bootstrap.sh와 같은 함정). 실제로 걸렸다.
ssm() { MSYS_NO_PATHCONV=1 aws ssm get-parameter --name "$1" --with-decryption --region "$REGION" \
  --query 'Parameter.Value' --output text 2>/dev/null | tr -d '\r'; }

# ── 0. 전제 ──────────────────────────────────────────────────────────
say "0/7 전제 확인"
for c in kubectl aws jq; do command -v "$c" >/dev/null || { echo "$c 가 없다" >&2; exit 1; }; done
read -r AF MAZ ST NODES <<<"$(aws elasticache describe-replication-groups --region "$REGION" \
  --replication-group-id "$RG" \
  --query 'ReplicationGroups[0].[AutomaticFailover,MultiAZ,Status,length(NodeGroups[0].NodeGroupMembers)]' \
  --output text 2>/dev/null || echo "? ? ? 0")"
echo "    Redis=$RG AutomaticFailover=$AF MultiAZ=$MAZ status=$ST 노드=$NODES"
# ⚠️ 자동 페일오버가 꺼져 있으면 test-failover가 거부되거나 다른 것을 재게 된다.
[ "$AF" = "enabled" ] || { echo "AutomaticFailover가 enabled가 아니다 — 중단한다" >&2; exit 1; }
[ "${NODES:-0}" -ge 2 ] || { echo "노드가 ${NODES}개다. 페일오버는 2개 이상에서만 의미가 있다" >&2; exit 1; }
NG="$(aws elasticache describe-replication-groups --region "$REGION" --replication-group-id "$RG" \
  --query 'ReplicationGroups[0].NodeGroups[0].NodeGroupId' --output text)"
PRIMARY0="$(aws elasticache describe-replication-groups --region "$REGION" --replication-group-id "$RG" \
  --query "ReplicationGroups[0].NodeGroups[0].NodeGroupMembers[?CurrentRole=='primary'].CacheClusterId" \
  --output text 2>/dev/null || true)"
echo "    node-group=$NG 현재 primary=${PRIMARY0:-?}"
# ⚠️ 상태가 모두 available이어도 test-failover가 거부될 수 있다. 이전 페일오버 뒤
# 옛 primary가 재동기화("Recovering cache nodes") 중이면 AWS가
# TestFailoverNotAvailableFault를 낸다. 2026-08-26에 워밍업 45초를 태우고 나서
# 두 번 막혔다 — 부하를 걸기 **전에** 확인한다.
for i in $(seq 1 30); do
  # ⚠️ `2>/dev/null || true`로 감싸면 CLI 실패가 REC=""이 되어 "재동기화 없음"으로 오판한다.
  # 초판이 그랬고, 게다가 줄바꿈이 리터럴 `\n`으로 들어가 `n`이 잘못된 위치 인자로 전달돼
  # AWS CLI가 항상 exit 252로 죽었다 — 이 체크는 한 번도 동작한 적이 없다(실측 확인).
  # 그래서 종료코드를 값과 분리해서 본다.
  if REC="$(aws elasticache describe-events \
    --region "$REGION" \
    --duration 5 \
    --query "Events[?contains(Message,'Recovering cache nodes')].Date" \
    --output text 2>&1)"; then
    if [ -z "$REC" ] || [ "$REC" = "None" ]; then REC=""; break; fi
  else
    echo "    ⚠️ describe-events 실패 — 재동기화 여부를 확인하지 못했다:"
    printf '      %s\n' "$(printf '%s' "$REC" | head -c 160)"
    REC=""; break
  fi
  echo "    최근 5분 내 재동기화 이벤트가 있다 — 대기($((i*20))s)"
  sleep 20
done
[ -z "${REC:-}" ] || echo "    ⚠️ 재동기화가 계속된다 — test-failover가 거부될 수 있다"

DOMAIN="$(kubectl -n "$NS" get ingress flowticket -o jsonpath='{.spec.rules[0].host}' 2>/dev/null || true)"
[ -n "$DOMAIN" ] || { echo "Ingress에서 도메인을 읽지 못했다" >&2; exit 1; }
API="https://$DOMAIN/api"
CODE="$(http_code "https://$DOMAIN")"
[ "$CODE" = "200" ] || { echo "측정 전 상태가 정상이 아니다: $CODE" >&2; exit 1; }

# ── 1. 대기열 상태 만들기 ───────────────────────────────────────────
say "1/7 대기열 토큰 발급 (Redis에 상태를 만든다)"
ADMIN_EMAIL="$(ssm /flowticket/ADMIN_EMAIL)"; ADMIN_PASSWORD="$(ssm /flowticket/ADMIN_PASSWORD)"
[ -n "$ADMIN_EMAIL" ] && [ -n "$ADMIN_PASSWORD" ] || { echo "관리자 자격증명이 비어 있다" >&2; exit 1; }
LOGIN_BODY="$(jq -cn --arg e "$ADMIN_EMAIL" --arg p "$ADMIN_PASSWORD" '{email:$e,password:$p,remember:true}')"
JWT="$(curl -sS -X POST "$API/auth/login" -H 'Content-Type: application/json' -d "$LOGIN_BODY" 2>/dev/null \
  | jq -r '.data.accessToken // empty' | tr -d '\r')"
unset ADMIN_PASSWORD LOGIN_BODY
[ -n "$JWT" ] || { echo "로그인 실패" >&2; exit 1; }
EVENT_ID="$(http_body "$API/events?status=ON_SALE&size=20" | jq -r '.data.items[0].id // empty' | tr -d '\r')"
[ -n "$EVENT_ID" ] || { echo "ON_SALE 이벤트가 없다" >&2; exit 1; }
QT="$(curl -sS -X POST "$API/events/$EVENT_ID/queue/token" -H "Authorization: Bearer $JWT" 2>/dev/null \
  | jq -r '.data.token // empty' | tr -d '\r')"
[ -n "$QT" ] || { echo "대기열 토큰 발급 실패 — 응답 형식을 확인하라" >&2; exit 1; }
# ⚠️ 응답 형식을 측정 전에 확인했다(2026-08-26):
#   {"rank":0,"total":0,"etaSeconds":0,"status":"ADMITTED"}
# 필드는 position이 아니라 **rank**이고, 입장 완료면 rank가 0이 된다. 그래서 rank로는
# 유실을 판정할 수 없다 — **status**가 신호다. 초판이 position을 봐서 전부 유실로 셀 뻔했다.
ST0_JSON="$(curl -sS "$API/queue/status?token=$QT" 2>/dev/null || true)"
POS0="$(printf '%s' "$ST0_JSON" | jq -r '.data.rank // empty' | tr -d '\r')"
STATUS0="$(printf '%s' "$ST0_JSON" | jq -r '.data.status // empty' | tr -d '\r')"
[ -n "$STATUS0" ] || { echo "대기열 상태를 읽지 못했다 — 유실 판정 기준이 없으므로 중단한다" >&2; exit 1; }

# ⚠️ ADMITTED가 될 때까지 기다린다. WAITING 상태로 측정을 시작하면 승격(1.5초 주기)이
# 측정 중에 일어나 상태가 바뀌고, 그것을 유실과 구분할 수 없다.
# 초판이 WAITING으로 시작해 워밍업 45초에 1,188건이 "유실"로 찍혔다(2026-08-26).
if [ "$STATUS0" != "ADMITTED" ]; then
  echo "    status=$STATUS0 — ADMITTED 승격을 기다린다(최대 60초)"
  for i in $(seq 1 30); do
    sleep 2
    ST0_JSON="$(http_body "$API/queue/status?token=$QT" || true)"
    STATUS0="$(printf '%s' "$ST0_JSON" | jq -r '.data.status // empty' 2>/dev/null | tr -d '\r')"
    POS0="$(printf '%s' "$ST0_JSON" | jq -r '.data.rank // empty' 2>/dev/null | tr -d '\r')"
    [ "$STATUS0" = "ADMITTED" ] && break
  done
fi
[ "$STATUS0" = "ADMITTED" ] || {
  echo "토큰이 ADMITTED가 되지 않았다(status=$STATUS0) — 유실 판정이 승격과 섞이므로 중단한다" >&2
  exit 1; }
echo "    event=$EVENT_ID token=${QT:0:12}… 장애 전 status=$STATUS0 rank=${POS0:-0}"
# ⚠️ 이 status가 페일오버 후에도 유지되는지가 이 실험의 핵심 관찰 항목이다.

# ── 2. 부하 ─────────────────────────────────────────────────────────
say "2/7 부하 기동 (${RATE} rps, Redis 경로)"
kubectl -n "$NS" delete job "$JOB" --ignore-not-found >/dev/null
kubectl -n "$NS" create configmap "$CM" \
  --from-file=queue-status.js="$ROOT/infra/k6/queue-status.js" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
cat > "$WORK/job.yaml" <<YAML
apiVersion: batch/v1
kind: Job
metadata: { name: $JOB, namespace: $NS }
spec:
  backoffLimit: 0
  ttlSecondsAfterFinished: 3600
  template:
    metadata: { labels: { app: $JOB } }
    spec:
      restartPolicy: Never
      containers:
        - name: k6
          image: grafana/k6:0.54.0
          args: ["run", "/scripts/queue-status.js"]
          env:
            - { name: BASE_URL, value: "$API" }
            - { name: RATE, value: "$RATE" }
            - { name: RUN_FOR, value: "$DURATION" }
            - { name: QUEUE_TOKEN, value: "$QT" }
            - { name: EXPECT_STATUS, value: "$STATUS0" }
          resources:
            requests: { cpu: "200m", memory: "256Mi" }
            limits: { memory: "512Mi" }
          volumeMounts: [{ name: scripts, mountPath: /scripts }]
      volumes:
        - name: scripts
          configMap: { name: $CM }
YAML
kubectl apply -f "$WORK/job.yaml" >/dev/null
kubectl -n "$NS" wait --for=condition=Ready pod -l app="$JOB" --timeout=120s >/dev/null
K6POD="$(kubectl -n "$NS" get pod -l app="$JOB" -o jsonpath='{.items[0].metadata.name}')"
echo "    pod=$K6POD"

# ── 3. Lettuce 연결 감시 ────────────────────────────────────────────
# 재연결에 걸리는 시간이 문서가 지정한 관찰 항목이다. Redis 커맨드 지표로 간접 관찰한다.
say "3/7 Redis 클라이언트 지표 감시 시작"
(
  prev=""
  while :; do
    v="$(kubectl -n "$NS" exec deploy/flowticket-api -c api -- \
      sh -c 'wget -qO- localhost:8080/actuator/prometheus 2>/dev/null \
             | awk "/^lettuce_command_(completion|firstresponse)_seconds_count|^redis/ {print \$1\"=\"\$2}" \
             | sed "s/{[^}]*}//" | sort -u | tr "\n" " "' 2>/dev/null || true)"
    [ -n "$v" ] && [ "$v" != "$prev" ] && { printf '%s  %s\n' "$(date -u +%H:%M:%SZ)" "$v"; prev="$v"; }
    sleep 1
  done
) > "$WORK/redis-metrics.log" 2>&1 &
WATCH_PID=$!

say "4/7 워밍업 ${WARMUP}s (장애 전 기준선)"
sleep "$WARMUP"
BASE_BAD="$(kubectl -n "$NS" logs "$K6POD" 2>/dev/null | grep -cE 'NON2XX|NOSTATE|STATECHANGE' || true)"
echo "    장애 전 실패+상태유실 = $BASE_BAD"
# ⚠️ 기준선이 0이 아니면 장애를 탓할 수 없다. 그대로 진행하면 페일오버와 무관한 신호가
# 결과에 섞인다 — 초판이 1,188건을 안고 진행했다(2026-08-26). 여기서 멈춘다.
[ "${BASE_BAD:-0}" -eq 0 ] || {
  echo "장애 전부터 신호가 ${BASE_BAD}건 있다 — 이 측정으로는 페일오버를 탓할 수 없다. 중단한다." >&2
  echo "  확인: kubectl -n $NS logs $K6POD | grep -E 'NON2XX|NOSTATE|STATECHANGE' | head" >&2
  exit 1; }

# ── 5. 장애 주입 ────────────────────────────────────────────────────
say "5/7 ElastiCache test-failover"
T0="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
aws elasticache test-failover --region "$REGION" \
  --replication-group-id "$RG" --node-group-id "$NG" >/dev/null
echo "    T0=$T0  node-group=$NG (이전 primary=${PRIMARY0:-?})"

# ── 6. 회복 관찰 ────────────────────────────────────────────────────
say "6/7 회복 관찰 (primary 전환 확인)"
NEWPRIMARY=""
for i in $(seq 1 40); do
  CUR="$(aws elasticache describe-replication-groups --region "$REGION" --replication-group-id "$RG" \
    --query "ReplicationGroups[0].NodeGroups[0].NodeGroupMembers[?CurrentRole=='primary'].CacheClusterId" \
    --output text 2>/dev/null || true)"
  RGST="$(aws elasticache describe-replication-groups --region "$REGION" --replication-group-id "$RG" \
    --query 'ReplicationGroups[0].Status' --output text 2>/dev/null || echo "?")"
  printf "    +%-4s status=%-12s primary=%s\n" "$((i*10))s" "$RGST" "${CUR:-?}"
  [ -n "$CUR" ] && [ "$CUR" != "$PRIMARY0" ] && { NEWPRIMARY="$CUR"; break; }
  sleep 10
done
T1="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "    primary 전환: ${PRIMARY0:-?} → ${NEWPRIMARY:-확인못함}"
[ -n "$NEWPRIMARY" ] || echo "    ⚠️ primary 전환을 확인하지 못했다 — 해석 시 주의"

# 페일오버 후 순번이 남아 있는가 — 이 실험의 핵심
# ⚠️ 여기서 죽으면 안 된다. 페일오버 직후라 응답이 JSON이 아닐 수 있고(그 자체가 관찰
# 대상이다), 초판은 여기서 jq parse error로 종료해 **주 측정 로그를 통째로 잃었다.**
# 파싱 실패를 예외가 아니라 값으로 다룬다. 필드도 position이 아니라 status·rank다.
ST1_JSON="$(http_body "$API/queue/status?token=$QT" || true)"
if printf '%s' "$ST1_JSON" | jq -e . >/dev/null 2>&1; then
  POS1="$(printf '%s' "$ST1_JSON" | jq -r '.data.rank // empty' | tr -d '\r')"
  STATUS1="$(printf '%s' "$ST1_JSON" | jq -r '.data.status // empty' | tr -d '\r')"
else
  POS1=""; STATUS1="파싱실패"
  echo "    ⚠️ 사후 상태 응답이 JSON이 아니다: $(printf '%s' "$ST1_JSON" | head -c 120)"
fi
echo "    장애 후 status=${STATUS1:-없음} rank=${POS1:-없음} (장애 전 status=$STATUS0 rank=${POS0:-0})"

say "7/7 부하 종료 대기"
kubectl -n "$NS" wait --for=condition=complete "job/$JOB" --timeout=15m >/dev/null 2>&1 || true
kubectl -n "$NS" logs "$K6POD" > "$WORK/k6.log" 2>&1 || true
kill "$WATCH_PID" 2>/dev/null || true; WATCH_PID=""

# ── 결과 ────────────────────────────────────────────────────────────
OUT="$ROOT/redis-failover-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$OUT"; cp "$WORK/k6.log" "$WORK/redis-metrics.log" "$OUT/" 2>/dev/null || true
{
  echo "rg=$RG node_group=$NG primary: ${PRIMARY0:-?} -> ${NEWPRIMARY:-?}"
  echo "queue status: before=$STATUS0/rank=${POS0:-0} after=${STATUS1:-없음}/rank=${POS1:-없음}"
  echo "rate=$RATE duration=$DURATION baseline=$BASE_BAD"
  echo "failover: $T0 -> $T1"
} > "$OUT/conditions.txt"
kubectl -n "$NS" get pods -l app=flowticket-api \
  -o custom-columns=NAME:.metadata.name,RESTARTS:.status.containerStatuses[0].restartCount \
  --no-headers > "$OUT/api-pods.txt" 2>/dev/null || true

SUMMARY="$(grep -o 'SUMMARY_JSON .*' "$WORK/k6.log" | tail -1 | sed 's/^SUMMARY_JSON //' || true)"
[ -n "$SUMMARY" ] || { echo "k6 요약을 얻지 못했다 — $OUT/k6.log" >&2; exit 1; }
TOTAL="$(echo "$SUMMARY" | jq -r .total)"
BAD="$(echo "$SUMMARY" | jq -r .non2xx)"
NOPOS="$(echo "$SUMMARY" | jq -r .posMissing)"
echo
echo "    총 요청       $TOTAL"
echo "    non-2xx       $BAD"
echo "    상태 유실     $NOPOS   (200인데 status가 없거나 바뀐 응답)"
echo "    p95/p99       $(echo "$SUMMARY" | jq -r .p95)ms / $(echo "$SUMMARY" | jq -r .p99)ms"
echo "    대기열 상태   $STATUS0(rank ${POS0:-0}) → ${STATUS1:-없음}(rank ${POS1:-없음})"
echo "    파드 재시작:"; sed 's/^/      /' "$OUT/api-pods.txt" 2>/dev/null
echo "    상세          $OUT/"
echo
if [ "$BAD" -gt 0 ] || [ "$NOPOS" -gt 0 ]; then
  grep -oE '(NON2XX|NOPOS) ts=[^ ]*[^\n]*' "$WORK/k6.log" | tail -20 | sed 's/^/      /'
  echo
  echo "결과: 페일오버 중 손실 — 실패 $BAD건 / 상태 유실 $NOPOS건 (총 $TOTAL). 상세: $OUT/" >&2
  exit 1
fi
echo "결과: 페일오버 중 무손실 — $TOTAL건 전량 2xx, 상태 유실 0."
echo "  ⚠️ 조건: test-failover / $RATE rps / 1회. 토큰 1개로만 관찰했으므로"
echo "     '대기열 전체가 보존된다'는 뜻은 아니다."
