#!/usr/bin/env bash
# Cluster Autoscaler 실증 — Pod가 못 들어갈 때 노드가 붙는가, 부하가 빠지면 줄어드는가.
#
# 왜 이 측정이 필요한가: 2026-08-25까지 이 프로젝트는 **고정 3노드 + 예산 안에 맞춘 HPA
# 상한**이었다(api 7 / web 4 = 4,950m / allocatable 5,790m). 그 구성에서는 HPA가 상한까지
# 늘려도 스케줄링이 성공하므로 **CA가 발동할 일이 없다.** CA를 넣었다고 말하려면
# Pending이 실제로 생기고, 노드가 붙어 해소되는 장면을 봐야 한다.
#
# ⚠️ 그래서 이 스크립트는 **일부러 예산을 넘긴다.** HPA 상한을 임시로 올려
# Pending을 만든 뒤, CA가 노드를 붙이는지 본다. 끝나면 원래 값으로 되돌린다.
#
# 측정하는 것 네 가지:
#   1) Pending이 실제로 발생하는가          (안 생기면 예산을 못 넘긴 것 — 조건 미달)
#   2) 노드가 늘어나는가, 몇 초 걸리는가     (EC2 부팅 + 클러스터 조인)
#   3) Pending이 해소되는가
#   4) 부하 제거 후 노드가 줄어드는가        (scale-down-unneeded-time 기본 10분)
#
# 사용:
#   bash scripts/node-autoscale-test.sh                    # 전체(축소까지, 약 25분)
#   bash scripts/node-autoscale-test.sh --skip-scale-down  # 확장만(약 10분)
#   bash scripts/node-autoscale-test.sh --api-max 12 --rate 400
#
# 전제: loadtest.tfvars로 apply한 클러스터(m6i.large, AZ당 max 3). 기본 프로파일
# (AZ당 max 2 = 6노드)에서도 돌지만 상한이 낮아 확장 폭을 보기 어렵다.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
NS=flowticket
ARGO_NS=argocd
APP=flowticket

API_MAX=12          # 임시 HPA 상한. 12×300m = 3,600m → 기존 예산을 확실히 넘긴다
RATE=400            # 부하(rps). HPA가 상한까지 올라가야 Pending이 생긴다
RUN_FOR=8m
SKIP_DOWN=0
DOWN_WAIT=900       # 축소 관찰 최대 대기(초). CA 기본 scale-down-unneeded-time이 10분이다

while [ $# -gt 0 ]; do
  case "$1" in
    --api-max)        API_MAX="$2"; shift 2;;
    --rate)           RATE="$2"; shift 2;;
    --duration)       RUN_FOR="$2"; shift 2;;
    --down-wait)      DOWN_WAIT="$2"; shift 2;;
    --skip-scale-down) SKIP_DOWN=1; shift;;
    -h|--help)        sed -n '2,26p' "$0"; exit 0;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2;;
  esac
done

WORK="$(mktemp -d)"
AUTOMATED=""
ORIG_API_MAX=""
RESTORE_NEEDED=0

cleanup() {
  rc=$?
  # ⚠️ 되돌리기는 무슨 일이 있어도 한다. HPA 상한을 올린 채 두면 다음 측정이 전혀 다른
  # 조건에서 돌고(예산 초과 상태), ArgoCD를 꺼둔 채 두면 클러스터가 GitOps 밖에 남는다.
  if [ -n "$ORIG_API_MAX" ]; then
    kubectl -n "$NS" patch hpa flowticket-api --type=merge \
      -p "{\"spec\":{\"maxReplicas\":$ORIG_API_MAX}}" >/dev/null 2>&1 \
      && echo "==> HPA 상한 복원: $ORIG_API_MAX" \
      || { echo "!!! HPA 상한 복원 실패 — 손으로 확인하라(kubectl -n $NS get hpa)" >&2; rc=1; }
  fi
  if [ "$RESTORE_NEEDED" -eq 1 ]; then
    kubectl -n "$ARGO_NS" patch application "$APP" --type=merge \
      -p "{\"spec\":{\"syncPolicy\":{\"automated\":$AUTOMATED}}}" >/dev/null 2>&1 \
      && echo "==> ArgoCD 자동 동기화 복원" \
      || { echo "!!! ArgoCD 복원 실패 — kubectl -n $ARGO_NS get application $APP -o jsonpath='{.spec.syncPolicy}'" >&2; rc=1; }
  fi
  kubectl -n "$NS" delete job k6-nodescale --ignore-not-found >/dev/null 2>&1 || true
  rm -rf "$WORK"
  exit "$rc"
}
trap cleanup EXIT

