#!/usr/bin/env node
// run 시작 조건: 클러스터 CPU가 기준선으로 돌아왔는가(loadtest-100k-plan §2.4 ①, §6 회차 리셋).
//
// 직전 run의 부하(대기열 승격, 아웃박스 발행, GC, HPA가 늘린 파드)가 남아 있으면 다음 run은 같은 조건이 아니다.
// 측정 세션 시작 직후(부하 전)의 CPU를 기준선으로 기록해 두고, 매 run 전에 그 근처로 돌아올 때까지 기다린다.
//
// Prometheus는 클러스터 안에만 있다. 먼저 포트포워드한다:
//   kubectl -n monitoring port-forward svc/prometheus-operated 9090:9090
//
//   기록: node scripts/loadtest/wait-baseline.mjs record --out artifacts/loadtest/<session-id>/baseline.json
//   대기: node scripts/loadtest/wait-baseline.mjs wait --baseline artifacts/loadtest/<session-id>/baseline.json
//
// 허용 범위(기준선의 +10% 또는 +0.1 core 중 큰 쪽, 3회 연속)는 잠정값이다. 근거가 생기면 계획서에 고정한다.
import { readFileSync, writeFileSync } from "node:fs";
import { parseArgs } from "node:util";

const [cmd, ...rest] = process.argv.slice(2);
const { values: a } = parseArgs({
  args: rest,
  options: {
    prom: { type: "string", default: "http://localhost:9090" },
    out: { type: "string" },
    baseline: { type: "string" },
    "rel-tol": { type: "string", default: "0.10" },
    "abs-tol": { type: "string", default: "0.1" },
    stable: { type: "string", default: "3" },
    interval: { type: "string", default: "10" },
    timeout: { type: "string", default: "900" },
  },
});

// 측정 대상(flowticket 네임스페이스) 컨테이너 CPU 합계(core)와 노드 평균 CPU 사용률.
const QUERIES = {
  appCores: 'sum(rate(container_cpu_usage_seconds_total{namespace="flowticket",container!=""}[1m]))',
  nodeBusy: '1 - avg(rate(node_cpu_seconds_total{mode="idle"}[1m]))',
};

async function sample() {
  const out = {};
  for (const [k, q] of Object.entries(QUERIES)) {
    const res = await fetch(`${a.prom}/api/v1/query?query=${encodeURIComponent(q)}`);
    if (!res.ok) throw new Error(`Prometheus 질의 실패 ${res.status}: ${k}`);
    const body = await res.json();
    const v = body?.data?.result?.[0]?.value?.[1];
    if (v === undefined) throw new Error(`값 없음: ${k} — 포트포워드와 지표 수집을 확인한다`);
    out[k] = Number(v);
  }
  return out;
}

const sleep = (s) => new Promise((r) => setTimeout(r, s * 1000));
const median = (xs) => [...xs].sort((x, y) => x - y)[Math.floor(xs.length / 2)];

if (cmd === "record") {
  if (!a.out) throw new Error("--out이 필요하다");
  const xs = [];
  for (let i = 0; i < 5; i++) {
    xs.push(await sample());
    if (i < 4) await sleep(Number(a.interval));
  }
  const baseline = {
    recordedAt: new Date().toISOString(),
    appCores: median(xs.map((x) => x.appCores)),
    nodeBusy: median(xs.map((x) => x.nodeBusy)),
    samples: xs,
  };
  writeFileSync(a.out, JSON.stringify(baseline, null, 2));
  console.log(JSON.stringify({ appCores: baseline.appCores, nodeBusy: baseline.nodeBusy }));
} else if (cmd === "wait") {
  if (!a.baseline) throw new Error("--baseline이 필요하다");
  const base = JSON.parse(readFileSync(a.baseline, "utf8"));
  const limit = (b) => Math.max(b * (1 + Number(a["rel-tol"])), b + Number(a["abs-tol"]));
  const appLimit = limit(base.appCores);
  const deadline = Date.now() + Number(a.timeout) * 1000;
  let ok = 0;
  while (Date.now() < deadline) {
    const s = await sample();
    ok = s.appCores <= appLimit ? ok + 1 : 0;
    console.error(`[baseline] app ${s.appCores.toFixed(3)} core (한도 ${appLimit.toFixed(3)}), 노드 ${(s.nodeBusy * 100).toFixed(1)}% — 연속 ${ok}/${a.stable}`);
    if (ok >= Number(a.stable)) {
      console.log(JSON.stringify({ ready: true, ...s }));
      process.exit(0);
    }
    await sleep(Number(a.interval));
  }
  console.error("[baseline] 시간 안에 기준선으로 돌아오지 않았다 — 이 상태로 run을 시작하지 않는다");
  process.exit(1);
} else {
  console.error("사용: wait-baseline.mjs record --out <json> | wait --baseline <json> [--prom URL]");
  process.exit(2);
}
