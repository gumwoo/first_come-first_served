#!/usr/bin/env node
// run 구간의 Prometheus 데이터를 run 디렉터리에 남긴다(loadtest-100k-plan §7 "원시 데이터").
//
// Prometheus는 철거 때 함께 사라지고, 철거하지 않아도 보존 기간이 6시간이다. 그래서 run이 끝날 때마다 범위 질의
// 결과(query_range JSON)를 그대로 저장한다. 파일 하나가 질의 하나다(prom/<이름>.json).
//
//   kubectl -n monitoring port-forward svc/prometheus-operated 9090:9090
//   node scripts/loadtest/export-prom.mjs --out artifacts/loadtest/<session>/<run> \
//     --start 2026-10-02T05:00:00Z --end 2026-10-02T05:12:00Z [--step 10]
//
// --step 기본 10초는 수집 주기(15초, servicemonitor-api.yaml)보다 짧게 잡은 값이다. step이 수집 주기와 같으면 스크랩
// 시각이 조금만 흔들려도 한 샘플이 두 step 사이에 끼어 빠진다(G1이 실서버 백필로 재현: step 15는 놓치고 14는 잡았다).
// 사후 재확인(prom-recheck.mjs)은 step이 10초를 넘으면 판정 불가로 끝낸다.
//
// --start는 run 시작 이전, --end는 run 종료 + 45초 이후로 잡는다(사후 검사가 요구 — 파드 대조 여유 45초, 스크랩 두 주기 30초 포함). run 마지막 순간의 상태(특히 승격 처리
// 실패)는 종료 뒤 스크랩에야 Prometheus에 들어오므로, --end를 run 종료 시각에 맞추면 그 구간이 빠진다. 사후 검사
// (check-correctness.sh)는 이 파일들로 run 구간의 파드·재시작·승격 처리 실패를 확인하고, --start가 run 시작보다
// 늦으면 판정 불가로 끝낸다.
//
// 종료 코드: 0 모든 질의 저장, 1 질의 일부 실패, 2 인자 오류.
import { mkdirSync, rmSync, writeFileSync } from "node:fs";
import { parseArgs } from "node:util";

const USAGE = "사용: export-prom.mjs --out <run 디렉터리> --start <ISO> --end <ISO> [--step 초(>0)] [--query-timeout 초(>0)]";
const usage = (msg) => {
  console.error(`${msg ? msg + "\n" : ""}${USAGE}`);
  process.exit(2);
};
let a;
try {
  ({ values: a } = parseArgs({
    options: {
      prom: { type: "string", default: "http://localhost:9090" },
      out: { type: "string" },
      start: { type: "string" },
      end: { type: "string" },
      step: { type: "string", default: "10" },
      "query-timeout": { type: "string", default: "30" },
    },
  }));
} catch (e) {
  usage(e.message);
}
const positive = (v) => /^\d+(\.\d+)?$/.test(v ?? "") && Number(v) > 0;
if (!a.out || !a.start || !a.end || !positive(a.step) || !positive(a["query-timeout"])) usage();