say() { echo "==> $*"; }
nodes()   { kubectl get nodes --no-headers 2>/dev/null | wc -l | tr -d ' '; }
ready()   { kubectl get nodes --no-headers 2>/dev/null | awk '$2=="Ready"' | wc -l | tr -d ' '; }
pending() { kubectl -n "$NS" get pods --field-selector=status.phase=Pending --no-headers 2>/dev/null | wc -l | tr -d ' '; }
apipods() { kubectl -n "$NS" get pods -l app=flowticket-api --no-headers 2>/dev/null | wc -l | tr -d ' '; }

# ── 0. 전제 ──────────────────────────────────────────────────────────
say "0/6 전제 확인"
for c in kubectl aws jq; do command -v "$c" >/dev/null || { echo "$c 가 없다" >&2; exit 1; }; done
kubectl get ns "$NS" >/dev/null 2>&1 || { echo "$NS 네임스페이스가 없다 — bring-up.sh 먼저" >&2; exit 1; }

CA_POD="$(kubectl -n kube-system get pod -l app.kubernetes.io/name=aws-cluster-autoscaler \
  -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"
if [ -z "$CA_POD" ]; then
  echo "Cluster Autoscaler 파드가 없다. 이 스크립트는 CA를 측정하는 것이므로 중단한다." >&2
  echo "  bring-up.sh 4단계가 설치한다." >&2
  exit 1
fi
# CA가 노드그룹을 인식하지 못하면 Pending이 나도 노드가 안 붙는다 — 결함이 아니라 설정 문제다.
#
# ⚠️ 로그 문자열로 판정하지 않는다. 초판이 "Registering Node Group"을 찾았는데 CA 1.35에는
# 그 문구가 없어 정상 CA를 실패로 판정했다(2026-08-26). CA가 스스로 발행하는 상태
# ConfigMap을 읽는다 — bring-up.sh와 같은 방식이어야 두 곳이 어긋나지 않는다.
CA_STATUS="$(kubectl -n kube-system get cm cluster-autoscaler-status \
  -o jsonpath='{.data.status}' 2>/dev/null || true)"
CA_RUNNING="$(printf '%s\n' "$CA_STATUS" | awk '/^autoscalerStatus:/{print $2; exit}')"
NG="$(printf '%s\n' "$CA_STATUS" | grep -cE '^  name: ' || true)"
[ "$CA_RUNNING" = "Running" ] || {
  echo "CA 상태가 Running이 아니다(=${CA_RUNNING:-읽지 못함}) — 중단한다." >&2
  echo "  확인: kubectl -n kube-system get cm cluster-autoscaler-status -o jsonpath='{.data.status}'" >&2
  exit 1; }
[ "${NG:-0}" -gt 0 ] || {
  echo "CA가 노드그룹을 하나도 인식하지 못했다 — ASG 태그(k8s.io/cluster-autoscaler/*)를 확인하라" >&2
  exit 1; }

NODE_TYPE="$(kubectl get nodes -o jsonpath='{.items[0].metadata.labels.node\.kubernetes\.io/instance-type}' 2>/dev/null || echo "?")"
N0="$(nodes)"
echo "    CA=$CA_POD ($CA_RUNNING, 노드그룹 ${NG}개)  노드 ${N0}대 / $NODE_TYPE"
# ⚠️ t3는 버스터블이라 지속 부하에서 CPU 크레딧이 개입한다(ADR-012 §5, TS-034).
case "$NODE_TYPE" in
  t3.*|t4g.*) echo "    ⚠️ 버스터블 인스턴스다 — 결과에 CPU 크레딧이 섞인다. loadtest.tfvars로 apply할 것";;
esac

DOMAIN="$(kubectl -n "$NS" get ingress flowticket -o jsonpath='{.spec.rules[0].host}' 2>/dev/null || true)"
[ -n "$DOMAIN" ] || { echo "Ingress에서 도메인을 읽지 못했다" >&2; exit 1; }
CODE="$(curl -s -o /dev/null -w '%{http_code}' "https://$DOMAIN" --max-time 20 || true)"
[ "$CODE" = "200" ] || { echo "측정 전 상태가 정상이 아니다: https://$DOMAIN → $CODE" >&2; exit 1; }

# ── 1. ArgoCD 자동 동기화 중지 ──────────────────────────────────────
say "1/6 ArgoCD 자동 동기화 일시 중지"
# HPA 상한을 patch로 올릴 것이므로, selfHeal이 켜져 있으면 즉시 Git 값으로 되돌린다.
AUTOMATED="$(kubectl -n "$ARGO_NS" get application "$APP" -o jsonpath='{.spec.syncPolicy.automated}' 2>/dev/null || true)"
if [ -n "$AUTOMATED" ] && [ "$AUTOMATED" != "null" ]; then
  kubectl -n "$ARGO_NS" patch application "$APP" --type=json \
    -p '[{"op":"remove","path":"/spec/syncPolicy/automated"}]' >/dev/null
  RESTORE_NEEDED=1
  echo "    중지됨(원본 $AUTOMATED)"
