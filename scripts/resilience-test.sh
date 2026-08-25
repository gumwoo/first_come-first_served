#!/usr/bin/env bash
# 장애 주입 실증 — 브로커 강제 종료(IMP-016) / 노드 드레인(IMP-017)을 부하 중에 재현한다.
#
# 왜 다시 재는가: 두 측정 모두 **Cluster Autoscaler가 없는 상태**에서 잰 값이다.
# CA를 도입하면 scale-down이 상태 저장 워크로드를 옮길 수 있어(ADR-012 §한계,
# terraform-design "CA와 상태 저장 워크로드") 전제가 바뀐다. 그래서 CA 도입과
# 재측정은 한 묶음이다 — 도입만 하고 재지 않으면 기존 수치가 조용히 낡는다.
#
# 왜 스크립트인가: IMP-016은 `kubectl delete pod ... --force`, IMP-017은
# `kubectl drain ...`을 손으로 돌렸다. IMP-015가 같은 이유로 재현 불가였고
# 2026-08-25에 그 대가를 치렀다(TS-035). 절차를 코드로 고정한다.
#
# 사용:
#   bash scripts/resilience-test.sh --scenario failover   # 브로커 1대 강제 종료
#   bash scripts/resilience-test.sh --scenario drain      # 노드 1대 드레인
#   bash scripts/resilience-test.sh --scenario drain --rate 25 --duration 4m
#
# 종료 코드: 부하 중 5xx가 1건이라도 나면 non-zero. "실험을 돌렸다"가 아니라
# "장애 중에도 요청이 떨어지지 않았다"가 이 스크립트의 주장이다.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
NS=flowticket
KNS=kafka
JOB=k6-resilience
CM=k6-resilience

SCENARIO=""
RATE=25
DURATION=4m
WARMUP=45

while [ $# -gt 0 ]; do
  case "$1" in
    --scenario) SCENARIO="$2"; shift 2;;
    --rate)     RATE="$2"; shift 2;;
    --duration) DURATION="$2"; shift 2;;
    --warmup)   WARMUP="$2"; shift 2;;
    -h|--help)  sed -n '2,20p' "$0"; exit 0;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2;;
  esac
done
case "$SCENARIO" in
  failover|drain) ;;
  *) echo "--scenario 는 failover | drain" >&2; exit 2;;
esac

WORK="$(mktemp -d)"
CORDONED=""

cleanup() {
  rc=$?
  # ⚠️ 드레인한 노드를 cordon 상태로 두면 다음 측정의 스케줄링 예산이 조용히 줄어든다.
  # 그 상태에서 "노드가 안 늘었다"를 관찰하면 CA 결함으로 오독하게 된다.
  if [ -n "$CORDONED" ]; then
    kubectl uncordon "$CORDONED" >/dev/null 2>&1 \
      && echo "==> uncordon: $CORDONED" \
      || { echo "!!! uncordon 실패 — kubectl uncordon $CORDONED" >&2; rc=1; }
  fi
  kubectl -n "$NS" delete job "$JOB" --ignore-not-found >/dev/null 2>&1 || true
  rm -rf "$WORK"
  exit "$rc"
}
trap cleanup EXIT

say() { echo "==> $*"; }

# ── 0. 전제 ──────────────────────────────────────────────────────────
say "0/6 전제 확인 (scenario=$SCENARIO)"
for c in kubectl jq; do command -v "$c" >/dev/null || { echo "$c 가 없다" >&2; exit 1; }; done
DOMAIN="$(kubectl -n "$NS" get ingress flowticket -o jsonpath='{.spec.rules[0].host}' 2>/dev/null || true)"
[ -n "$DOMAIN" ] || { echo "Ingress에서 도메인을 읽지 못했다" >&2; exit 1; }
CODE="$(curl -s -o /dev/null -w '%{http_code}' "https://$DOMAIN" --max-time 20 || true)"
[ "$CODE" = "200" ] || { echo "측정 전 상태가 정상이 아니다: https://$DOMAIN → $CODE" >&2; exit 1; }

CA_ON="없음"
kubectl -n kube-system get deploy -l app.kubernetes.io/name=aws-cluster-autoscaler \
  --no-headers 2>/dev/null | grep -q . && CA_ON="있음"
NODE_TYPE="$(kubectl get nodes -o jsonpath='{.items[0].metadata.labels.node\.kubernetes\.io/instance-type}' 2>/dev/null || echo "?")"
echo "    노드 $(kubectl get nodes --no-headers | wc -l | tr -d ' ')대 / $NODE_TYPE, Cluster Autoscaler $CA_ON"
# 이 한 줄이 이 측정의 조건을 규정한다 — IMP-016·017은 'CA 없음'에서 잰 값이다.