// 계획서 §3(판정)·§5(계측)에서 쓰는 값들. 이름이 파일 이름이 된다.
export const QUERIES = {
  // 대기열(§5.1). 파드마다 같은 값이라 max로 묶는다(QueueMetrics).
  queue_waiting: "max by (event) (flowticket_queue_waiting)",
  queue_admitted: "max by (event) (flowticket_queue_admitted)",
  queue_admit_count: "max by (event) (flowticket_queue_admit_count)",
  queue_capacity: "max(flowticket_queue_capacity)",
  // 실효 입장 초과 판정(admission-overlap.mjs)의 정원. 파드마다 따로 내보내 파드 사이에 값이 다른지도 본다(max는 그 차이를 가린다).
  queue_capacity_by_pod: "max by (pod) (flowticket_queue_capacity)",
  queue_admit_drift: "max by (event) (abs(flowticket_queue_admit_drift))",
  queue_admit_tick_failures_rate: "sum(rate(flowticket_queue_admit_tick_failures_total[1m]))",
  queue_admit_tick_p95: "histogram_quantile(0.95, sum by (le) (rate(flowticket_queue_admit_tick_seconds_bucket[1m])))",
  queue_gate_fallback_rate: "sum(rate(flowticket_queue_gate_fallback_total[1m]))",
  // 정합성(§3.3)
  seat_oversold: "max(flowticket_seat_oversold)",
  // 관측 신선도(prom-recheck.mjs). 항상 있어야 할 두 지표의 가장 오래된 샘플 나이 — 감시기의 신선도 질의와 같은 식.
  // timestamp()는 지표 이름을 떼므로 지표마다 따로 걸고 구분 레이블을 붙여 or로 합친다(같은 레이블 묶음 오류 방지).
  sample_age_max:
    'max(label_replace(time() - timestamp(flowticket_seat_oversold), "m", "oversold", "", "")' +
    ' or label_replace(time() - timestamp(flowticket_queue_admit_tick_failures_total), "m", "tickfail", "", ""))',
  // API 지연·처리량(§3.2 판정, §3.4 SLO)
  http_p95_by_uri: "histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket[1m])))",
  http_rps_by_uri: "sum by (uri) (rate(http_server_requests_seconds_count[1m]))",
  http_5xx_rate: "sum(rate(http_server_requests_seconds_count{status=~\"5..\"}[1m]))",
  // 파드별 요청 분배 — 예매 경로는 web(Next.js rewrite) → api Service로 가서, 연결을 재사용하면 kube-proxy가 연결 단위로
  // 파드를 고르므로 요청이 일부 파드에 쏠릴 수 있다(추론, 미측정). 대기열 두 경로는 ALB가 파드 IP로 직접 보낸다(target-type ip).
  // app_pod_nodes(파드 → 노드 → AZ)·booking_db_*_by_pod와 함께 보면 "쏠림"과 "RDS 주 AZ와의 거리"를 가를 수 있다.
  http_rps_by_pod_uri: "sum by (pod, uri) (rate(http_server_requests_seconds_count[1m]))",
  booking_http_p95_by_pod:
    "histogram_quantile(0.95, sum by (le, pod, uri) (rate(http_server_requests_seconds_bucket" +
    "{uri=~\"/events/\\\\{id\\\\}/seats/hold|/seats/hold/\\\\{holdId\\\\}|/orders|/orders/\\\\{id\\\\}/payments(/confirm)?\"}[1m])))",
  // 진단 신호(§3.2)
  hikari_pending: "max by (pod) (hikaricp_connections_pending)",
  // 풀 크기 재산정 근거(운영 요구사항 §4 — api·대기열의 active·idle·total, 자원 산정 계획 §4). pending만으로는 "얼마나 남았나"를 모른다.
  hikari_active: "max by (pod) (hikaricp_connections_active)",
  hikari_idle: "max by (pod) (hikaricp_connections_idle)",
  hikari_total: "max by (pod) (hikaricp_connections)",
  // 예매 세션 DB 계측(BookingDbSessionListener — IMP-034 §6의 AZ 배치 추론 검증). op = hold·release·order·pay·confirm.
  // 고정 버킷(SLO)이라 백분위는 버킷 경계 해상도까지만 정확하다(application.yml slo).
  booking_db_held_p95_by_op: "histogram_quantile(0.95, sum by (le, op) (rate(flowticket_booking_db_held_seconds_bucket[1m])))",
  booking_db_acquire_p95_by_op: "histogram_quantile(0.95, sum by (le, op) (rate(flowticket_booking_db_acquire_seconds_bucket[1m])))",
  booking_db_execute_p95_by_op: "histogram_quantile(0.95, sum by (le, op) (rate(flowticket_booking_db_execute_seconds_bucket[1m])))",
  booking_db_statements_mean_by_op:
    "sum by (op) (rate(flowticket_booking_db_statements_sum[1m])) / sum by (op) (rate(flowticket_booking_db_statements_count[1m]))",
  // 파드별 평균 — app_pod_nodes(파드 → 노드)로 노드를 찾고, 노드 이름의 IP가 속한 앱 서브넷(10.0.32/64/96.0/19 = 2a/2b/2c)으로
  // AZ를 정해 "RDS 주 AZ의 파드가 빠른가"를 가른다.
  booking_db_held_mean_by_pod:
    "sum by (pod, op) (rate(flowticket_booking_db_held_seconds_sum[1m])) / sum by (pod, op) (rate(flowticket_booking_db_held_seconds_count[1m]))",
  booking_db_execute_mean_by_pod:
    "sum by (pod, op) (rate(flowticket_booking_db_execute_seconds_sum[1m])) / sum by (pod, op) (rate(flowticket_booking_db_execute_seconds_count[1m]))",
  tomcat_connections: "sum by (pod) (tomcat_connections_current_connections)",
  tomcat_threads_busy: "sum by (pod) (tomcat_threads_busy_threads)",
  jvm_heap_used: "sum by (pod) (jvm_memory_used_bytes{area=\"heap\"})",
  // 새 파드 워밍업 확인(IMP-033 v2 사전 등록이 보려다 못 본 값 — 지표 이름은 Micrometer JvmCompilationMetrics 기준, 세션에서 존재 확인)
  jvm_compilation_ms: "max by (pod) (jvm_compilation_time_ms_total)",
  // 메모리 requests 재산정 근거 — 힙만이 아니라 컨테이너 전체(OOM 판단 기준과 같은 값)
  app_memory_working_set_by_pod: "max by (pod) (container_memory_working_set_bytes{namespace=\"flowticket\",container!=\"\"})",
  jvm_gc_pause_rate: "sum by (pod) (rate(jvm_gc_pause_seconds_sum[1m]))",
  open_files: "max by (pod) (process_files_open_files)",
  app_cpu_by_pod: "sum by (pod) (rate(container_cpu_usage_seconds_total{namespace=\"flowticket\",container!=\"\"}[1m]))",
  node_cpu_busy: "1 - avg by (instance) (rate(node_cpu_seconds_total{mode=\"idle\"}[1m]))",
  deployment_replicas: "sum by (deployment) (kube_deployment_status_replicas{namespace=\"flowticket\"})",
  // 사후 검사의 로그 범위 확인(pod-coverage.mjs). run 구간에 있었던 api 파드와 컨테이너 재시작 횟수.
  api_pods: "max by (pod) (kube_pod_info{namespace=\"flowticket\", pod=~\"flowticket-api(-queue)?-[a-z0-9]+-[a-z0-9]+\"})",
  api_restarts: "max by (pod) (kube_pod_container_status_restarts_total{namespace=\"flowticket\", container=\"api\", pod=~\"flowticket-api(-queue)?-[a-z0-9]+-[a-z0-9]+\"})",
  // run 조건 기록: 앱 파드의 노드 배치와 컨테이너 이미지 — 같은 조건 비교와 IMP before/after의 근거.
  app_pod_nodes: "max by (pod, node) (kube_pod_info{namespace=\"flowticket\", pod=~\"flowticket-(api|api-queue|web)-[a-z0-9]+-[a-z0-9]+\"})",
  app_images: "max by (pod, container, image) (kube_pod_container_info{namespace=\"flowticket\", container=~\"api|web\"})",
  // 비용(ADR-027 A'/B 비교) — 요청 CPU·노드 수를 run 구간으로 적분해 노드-시간·요청 코어-시간을 낸다(cost-summary.mjs).
  // 요청량은 스케줄러가 자리를 잡는 기준이라 노드 수를 정한다 — 사용량(app_cpu_by_pod)이 아니라 요청량이 비용에 가깝다.
  // Running 파드만 센다: KSM은 phase와 상관없이 spec의 요청량을 내므로, 노드를 기다리는 Pending·Evicted 파드가 "잡은 코어"로 섞인다.
  cpu_requests_by_pod:
    "sum by (pod) (kube_pod_container_resource_requests{namespace=\"flowticket\", resource=\"cpu\"} " +
    "* on (namespace, pod) group_left () (max by (namespace, pod) (kube_pod_status_phase{namespace=\"flowticket\", phase=\"Running\"}) == 1))",
  // Ready 노드 수 — EC2 기동 뒤 Ready 전 시간과 NotReady 구간은 빠지므로 청구 시간보다 약간 적다(IMP-032와 같은 정의).
  nodes_ready: "sum(kube_node_status_condition{condition=\"Ready\", status=\"true\"})",
  // 노드 배치 계산(자원 산정 계획 §2.2·§2.3 — 가용성 G1 시험 전 "노드 하나를 잃으면 남은 자리에 들어가나"). 모든 네임스페이스의 Running 파드
  // 요청을 노드별로 더한다(flowticket 밖 몫 — DaemonSet·모니터링·운영자 — 을 함께 보기 위해). 노드별 allocatable과 나란히 본다.
  node_cpu_requests:
    "sum by (node) (kube_pod_container_resource_requests{resource=\"cpu\"} " +
    "* on (namespace, pod) group_left () (max by (namespace, pod) (kube_pod_status_phase{phase=\"Running\"}) == 1))",
  node_memory_requests:
    "sum by (node) (kube_pod_container_resource_requests{resource=\"memory\"} " +
    "* on (namespace, pod) group_left () (max by (namespace, pod) (kube_pod_status_phase{phase=\"Running\"}) == 1))",
  node_allocatable: "max by (node, resource) (kube_node_status_allocatable{resource=~\"cpu|memory\"})",
  // 아웃박스(ADR-022)
  outbox_oldest_pending_age: "max(flowticket_outbox_oldest_pending_age_seconds)",
  // 구독자 0으로 나간 발행(ADR-028) — 파드별 원시 카운터. increase()는 새 파드의 첫 샘플 이전 증가를 놓쳐서 쓰지 않는다
  // (outbox-no-receivers.mjs가 원시 값으로 구간 증가를 계산한다).
  outbox_no_receivers_by_pod: "max by (pod) (flowticket_outbox_publish_no_receivers_total)",
};