else
  echo "    이미 꺼져 있음"
fi

# ── 2. HPA 상한을 예산 밖으로 ───────────────────────────────────────
say "2/6 HPA 상한을 일시적으로 올린다 (Pending을 만들기 위해)"
ORIG_API_MAX="$(kubectl -n "$NS" get hpa flowticket-api -o jsonpath='{.spec.maxReplicas}')"
kubectl -n "$NS" patch hpa flowticket-api --type=merge \
  -p "{\"spec\":{\"maxReplicas\":$API_MAX}}" >/dev/null
echo "    api maxReplicas $ORIG_API_MAX → $API_MAX (×300m = $((API_MAX*300))m)"
echo "    ⚠️ 이 값은 노드 예산을 일부러 넘긴다. 종료 시 $ORIG_API_MAX 으로 되돌린다"

# ── 3. 부하 ─────────────────────────────────────────────────────────
say "3/6 부하 투입 (${RATE} rps, $RUN_FOR)"
EVENT_ID="$(curl -s "https://$DOMAIN/api/events?status=ON_SALE&size=20" --max-time 20 \
  | jq -r '.data.items[0].id // empty' | tr -d '\r' || true)"
kubectl -n "$NS" delete job k6-nodescale --ignore-not-found >/dev/null
kubectl -n "$NS" create configmap k6-nodescale \
  --from-file=lib.js="$ROOT/infra/k6/lib.js" \
  --from-file=read-load-rate.js="$ROOT/infra/k6/read-load-rate.js" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
cat > "$WORK/job.yaml" <<YAML
apiVersion: batch/v1
kind: Job
metadata: { name: k6-nodescale, namespace: $NS }
spec:
  backoffLimit: 0
  ttlSecondsAfterFinished: 3600
  template:
    metadata: { labels: { app: k6-nodescale } }
    spec:
      restartPolicy: Never
      containers:
        - name: k6
          image: grafana/k6:0.54.0
          args: ["run", "/scripts/read-load-rate.js"]
          env:
            - { name: K6_BASE_URL, value: "http://flowticket-api" }
            - { name: RATE, value: "$RATE" }
            - { name: RUN_FOR, value: "$RUN_FOR" }
          resources:
            # ⚠️ 생성기가 스스로 병목이 되면 도착률을 못 채워 Pending이 안 생긴다.
            # TS-034에서 k6가 1Gi로 OOMKilled 됐다 — 넉넉히 잡는다.
            requests: { cpu: "500m", memory: "1Gi" }
            limits: { memory: "3Gi" }
          volumeMounts: [{ name: scripts, mountPath: /scripts }]
      volumes:
        - name: scripts
          configMap: { name: k6-nodescale }
YAML
kubectl apply -f "$WORK/job.yaml" >/dev/null
T0="$(date -u +%s)"
echo "    T0=$(date -u -d "@$T0" +%H:%M:%SZ)  event=$EVENT_ID"

# ── 4. 확장 관찰 ────────────────────────────────────────────────────
say "4/6 확장 관찰 (Pending → 노드 증가 → 해소)"
printf "    %-8s %-6s %-7s %-8s %s\n" "경과" "노드" "Ready" "api파드" "Pending"
MAX_PENDING=0; NODE_UP_AT=""; PENDING_AT=""; RESOLVED_AT=""; NMAX="$N0"
for i in $(seq 1 60); do   # 최대 10분
  E=$(( $(date -u +%s) - T0 ))
  N=$(nodes); R=$(ready); P=$(pending); A=$(apipods)
  printf "    T0+%-5s %-6s %-7s %-8s %s\n" "${E}s" "$N" "$R" "$A" "$P"
  [ "$P" -gt "$MAX_PENDING" ] && MAX_PENDING="$P"
  [ -z "$PENDING_AT" ] && [ "$P" -gt 0 ] && PENDING_AT="$E"
  [ "$N" -gt "$NMAX" ] && { NMAX="$N"; [ -z "$NODE_UP_AT" ] && NODE_UP_AT="$E"; }
  [ -n "$PENDING_AT" ] && [ "$P" -eq 0 ] && [ -z "$RESOLVED_AT" ] && RESOLVED_AT="$E"
  [ -n "$RESOLVED_AT" ] && [ "$R" -gt "$N0" ] && break
  sleep 10
