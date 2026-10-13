#!/usr/bin/env node
// run 구간의 노드-시간·요청 CPU 코어-시간과, 단가를 곱한 노드 비용(계산)을 낸다 — ADR-027 A'/B 비교의 비용 축.
//
//   node scripts/loadtest/cost-summary.mjs --prom <run>/prom --start <ISO> --end <ISO> [--step 10] [--node-price 0.118]
//
// 입력: export-prom.mjs가 남긴 nodes_ready.json·cpu_requests_by_pod.json(query_range).
// 적분: 각 샘플 값을 그 시각부터 step초 동안 유지한 것으로 본다(왼쪽 직사각형). 샘플이 빠진 구간은 0이다 —
//   파드가 사라진 뒤의 요청량을 이어 붙이지 않기 위해서다. step은 내보낸 step과 같게 준다(기본 10초).
// 출력은 **계산값**이다(청구서가 아니다). 노드 단가는 기본 m6i.large 서울 온디맨드 $0.118/h(IMP-032에 쓴 값).
//   Redis·NAT·RDS·EKS 같은 고정분은 A'/B가 같아 비교에서 뺀다.
//
// 종료 코드: 0 정상, 2 인자·입력 오류.
import { readFileSync } from "node:fs";
import { parseArgs } from "node:util";

const { values: a } = parseArgs({
  options: {
    prom: { type: "string" },
    start: { type: "string" },
    end: { type: "string" },
    step: { type: "string", default: "10" },
    "node-price": { type: "string", default: "0.118" },
  },
});

function fail(msg) {
  console.error(`cost-summary: ${msg}`);
  process.exit(2);
}

const start = Date.parse(a.start) / 1000;
const end = Date.parse(a.end) / 1000;
const step = Number(a.step);
const nodePrice = Number(a["node-price"]);
if (!a.prom || !(end > start) || !(step > 0) || !(nodePrice >= 0)) {
  fail("사용: --prom <dir> --start <ISO> --end <ISO> [--step 10] [--node-price 0.118]");
}

function load(name) {
  try {
    const j = JSON.parse(readFileSync(`${a.prom}/${name}.json`, "utf8"));
    if (j.status !== "success" || !Array.isArray(j.data?.result)) throw new Error("형식이 query_range 결과가 아니다");
    return j.data.result;
  } catch (e) {
    fail(`${name}.json을 읽지 못했다: ${e.message}`);
  }
}

/** 시계열 하나를 [start, end] 안에서 적분한다(값 × 초). 각 샘플은 [t, t+step)을 대표한다. */
function integrate(values, from, to, stepSec) {
  let sum = 0;
  for (const [t, v] of values) {
    const x = Number(v);
    if (!Number.isFinite(x)) continue;
    const lo = Math.max(Number(t), from);
    const hi = Math.min(Number(t) + stepSec, to);
    if (hi > lo) sum += x * (hi - lo);
  }
  return sum;
}

function groupOf(pod) {
  if (/^flowticket-api-queue-/.test(pod)) return "api-queue";
  if (/^flowticket-api-/.test(pod)) return "api";
  if (/^flowticket-web-/.test(pod)) return "web";
  return "other";
}

const hours = (end - start) / 3600;
const nodes = load("nodes_ready");
const nodeSeries = nodes[0]?.values ?? [];
if (nodeSeries.length === 0) fail("nodes_ready에 샘플이 없다");
const inWindow = nodeSeries.map(([t, v]) => [Number(t), Number(v)]).filter(([t]) => t >= start && t <= end);
const nodeHours = integrate(nodeSeries, start, end, step) / 3600;

const coreHours = { api: 0, "api-queue": 0, web: 0, other: 0 };
for (const s of load("cpu_requests_by_pod")) {
  const pod = s.metric?.pod ?? "";
  coreHours[groupOf(pod)] += integrate(s.values ?? [], start, end, step) / 3600;
}
const round = (x, d = 4) => Math.round(x * 10 ** d) / 10 ** d;

console.log(
  JSON.stringify(
    {
      note: "계산값 — 샘플 적분 × 단가(청구서 아님). 고정분(Redis·NAT·RDS·EKS)은 A'/B가 같아 제외",
      window: { start: a.start, end: a.end, hours: round(hours) },
      nodes: {
        nodeHours: round(nodeHours),
        min: inWindow.length ? Math.min(...inWindow.map(([, v]) => v)) : null,
        max: inWindow.length ? Math.max(...inWindow.map(([, v]) => v)) : null,
        avg: round(nodeHours / hours, 2),
      },
      cpuRequestCoreHours: Object.fromEntries(
        [...Object.entries(coreHours), ["total", Object.values(coreHours).reduce((x, y) => x + y, 0)]].map(([k, v]) => [k, round(v)])
      ),
      cost: { nodePriceUsdPerHour: nodePrice, nodeCostUsd: round(nodeHours * nodePrice, 2) },
    },
    null,
    1
  )
);
