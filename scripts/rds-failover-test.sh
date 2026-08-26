#!/usr/bin/env bash
# RDS 강제 페일오버 중 **앱의 거동**을 측정한다.
#
# 왜 필요한가: ADR-012 §10이 Multi-AZ를 **A(실증) 범주**에 넣었다 — "켜 두기만 하는 것이
# 아니라 눌러 보고 앱의 거동을 측정한다". terraform-design도 관찰 항목을 표로 지정해 두었다.
# 그런데 2026-08-26 확인 결과 **설정은 켜져 있고(MultiAZ=True) 실증 문서가 없었다.**
# CA와 같은 패턴이다 — 설계는 있고 측정이 빠졌다.
#
# ⚠️ 측정 대상은 "AWS가 페일오버에 성공했는가"가 아니다. 그건 AWS가 보장한다.
# 재는 것은 **앱이 그 구간을 어떻게 통과하는가**다(terraform-design의 관찰 표).
#   커넥션 풀 — HikariCP가 끊긴 커넥션을 버리고 재연결하는가, 고갈되는가
#   오류 형태 — 5xx인가 타임아웃 누적인가
#   중단 시간 — 쓰기 불가 구간의 실제 길이
#   자동 복구 — 스스로 정상화되는가, Pod 재시작이 필요한가
#
# ⚠️ Redis 페일오버와 **같이 하지 않는다.** 원인이 섞인다. 각각 별도 실험이다.
#
# 사용:
#   bash scripts/rds-failover-test.sh                 # 25 rps × 6분
#   bash scripts/rds-failover-test.sh --rate 25 --duration 6m --warmup 45
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
NS=flowticket
JOB=k6-rdsfail
CM=k6-rdsfail
REGION="${AWS_REGION:-ap-northeast-2}"

RATE=25
DURATION=6m
WARMUP=45

while [ $# -gt 0 ]; do
  case "$1" in
    --rate)     RATE="$2"; shift 2;;
    --duration) DURATION="$2"; shift 2;;
    --warmup)   WARMUP="$2"; shift 2;;
    -h|--help)  sed -n '2,22p' "$0"; exit 0;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2;;
  esac
done

WORK="$(mktemp -d)"
cleanup() {
  rc=$?
  kubectl -n "$NS" delete job "$JOB" --ignore-not-found >/dev/null 2>&1 || true
  [ -n "${WATCH_PID:-}" ] && kill "$WATCH_PID" 2>/dev/null || true
  rm -rf "$WORK"
  exit "$rc"
}
trap cleanup EXIT
say() { echo "==> $*"; }

# 로컬 네트워크에 의존하지 않는 HTTP 헬퍼
. "$ROOT/scripts/lib/cluster-http.sh"

# ── 0. 전제 ──────────────────────────────────────────────────────────
say "0/6 전제 확인"
for c in kubectl aws jq; do command -v "$c" >/dev/null || { echo "$c 가 없다" >&2; exit 1; }; done
DB="$(aws rds describe-db-instances --region "$REGION" \
  --query 'DBInstances[0].DBInstanceIdentifier' --output text 2>/dev/null || true)"
[ -n "$DB" ] && [ "$DB" != "None" ] || { echo "RDS 인스턴스를 찾지 못했다" >&2; exit 1; }
read -r MULTIAZ STATUS AZ <<<"$(aws rds describe-db-instances --region "$REGION" \
  --db-instance-identifier "$DB" \
  --query 'DBInstances[0].[MultiAZ,DBInstanceStatus,AvailabilityZone]' --output text)"
echo "    RDS=$DB MultiAZ=$MULTIAZ status=$STATUS az=$AZ"
# ⚠️ Multi-AZ가 아니면 강제 페일오버는 그냥 재부팅이다 — 다른 것을 재게 된다.
[ "$MULTIAZ" = "True" ] || { echo "MultiAZ가 아니다 — 이 실험은 Multi-AZ 전환을 재는 것이므로 중단한다" >&2; exit 1; }
[ "$STATUS" = "available" ] || { echo "RDS 상태가 available이 아니다($STATUS)" >&2; exit 1; }

DOMAIN="$(kubectl -n "$NS" get ingress flowticket -o jsonpath='{.spec.rules[0].host}' 2>/dev/null || true)"
[ -n "$DOMAIN" ] || { echo "Ingress에서 도메인을 읽지 못했다" >&2; exit 1; }
CODE="$(http_code "https://$DOMAIN")"
[ "$CODE" = "200" ] || { echo "측정 전 상태가 정상이 아니다: $CODE" >&2; exit 1; }

