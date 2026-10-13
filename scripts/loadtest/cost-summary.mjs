#!/usr/bin/env node
// run 구간의 노드-시간·요청 CPU 코어-시간과, 단가를 곱한 노드 비용(계산)을 낸다 — ADR-027 A'/B 비교의 비용 축.
//
//   node scripts/loadtest/cost-summary.mjs --prom <run>/prom --start <ISO+Z> --end <ISO+Z> [--step 10] [--node-price 0.118]
//
// 입력: export-prom.mjs가 남긴 nodes_ready.json·cpu_requests_by_pod.json(query_range)과 _meta.json(내보낸 구간·step).
// 적분: 각 샘플 값을 그 시각부터 step초 동안 유지한 것으로 본다(왼쪽 직사각형). step은 _meta.json 값을 쓴다 —
//   --step을 주면 _meta와 같아야 한다(다르면 노드-시간이 그 비율만큼 틀린다).
// 판정 불가(종료 2) — 틀린 값을 조용히 내지 않는다(pod-coverage.mjs·check-correctness.sh와 같은 원칙):
//   - 시각에 시간대(Z 또는 ±hh:mm)가 없음 — Date.parse가 로컬 시간으로 읽어 구간이 밀린다
//   - [start, end]가 내보낸 구간 밖으로 나감
//   - nodes_ready가 구간을 빈틈없이 덮지 않음(KSM 수집 공백) — 같은 KSM에서 오는 파드 요청량의 공백도 이것으로 잡는다
// 파드별 요청량의 빈 구간은 0이다 — 파드가 없던 구간이다(Running 파드만 내보낸다, export-prom.mjs).
// 출력은 **계산값**이다(청구서가 아니다). 노드 단가 기본값은 m6i.large 서울 온디맨드 $0.118/h(IMP-032에 쓴 값) —
//   loadtest.tfvars 프로파일의 노드 타입이다. 다른 프로파일(기본 t3.large)이면 --node-price를 맞춰 준다.
//   Redis·NAT·RDS·EKS 같은 고정분은 A'/B가 같아 비교에서 뺀다.
//
// 종료 코드: 0 정상, 2 인자·입력 오류 또는 판정 불가.
import { readFileSync } from "node:fs";
import { parseArgs } from "node:util";

const { values: a } = parseArgs({
  options: {
    prom: { type: "string" },
    start: { type: "string" },
    end: { type: "string" },
    step: { type: "string" },
    "node-price": { type: "string", default: "0.118" },
  },
});

function fail(msg) {
  console.error(`cost-summary: ${msg}`);
  process.exit(2);
}

const USAGE = "사용: --prom <dir> --start <ISO, Z 포함> --end <ISO, Z 포함> [--step 10] [--node-price 0.118]";
const TZ = /(Z|[+-]\d{2}:?\d{2})$/;
if (!a.prom || !a.start || !a.end) fail(USAGE);
if (!TZ.test(a.start) || !TZ.test(a.end)) fail(`시각에 시간대(Z 또는 ±hh:mm)가 없다 — 로컬 시간으로 읽혀 구간이 밀린다: ${a.start} ~ ${a.end}`);
const start = Date.parse(a.start) / 1000;
const end = Date.parse(a.end) / 1000;
const nodePrice = Number(a["node-price"]);
if (!(end > start) || !(nodePrice >= 0)) fail(USAGE);

function readJson(name) {
  try {
    return JSON.parse(readFileSync(`${a.prom}/${name}.json`, "utf8"));
  } catch (e) {
    fail(`${name}.json을 읽지 못했다: ${e.message}`);
  }
}

const meta = readJson("_meta");
const step = Number(meta.step);
if (!(step > 0)) fail("_meta.json에 step이 없다");
if (a.step !== undefined && Number(a.step) !== step) fail(`--step ${a.step}이 내보낸 step ${step}과 다르다`);
if (!(start >= Number(meta.startSec) && end <= Number(meta.endSec))) {
  fail(`구간 [${a.start}, ${a.end}]이 내보낸 구간 [${meta.start}, ${meta.end}] 밖으로 나간다`);
}

function load(name) {
  const j = readJson(name);
  if (j.status !== "success" || !Array.isArray(j.data?.result)) fail(`${name}.json이 query_range 결과가 아니다`);
  return j.data.result;
}

/** 샘플 [t, t+step) 중 구간 [from, to)와 겹치는 것만. */
const overlapping = (values, from, to) => values.map(([t, v]) => [Number(t), Number(v)]).filter(([t]) => t + step > from && t < to);

/** 시계열 하나를 구간 안에서 적분한다(값 × 초). 각 샘플은 [t, t+step)을 대표하고, 겹치는 길이만큼만 센다. */
function integrate(values, from, to) {
  let sum = 0;
  for (const [t, x] of overlapping(values, from, to)) {
    if (!Number.isFinite(x)) continue;
    sum += x * (Math.min(t + step, to) - Math.max(t, from));
  }
  return sum;
}

function groupOf(pod) {
  if (/^flowticket-api-queue-/.test(pod)) return "api-queue";
  if (/^flowticket-api-/.test(pod)) return "api";
  if (/^flowticket-web-/.test(pod)) return "web";
  return "other";
}

const nodeSeries = load("nodes_ready")[0]?.values ?? [];
const inWindow = overlapping(nodeSeries, start, end).sort((x, y) => x[0] - y[0]);
// 빈틈 검사: 첫 샘플이 start를 덮고, 이웃 샘플 간격이 step을 넘지 않고, 마지막 샘플이 end까지 덮어야 한다.
const EPS = 0.5;
if (inWindow.length === 0) fail("nodes_ready에 구간 안 샘플이 없다");
if (inWindow[0][0] > start + EPS) fail(`nodes_ready가 구간 시작을 덮지 않는다: 첫 샘플 ${new Date(inWindow[0][0] * 1000).toISOString()}`);
for (let i = 1; i < inWindow.length; i++) {
  if (inWindow[i][0] - inWindow[i - 1][0] > step + EPS) {
    fail(`nodes_ready 수집 공백: ${new Date(inWindow[i - 1][0] * 1000).toISOString()} → ${new Date(inWindow[i][0] * 1000).toISOString()} — KSM 공백이면 요청량도 비어 계산이 틀린다`);
  }
}
const last = inWindow[inWindow.length - 1][0];
if (last + step < end - EPS) fail(`nodes_ready가 구간 끝을 덮지 않는다: 마지막 샘플 ${new Date(last * 1000).toISOString()}`);

const hours = (end - start) / 3600;
const nodeHours = integrate(nodeSeries, start, end) / 3600;

const coreHours = { api: 0, "api-queue": 0, web: 0, other: 0 };
for (const s of load("cpu_requests_by_pod")) {
  coreHours[groupOf(s.metric?.pod ?? "")] += integrate(s.values ?? [], start, end) / 3600;
}
const round = (x, d = 4) => Math.round(x * 10 ** d) / 10 ** d;

console.log(
  JSON.stringify(
    {
      note: "계산값 — 샘플 적분 × 단가(청구서 아님). Ready 노드 기준이라 기동 중·NotReady 시간은 빠진다. 고정분(Redis·NAT·RDS·EKS)은 A'/B가 같아 제외",
      window: { start: a.start, end: a.end, hours: round(hours), step },
      nodes: {
        nodeHours: round(nodeHours),
        min: Math.min(...inWindow.map(([, v]) => v)),
        max: Math.max(...inWindow.map(([, v]) => v)),
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