# 시나리오별 대상 확정
if [ "$SCENARIO" = "failover" ]; then
  # ⚠️ 라벨 셀렉터를 하나만 믿지 않는다. strimzi.io/broker-role은 Strimzi 버전에 따라
  # 없을 수 있고, 그러면 "브로커 0개"로 조용히 오판한다. pool 라벨로 폴백한다.
  # (이 클러스터는 controller/broker 겸용 pool "dual-role" 하나다 — k8s/kafka/kafka.yaml)
  BSEL="strimzi.io/broker-role=true"
  BROKERS="$(kubectl -n "$KNS" get pods -l "$BSEL" --no-headers 2>/dev/null | wc -l | tr -d ' ')"
  if [ "${BROKERS:-0}" -eq 0 ]; then
    BSEL="strimzi.io/cluster=flowticket,strimzi.io/pool-name=dual-role"
    BROKERS="$(kubectl -n "$KNS" get pods -l "$BSEL" --no-headers 2>/dev/null | wc -l | tr -d ' ')"
    [ "${BROKERS:-0}" -gt 0 ] && echo "    (broker-role 라벨이 없어 pool-name 셀렉터로 폴백했다)"
  fi
  if [ "${BROKERS:-0}" -eq 0 ]; then
    echo "브로커 파드를 라벨로 찾지 못했다. 실제 라벨을 확인하라:" >&2
    echo "  kubectl -n $KNS get pods --show-labels" >&2
    exit 1
  fi
  [ "$BROKERS" -ge 3 ] || {
    echo "브로커가 ${BROKERS}개다. min.insync=2 환경에서 1대를 죽이는 실험은 3대 이상에서만 의미가 있다" >&2
    exit 1; }
  TARGET="$(kubectl -n "$KNS" get pods -l "$BSEL" -o jsonpath='{.items[0].metadata.name}')"
  echo "    브로커 ${BROKERS}대 — 대상 $TARGET"
else
  # 파드가 가장 많은 노드를 고른다. 빈 노드를 드레인하면 아무것도 증명하지 못한다.
  TARGET="$(kubectl -n "$NS" get pods -o jsonpath='{range .items[*]}{.spec.nodeName}{"\n"}{end}' \
    | sort | uniq -c | sort -rn | head -1 | awk '{print $2}')"
  [ -n "$TARGET" ] || { echo "드레인할 노드를 찾지 못했다" >&2; exit 1; }
  echo "    대상 노드 $TARGET (해당 노드의 flowticket 파드 $(kubectl -n "$NS" get pods --field-selector spec.nodeName="$TARGET" --no-headers | wc -l | tr -d ' ')개)"
fi

# ── 1. 부하 기동 ────────────────────────────────────────────────────
say "1/6 부하 기동 (${RATE} rps)"
EVENT_ID="$(curl -s "https://$DOMAIN/api/events?status=ON_SALE&size=20" --max-time 20 \
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
      # ⚠️ 드레인 대상 노드에 생성기가 올라가면 측정기가 함께 축출된다.
      # 그러면 "장애 때문에 요청이 끊겼다"가 아니라 "측정기가 죽었다"를 보게 된다.
      affinity:
        nodeAffinity:
          requiredDuringSchedulingIgnoredDuringExecution:
            nodeSelectorTerms:
              - matchExpressions:
                  - key: kubernetes.io/hostname
                    operator: NotIn
                    values: ["${TARGET}"]
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
echo "    pod=$K6POD"

# ── 2. 워밍업 ───────────────────────────────────────────────────────
say "2/6 워밍업 ${WARMUP}s (장애 전 기준선)"
sleep "$WARMUP"
BASE_BAD="$(kubectl -n "$NS" logs "$K6POD" 2>/dev/null | grep -c 'NON2XX' || true)"
echo "    장애 전 non-2xx = $BASE_BAD"
[ "${BASE_BAD:-0}" -eq 0 ] || echo "    ⚠️ 장애 전부터 실패가 있다 — 이번 측정으로 장애를 탓할 수 없다"