# ── 1. 부하 (DB를 실제로 타는 경로) ─────────────────────────────────
say "1/6 부하 기동 (${RATE} rps)"
# ⚠️ 좌석 조회는 DB를 탄다(IMP-015 §3이 같은 이유로 이 엔드포인트를 골랐다).
# 캐시가 걸리는 구간이 있으므로 "DB 장애가 곧 5xx"는 아니다 — 그것 자체가 관찰 대상이다.
EVENT_ID="$(http_body "https://$DOMAIN/api/events?status=ON_SALE&size=20" \
  | jq -r '.data.items[0].id // empty' | tr -d '\r' || true)"
kubectl -n "$NS" delete job "$JOB" --ignore-not-found >/dev/null
kubectl -n "$NS" create configmap "$CM" \
  --from-file=rolling-availability.js="$ROOT/infra/k6/rolling-availability.js" \
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
          args: ["run", "/scripts/rolling-availability.js"]
          env:
            - { name: BASE_URL, value: "https://$DOMAIN/api" }
            - { name: RATE, value: "$RATE" }
            - { name: RUN_FOR, value: "$DURATION" }
            - { name: EVENT_ID, value: "$EVENT_ID" }
            - { name: MODEL, value: "open" }
            - { name: VUS, value: "6" }
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
echo "    pod=$K6POD event=$EVENT_ID"

# ── 2. Hikari 감시 시작 ─────────────────────────────────────────────
# 커넥션 풀이 고갈되는지가 문서가 지정한 첫 관찰 항목이다.
#
# ⚠️ /actuator/metrics/{name} 은 이 앱에서 빈 응답을 준다(2026-08-26 확인). 그대로 두면
# 로그가 텅 빈 채로 측정이 끝나고, 첫 관찰 항목을 잃는다 — 측정 전에 확인해서 잡았다.
# /actuator/prometheus 에는 hikaricp_* 지표가 나온다. 그쪽을 읽는다.
say "2/6 HikariCP 감시 시작 (1초 간격)"
HIKARI_OK="$(kubectl -n "$NS" exec deploy/flowticket-api -c api -- \
  sh -c 'wget -qO- localhost:8080/actuator/prometheus 2>/dev/null | grep -c "^hikaricp_connections_active"' 2>/dev/null || echo 0)"
[ "${HIKARI_OK:-0}" -gt 0 ] || {
  echo "HikariCP 지표를 읽지 못한다 — 커넥션 풀 관찰 없이는 이 실험의 절반이 빈다. 중단한다." >&2
  echo "  확인: kubectl -n $NS exec deploy/flowticket-api -c api -- wget -qO- localhost:8080/actuator/prometheus | grep hikaricp" >&2
  exit 1; }
