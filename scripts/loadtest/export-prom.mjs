#!/usr/bin/env node
// run 구간의 Prometheus 데이터를 run 디렉터리에 남긴다(loadtest-100k-plan §7 "원시 데이터").
//
// Prometheus는 철거 때 함께 사라지고, 철거하지 않아도 보존 기간이 6시간이다. 그래서 run이 끝날 때마다 범위 질의
// 결과(query_range JSON)를 그대로 저장한다. 파일 하나가 질의 하나다(prom/<이름>.json).
//
//   kubectl -n monitoring port-forward svc/prometheus-operated 9090:9090
//   node scripts/loadtest/export-prom.mjs --out artifacts/loadtest/<session>/<run> \
//     --start 2026-10-02T05:00:00Z --end 2026-10-02T05:12:00Z [--step 15]
//
// --step 기본 15초는 Prometheus 수집 주기(servicemonitor-api.yaml)와 같다. 그보다 잘게 질의해도 새 정보가 없다.
import { mkdirSync, writeFileSync } from "node:fs";
import { parseArgs } from "node:util";

const { values: a } = parseArgs({
  options: {
    prom: { type: "string", default: "http://localhost:9090" },
    out: { type: "string" },
    start: { type: "string" },
    end: { type: "string" },
    step: { type: "string", default: "15" },
  },
});
if (!a.out || !a.start || !a.end) {
  console.error("사용: export-prom.mjs --out <run 디렉터리> --start <ISO> --end <ISO> [--step 초]");
  process.exit(2);
}

// 계획서 §3(판정)·§5(계측)에서 쓰는 값들. 이름이 파일 이름이 된다.
export const QUERIES = {
  // 대기열(§5.1). 파드마다 같은 값이라 max로 묶는다(QueueMetrics).
  queue_waiting: "max by (event) (flowticket_queue_waiting)",
  queue_admitted: "max by (event) (flowticket_queue_admitted)",
  queue_admit_count: "max by (event) (flowticket_queue_admit_count)",
  queue_capacity: "max(flowticket_queue_capacity)",
  queue_admit_drift: "max by (event) (abs(flowticket_queue_admit_drift))",
  queue_admit_tick_failures_rate: "sum(rate(flowticket_queue_admit_tick_failures_total[1m]))",
  queue_admit_tick_p95: "histogram_quantile(0.95, sum by (le) (rate(flowticket_queue_admit_tick_seconds_bucket[1m])))",
  queue_gate_fallback_rate: "sum(rate(flowticket_queue_gate_fallback_total[1m]))",
  // SSE(§5.1). 파드마다 다른 값이라 합으로 묶는다.
  sse_connections: "sum(flowticket_queue_sse_connections)",
  sse_send_failures_rate: "sum by (phase) (rate(flowticket_queue_sse_send_failures_total[1m]))",
  // 정합성(§3.3)
  seat_oversold: "max(flowticket_seat_oversold)",
  // API 지연·처리량(§3.2 판정, §3.4 SLO)
  http_p95_by_uri: "histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket[1m])))",
  http_rps_by_uri: "sum by (uri) (rate(http_server_requests_seconds_count[1m]))",
  http_5xx_rate: "sum(rate(http_server_requests_seconds_count{status=~\"5..\"}[1m]))",
  // 진단 신호(§3.2)
  hikari_pending: "max by (pod) (hikaricp_connections_pending)",
  tomcat_connections: "sum by (pod) (tomcat_connections_current_connections)",
  tomcat_threads_busy: "sum by (pod) (tomcat_threads_busy_threads)",
  jvm_heap_used: "sum by (pod) (jvm_memory_used_bytes{area=\"heap\"})",
  jvm_gc_pause_rate: "sum by (pod) (rate(jvm_gc_pause_seconds_sum[1m]))",
  open_files: "max by (pod) (process_files_open_files)",
  app_cpu_by_pod: "sum by (pod) (rate(container_cpu_usage_seconds_total{namespace=\"flowticket\",container!=\"\"}[1m]))",
  node_cpu_busy: "1 - avg by (instance) (rate(node_cpu_seconds_total{mode=\"idle\"}[1m]))",
  deployment_replicas: "sum by (deployment) (kube_deployment_status_replicas{namespace=\"flowticket\"})",
  // 아웃박스(ADR-022)
  outbox_oldest_pending_age: "max(flowticket_outbox_oldest_pending_age_seconds)",
};

mkdirSync(`${a.out}/prom`, { recursive: true });
const toSec = (s) => (/^\d+(\.\d+)?$/.test(s) ? Number(s) : Date.parse(s) / 1000);
const start = toSec(a.start);
const end = toSec(a.end);
if (!(end > start)) throw new Error(`구간이 잘못됐다: ${a.start} ~ ${a.end}`);

let failed = 0;
for (const [name, q] of Object.entries(QUERIES)) {
  const url = `${a.prom}/api/v1/query_range?query=${encodeURIComponent(q)}&start=${start}&end=${end}&step=${a.step}`;
  try {
    const res = await fetch(url);
    const body = await res.text();
    writeFileSync(`${a.out}/prom/${name}.json`, body);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const n = JSON.parse(body)?.data?.result?.length ?? 0;
    console.error(`[prom] ${name}: 시계열 ${n}개`);
  } catch (e) {
    failed++;
    console.error(`[prom] ${name}: 실패 ${e.message || e}`);
  }
}
writeFileSync(`${a.out}/prom/_meta.json`, JSON.stringify({ prom: a.prom, start: a.start, end: a.end, step: Number(a.step), queries: QUERIES }, null, 2));
process.exit(failed ? 1 : 0);