# ── 3. 장애 주입 ────────────────────────────────────────────────────
say "3/6 장애 주입"
T0="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
if [ "$SCENARIO" = "failover" ]; then
  # 유예 없이 죽인다. graceful이면 브로커가 리더십을 미리 넘겨 장애가 아니게 된다(IMP-016 §3).
  kubectl -n "$KNS" delete pod "$TARGET" --grace-period=0 --force >/dev/null 2>&1
  echo "    T0=$T0  브로커 $TARGET 강제 종료(--grace-period=0 --force)"
else
  CORDONED="$TARGET"
  kubectl drain "$TARGET" --ignore-daemonsets --delete-emptydir-data --timeout=10m
  echo "    T0=$T0  노드 $TARGET 드레인 완료"
fi

# ── 4. 회복 관찰 ────────────────────────────────────────────────────
say "4/6 회복 관찰"
if [ "$SCENARIO" = "failover" ]; then
  # ISR이 줄었다가 돌아오는지가 핵심이다. 쓰기가 지속되는지는 5xx로 본다.
  for i in $(seq 1 30); do
    R="$(kubectl -n "$KNS" get pods -l "$BSEL" --no-headers 2>/dev/null | awk '$2=="1/1"' | wc -l | tr -d ' ')"
    printf "    +%-4s 준비된 브로커 %s\n" "$((i*10))s" "$R"
    [ "$R" -ge "$BROKERS" ] && { echo "    브로커 복귀 완료"; break; }
    sleep 10
  done
else
  for i in $(seq 1 30); do
    P="$(kubectl -n "$NS" get pods --field-selector=status.phase=Pending --no-headers 2>/dev/null | wc -l | tr -d ' ')"
    N="$(kubectl get nodes --no-headers | wc -l | tr -d ' ')"
    printf "    +%-4s 노드 %s  Pending %s\n" "$((i*10))s" "$N" "$P"
    [ "$P" -eq 0 ] && [ "$i" -ge 3 ] && break
    sleep 10
  done
fi
T1="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# ── 5. 종료 대기 ────────────────────────────────────────────────────
say "5/6 부하 종료 대기"
kubectl -n "$NS" wait --for=condition=complete "job/$JOB" --timeout=15m >/dev/null 2>&1 || true
kubectl -n "$NS" logs "$K6POD" > "$WORK/k6.log" 2>&1 || true

# ── 6. 판정 ─────────────────────────────────────────────────────────
say "6/6 결과"
OUT="$ROOT/resilience-$SCENARIO-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$OUT"; cp "$WORK/k6.log" "$OUT/" 2>/dev/null || true
{
  echo "scenario=$SCENARIO target=$TARGET rate=$RATE duration=$DURATION"
  echo "node_type=$NODE_TYPE cluster_autoscaler=$CA_ON"
  echo "injection: $T0 -> recovery observed by $T1"
  echo "baseline_non2xx: $BASE_BAD"
} > "$OUT/conditions.txt"
{ echo "# 사후 상태"; kubectl -n "$KNS" get pods --no-headers 2>/dev/null || true
  echo "--- nodes"; kubectl get nodes --no-headers 2>/dev/null || true; } > "$OUT/after.txt"

SUMMARY="$(grep -o 'SUMMARY_JSON .*' "$WORK/k6.log" | tail -1 | sed 's/^SUMMARY_JSON //' || true)"
[ -n "$SUMMARY" ] || { echo "k6 요약을 얻지 못했다 — $OUT/k6.log" >&2; exit 1; }
TOTAL="$(echo "$SUMMARY" | jq -r .total)"
BAD="$(echo "$SUMMARY" | jq -r .non2xx)"
echo
echo "    총 요청   $TOTAL"
echo "    non-2xx   $BAD"
echo "    p95/p99   $(echo "$SUMMARY" | jq -r .p95)ms / $(echo "$SUMMARY" | jq -r .p99)ms"
echo "    조건·로그 $OUT/"
echo

if [ "$BAD" -gt 0 ]; then
  echo "    실패한 요청:"
  grep -o 'NON2XX ts=[^ ]* status=[^ ]* dur=[^ ]* err=[^ ]*' "$WORK/k6.log" | tail -30 | sed 's/^/      /'
  echo
  echo "결과: 장애 중 요청 손실 — $TOTAL건 중 $BAD건 실패. 상세: $OUT/" >&2
  exit 1
fi
echo "결과: 장애 중 무손실 — $TOTAL건 전량 2xx."
echo "  ⚠️ 조건: $SCENARIO / $RATE rps / Cluster Autoscaler $CA_ON / 1회 측정."
echo "     CA 유무는 이 측정의 전제다 — 바뀌면 다시 재야 한다."
