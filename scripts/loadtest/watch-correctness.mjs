#!/usr/bin/env node
// 정합성 실시간 감시(loadtest-100k-plan §3.3). run 동안 Prometheus를 주기적으로 조회해 세 조건을 판정한다.
//
//   over-admit      max by (event) (admitted)  > capacity          — 입장 토큰 수가 정원을 넘었다
//   카운터 어긋남     admit_count ≠ admitted (같은 파드·같은 이벤트) — 카운터와 집합이 갈라졌다(TS-024 같은 누수)
//   초과판매         max(seat_oversold) > 0                         — 한 좌석이 PAID 주문 둘 이상에 걸렸다
//
// 카운터 어긋남은 **같은 시계열끼리** 뺀다. 두 값은 승격 워커가 한 Lua로 같은 시점에 읽어 같은 레이블(파드·이벤트)로
// 내보낸다(QueueMetrics). 파드를 넘나들어 max끼리 비교하면 서로 다른 시점의 값이 섞여 오탐이 난다.
//
// 위반이 나오면 계획서의 "실시간 조건에 걸리면 즉시 중단"을 따른다: violation.json을 남기고, --on-violation 명령을
// 한 번 실행한다(예: 발생기의 k6를 멈추는 명령). 감시는 계속하고, 끝날 때 종료 코드 3으로 위반이 있었음을 알린다.
//
// Prometheus는 클러스터 안에만 있다. 먼저 포트포워드한다:
//   kubectl -n monitoring port-forward svc/prometheus-operated 9090:9090
//
//   node scripts/loadtest/watch-correctness.mjs --out artifacts/loadtest/<session>/<run> \
//     [--interval 5] [--for 600] [--on-violation "bash scripts/loadtest/loadgen.sh exec -- pkill -INT k6"]
import { exec } from "node:child_process";
import { appendFileSync, mkdirSync, writeFileSync } from "node:fs";
import { parseArgs } from "node:util";

const { values: a } = parseArgs({
  options: {
    prom: { type: "string", default: "http://localhost:9090" },
    out: { type: "string" },
    interval: { type: "string", default: "5" },
    for: { type: "string" },
    "on-violation": { type: "string" },
  },
});
if (!a.out) {
  console.error("사용: watch-correctness.mjs --out <run 디렉터리> [--interval 초] [--for 초] [--on-violation 명령]");
  process.exit(2);
}

export const CHECKS = {
  overAdmit: "max(max by (event) (flowticket_queue_admitted) - on() group_left() max(flowticket_queue_capacity))",
  counterDrift: "max(abs(flowticket_queue_admit_count - flowticket_queue_admitted))",
  oversold: "max(flowticket_seat_oversold)",
};

async function query(q) {
  const res = await fetch(`${a.prom}/api/v1/query?query=${encodeURIComponent(q)}`);
  if (!res.ok) throw new Error(`Prometheus 질의 실패 ${res.status}`);
  const body = await res.json();
  const v = body?.data?.result?.[0]?.value?.[1];
  // 시계열이 없으면(활성 이벤트 없음, 지표 수집 전) null. NaN은 수집 실패다. 둘 다 "판정 불가"로 기록하고 위반으로 보지 않는다.
  if (v === undefined) return null;
  const n = Number(v);
  return Number.isNaN(n) ? null : n;
}

mkdirSync(a.out, { recursive: true });
const LOG = `${a.out}/watch-correctness.jsonl`;
let violated = false;
let action = null; // 위반 시 실행한 명령. 끝나기 전에 감시기가 종료되지 않게 기다린다
let stopping = false;
const startedAt = Date.now();

async function tick() {
  const row = { t: new Date().toISOString() };
  const found = [];
  for (const [name, q] of Object.entries(CHECKS)) {
    try {
      row[name] = await query(q);
    } catch (e) {
      row[name] = null;
      row[`${name}Error`] = String(e.message || e);
    }
    if (row[name] !== null && row[name] > 0) found.push(name);
  }
  row.violations = found;
  appendFileSync(LOG, JSON.stringify(row) + "\n");
  if (found.length && !violated) {
    violated = true;
    writeFileSync(`${a.out}/violation.json`, JSON.stringify(row, null, 2));
    console.error(`[watch] 정합성 위반: ${found.join(", ")} — 계획서 §3.3: 즉시 중단`);
    if (a["on-violation"]) {
      action = new Promise((resolve) => {
        exec(a["on-violation"], (err, stdout, stderr) => {
          appendFileSync(`${a.out}/violation-action.log`, `${stdout}\n${stderr}\n${err ? "exit " + err.code : "exit 0"}\n`);
          resolve();
        });
      });
    }
  }
}

async function finish() {
  if (stopping) return;
  stopping = true;
  // 중단 명령(예: 발생기의 k6 정지)이 아직 돌고 있으면 최대 60초 기다린다. 감시기가 먼저 끝나면 명령이 잘린다.
  if (action) await Promise.race([action, new Promise((r) => setTimeout(r, 60_000))]);
  console.error(`[watch] 종료: ${violated ? "위반 있음(violation.json)" : "위반 없음"} — ${LOG}`);
  process.exit(violated ? 3 : 0);
}

process.on("SIGINT", finish);
const loop = async () => {
  while (!stopping) {
    await tick();
    if (a.for && Date.now() - startedAt >= Number(a.for) * 1000) await finish();
    await new Promise((r) => setTimeout(r, Number(a.interval) * 1000));
  }
};
loop();
