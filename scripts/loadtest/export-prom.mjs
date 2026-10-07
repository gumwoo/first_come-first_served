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
  // SSE(§5.1). 파드마다 다른 값이라 합으로 묶는다.
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
  // 사후 검사의 로그 범위 확인(pod-coverage.mjs). run 구간에 있었던 api 파드와 컨테이너 재시작 횟수.
  api_pods: "max by (pod) (kube_pod_info{namespace=\"flowticket\", pod=~\"flowticket-api-[a-z0-9]+-[a-z0-9]+\"})",
  api_restarts: "max by (pod) (kube_pod_container_status_restarts_total{namespace=\"flowticket\", container=\"api\", pod=~\"flowticket-api-[a-z0-9]+-[a-z0-9]+\"})",
  // run 조건 기록: 앱 파드의 노드 배치와 컨테이너 이미지 — 같은 조건 비교와 IMP before/after의 근거.
  app_pod_nodes: "max by (pod, node) (kube_pod_info{namespace=\"flowticket\", pod=~\"flowticket-(api|web)-[a-z0-9]+-[a-z0-9]+\"})",
  app_images: "max by (pod, container, image) (kube_pod_container_info{namespace=\"flowticket\", container=~\"api|web\"})",
  // 아웃박스(ADR-022)
  outbox_oldest_pending_age: "max(flowticket_outbox_oldest_pending_age_seconds)",
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