done
{
  echo "# CA 확장 관찰"
  echo "시작 노드      $N0"
  echo "최대 노드      $NMAX"
  echo "최대 Pending   $MAX_PENDING"
  echo "Pending 시작   ${PENDING_AT:-없음}"
  echo "노드 증가      ${NODE_UP_AT:-없음}"
  echo "Pending 해소   ${RESOLVED_AT:-없음}"
} | tee "$WORK/scale-out.txt"

# CA가 왜 그렇게 판단했는지는 로그가 유일한 근거다.
kubectl -n kube-system logs "$CA_POD" --tail=300 2>/dev/null \
  | grep -iE "scale.up|ScaleUp|Pending|node group|would fit" | tail -20 > "$WORK/ca-scaleup.log" || true

# ── 5. 축소 관찰 ────────────────────────────────────────────────────
if [ "$SKIP_DOWN" -eq 0 ]; then
  say "5/6 부하 종료 → 축소 관찰 (최대 $((DOWN_WAIT/60))분)"
  kubectl -n "$NS" delete job k6-nodescale --ignore-not-found >/dev/null
  # HPA도 먼저 되돌린다 — 상한이 높으면 파드가 안 줄어 노드도 안 준다.
  kubectl -n "$NS" patch hpa flowticket-api --type=merge \
    -p "{\"spec\":{\"maxReplicas\":$ORIG_API_MAX}}" >/dev/null
  echo "    api maxReplicas → $ORIG_API_MAX (원복). CA scale-down-unneeded-time 기본 10분"
  T1="$(date -u +%s)"
  DOWN_AT=""
  for i in $(seq 1 $((DOWN_WAIT/20))); do
    E=$(( $(date -u +%s) - T1 ))
    N=$(nodes)
    printf "    T1+%-5s 노드 %s\n" "${E}s" "$N"
    [ "$N" -lt "$NMAX" ] && { DOWN_AT="$E"; break; }
    sleep 20
  done
  echo "    축소 시작: ${DOWN_AT:-관찰 못 함(${DOWN_WAIT}s 내)}"
  # ⚠️ 축소가 상태 저장 워크로드를 건드렸는지 — IMP-016·017의 전제가 여기서 바뀐다.
  {
    echo "# 축소 후 Kafka / PDB 상태"
    kubectl -n kafka get pods --no-headers 2>/dev/null || true
    echo "--- PDB"
    kubectl get pdb -A --no-headers 2>/dev/null || true
  } | tee "$WORK/scale-down.txt"
else
  say "5/6 축소 관찰 생략(--skip-scale-down)"
fi

# ── 6. 판정 ─────────────────────────────────────────────────────────
say "6/6 결과"
OUT="$ROOT/nodescale-test-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$OUT"; cp "$WORK"/*.txt "$WORK"/*.log "$OUT/" 2>/dev/null || true
{
  echo "instance_type=$NODE_TYPE api_max_tmp=$API_MAX rate=$RATE duration=$RUN_FOR"
  echo "nodes: $N0 -> $NMAX"
} > "$OUT/conditions.txt"
echo "    상세: $OUT/"
echo

# 종료 코드는 "측정을 돌렸다"가 아니라 "CA가 동작했다"의 판정이어야 한다.
if [ "$MAX_PENDING" -eq 0 ]; then
  echo "판정: **조건 미달** — Pending이 한 번도 생기지 않았다." >&2
  echo "  예산을 넘기지 못한 것이다. --api-max 를 올리거나 --rate 를 올려 다시 하라." >&2
  echo "  (HPA가 상한까지 올라가지 못했을 수도 있다 — api파드 열을 확인하라)" >&2
  exit 1
fi
if [ "$NMAX" -le "$N0" ]; then
  echo "판정: **CA 미동작** — Pending이 최대 ${MAX_PENDING}개 생겼는데 노드가 늘지 않았다." >&2
  echo "  확인: 노드그룹 max_size, ASG 태그, CA 로그($OUT/ca-scaleup.log)" >&2
  exit 1
fi
echo "판정: CA 동작 확인 — Pending 최대 ${MAX_PENDING}개, 노드 $N0 → $NMAX"
[ -n "$NODE_UP_AT" ] && echo "  노드 증가까지 ${NODE_UP_AT}s"
[ -n "$RESOLVED_AT" ] && echo "  Pending 해소까지 ${RESOLVED_AT}s"
echo "  ⚠️ 1회 측정이다. 그리고 이것은 **HPA 상한을 일부러 올린 조건**이며,"
echo "     평시 구성(api $ORIG_API_MAX)에서는 예산 안에 들어가 CA가 발동하지 않는다."