(
  prev=""
  while :; do
    v="$(kubectl -n "$NS" exec deploy/flowticket-api -c api -- \
      sh -c 'wget -qO- localhost:8080/actuator/prometheus 2>/dev/null \
             | awk "/^hikaricp_connections_(active|idle|pending|timeout_total)/ {print \$1\"=\"\$2}" \
             | sed "s/{[^}]*}//" | tr "\n" " "' 2>/dev/null || true)"
    [ -n "$v" ] && [ "$v" != "$prev" ] && { printf '%s  %s\n' "$(date -u +%H:%M:%SZ)" "$v"; prev="$v"; }
    sleep 1
  done
) > "$WORK/hikari.log" 2>&1 &
WATCH_PID=$!
echo "    hikaricp 지표 확인됨(prometheus 엔드포인트)"

say "3/6 워밍업 ${WARMUP}s (장애 전 기준선)"
sleep "$WARMUP"
BASE_BAD="$(kubectl -n "$NS" logs "$K6POD" 2>/dev/null | grep -c 'NON2XX' || true)"
echo "    장애 전 non-2xx = $BASE_BAD"

# ── 4. 강제 페일오버 ────────────────────────────────────────────────
say "4/6 RDS 강제 페일오버"
T0="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
aws rds reboot-db-instance --region "$REGION" --db-instance-identifier "$DB" --force-failover >/dev/null
echo "    T0=$T0  reboot --force-failover 실행 (이전 AZ=$AZ)"

# ── 5. 회복 관찰 ────────────────────────────────────────────────────
say "5/6 회복 관찰"
NEWAZ=""; FAILOVER_EVT=""
for i in $(seq 1 60); do
  read -r ST CAZ <<<"$(aws rds describe-db-instances --region "$REGION" \
    --db-instance-identifier "$DB" --query 'DBInstances[0].[DBInstanceStatus,AvailabilityZone]' \
    --output text 2>/dev/null || echo "? ?")"
  # ⚠️ DBInstanceStatus=available 만 보고 끝내지 않는다. 2026-08-26 실행에서 실제로
  # 오판했다 — available이 된 시점(+70s)에는 AZ 필드가 아직 옛 값이었고 "전환 안 됨"으로
  # 기록됐다. 실제로는 12:06:13에 failover completed 이벤트가 났고 AZ는 2b → 2c였다.
  # 상태 하나만 보고 판정하면 정상 동작을 잘못 읽는다(TS-036과 같은 계열).
  EVT="$(aws rds describe-events --region "$REGION" --source-identifier "$DB" \
    --source-type db-instance --duration 30 \
    --query "Events[?contains(Message,'failover completed')].Date" --output text 2>/dev/null || true)"
  printf "    +%-4s status=%-12s az=%-18s failover_completed=%s\n" \
    "$((i*10))s" "$ST" "$CAZ" "${EVT:-아직}"
  if [ "$ST" = "available" ] && { [ "$CAZ" != "$AZ" ] || [ -n "$EVT" ]; }; then
    NEWAZ="$CAZ"; FAILOVER_EVT="$EVT"; break
  fi
  sleep 10
done
[ -n "${NEWAZ:-}${FAILOVER_EVT:-}" ] || echo "    ⚠️ AZ 전환도 failover completed 이벤트도 확인하지 못했다 — 해석 시 주의"
T1="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "    AZ 전환: $AZ → ${NEWAZ:-확인못함}  (failover completed: ${FAILOVER_EVT:-없음})"

say "6/6 부하 종료 대기"
kubectl -n "$NS" wait --for=condition=complete "job/$JOB" --timeout=15m >/dev/null 2>&1 || true
kubectl -n "$NS" logs "$K6POD" > "$WORK/k6.log" 2>&1 || true
kill "$WATCH_PID" 2>/dev/null || true; WATCH_PID=""

# ── 결과 ────────────────────────────────────────────────────────────
OUT="$ROOT/rds-failover-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$OUT"; cp "$WORK/k6.log" "$WORK/hikari.log" "$OUT/" 2>/dev/null || true
{
  echo "db=$DB multiaz=$MULTIAZ az: $AZ -> ${NEWAZ:-?}"
  echo "rate=$RATE duration=$DURATION baseline_non2xx=$BASE_BAD"
  echo "failover: $T0 -> available by $T1"
} > "$OUT/conditions.txt"
# 앱이 스스로 정상화됐는지 — Pod 재시작이 필요했다면 restartCount가 오른다.
kubectl -n "$NS" get pods -l app=flowticket-api \
  -o custom-columns=NAME:.metadata.name,RESTARTS:.status.containerStatuses[0].restartCount,READY:.status.containerStatuses[0].ready \
  --no-headers > "$OUT/api-pods.txt" 2>/dev/null || true

SUMMARY="$(grep -o 'SUMMARY_JSON .*' "$WORK/k6.log" | tail -1 | sed 's/^SUMMARY_JSON //' || true)"
[ -n "$SUMMARY" ] || { echo "k6 요약을 얻지 못했다 — $OUT/k6.log" >&2; exit 1; }
TOTAL="$(echo "$SUMMARY" | jq -r .total)"; BAD="$(echo "$SUMMARY" | jq -r .non2xx)"
echo
echo "    총 요청   $TOTAL"
echo "    non-2xx   $BAD"
echo "    p95/p99   $(echo "$SUMMARY" | jq -r .p95)ms / $(echo "$SUMMARY" | jq -r .p99)ms"
echo "    AZ        $AZ → ${NEWAZ:-?}"
echo "    파드 재시작:"; sed 's/^/      /' "$OUT/api-pods.txt" 2>/dev/null
echo "    상세      $OUT/"
echo
if [ "$BAD" -gt 0 ]; then
  echo "    실패 요청(최대 30건):"
  grep -o 'NON2XX ts=[^ ]* status=[^ ]* dur=[^ ]* err=[^ ]*' "$WORK/k6.log" | tail -30 | sed 's/^/      /'
  echo
  echo "결과: 페일오버 중 요청 손실 — $TOTAL건 중 $BAD건. 상세: $OUT/" >&2
  exit 1
fi
echo "결과: 페일오버 중 무손실 — $TOTAL건 전량 2xx."
echo "  ⚠️ 조건: RDS 강제 페일오버 / $RATE rps / 1회. 캐시가 걸린 구간이 있을 수 있으므로"
echo "     '무손실'이 곧 'DB 없이도 서비스된다'는 뜻은 아니다."