const toSec = (s) => (/^\d+(\.\d+)?$/.test(s) ? Number(s) : Date.parse(s) / 1000);
const start = toSec(a.start);
const end = toSec(a.end);
if (!(end > start)) usage(`구간이 잘못됐다: ${a.start} ~ ${a.end}`);
mkdirSync(`${a.out}/prom`, { recursive: true });
// 이전 내보내기의 _meta.json을 먼저 지운다. 도중에 죽으면 _meta.json이 없어 사후 검사가 판정 불가로 끝난다
// (이전 구간의 _meta.json이 새 파일들과 섞여 읽히지 않게).
rmSync(`${a.out}/prom/_meta.json`, { force: true });

let failed = 0;
for (const [name, q] of Object.entries(QUERIES)) {
  const url = `${a.prom}/api/v1/query_range?query=${encodeURIComponent(q)}&start=${start}&end=${end}&step=${a.step}`;
  try {
    // 포트포워드가 멈춰도 끝나도록 질의마다 시간 제한을 둔다(시간 초과 = 그 질의 실패).
    const res = await fetch(url, { signal: AbortSignal.timeout(Number(a["query-timeout"]) * 1000) });
    const body = await res.text();
    writeFileSync(`${a.out}/prom/${name}.json`, body);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const n = JSON.parse(body)?.data?.result?.length ?? 0;
    console.error(`[prom] ${name}: 시계열 ${n}개`);
  } catch (e) {
    failed++;
    // 실패한 질의의 결과 파일은 지운다. 다시 내보내다 실패하면 이전 구간의 파일이 새 _meta.json과 함께 읽힐 수 있다
    // (HTTP 오류 본문은 위에서 이미 썼으므로 그것도 지운다 — 사후 검사는 파일이 없으면 판정 불가로 센다).
    rmSync(`${a.out}/prom/${name}.json`, { force: true });
    console.error(`[prom] ${name}: 실패 ${e.message || e}`);
  }
}
writeFileSync(`${a.out}/prom/_meta.json`, JSON.stringify({ prom: a.prom, start: a.start, end: a.end, startSec: start, endSec: end, step: Number(a.step), queries: QUERIES }, null, 2));
process.exitCode = failed ? 1 : 0;
