#!/usr/bin/env node
// 정합성 실시간 감시(loadtest-100k-plan §3.3). run 동안 Prometheus를 주기적으로 조회해 세 조건을 판정한다.
//
//   over-admit      max by (event) (admitted)  > capacity          — 입장 토큰 수가 정원을 넘었다
//   카운터 어긋남     |admit_drift| > 0                               — 카운터와 집합이 갈라졌다(TS-024 같은 누수)
//   초과판매         max(seat_oversold) > 0                         — 한 좌석이 PAID 주문 둘 이상에 걸렸다
//
// 카운터 어긋남은 앱이 같은 스냅숏에서 계산해 내보낸 admit_drift만 본다(QueueMetrics). admit_count와 admitted를
// Prometheus에서 빼면 스크랩이 두 게이지를 서로 다른 틱에 읽은 값이 섞여 거짓 어긋남이 나온다(계획서 §5.1).
//
// 위반이 나오면 계획서의 "실시간 조건에 걸리면 즉시 중단"을 따른다: violation.json을 남기고, --on-violation 명령을
// 한 번 실행한다(예: 발생기의 k6를 멈추는 명령). 감시는 계속하고, 끝날 때 종료 코드로 결과를 알린다:
//   0 위반 없음 — 조회 오류가 한 번도 없었고, 세 조건 모두 감시 중 한 번 이상 값이 나왔다
//   3 위반 있음
//   4 판정 불가 — 조회 오류(연결 끊김·HTTP 오류)가 한 번이라도 있었거나, 값이 한 번도 나오지 않은 조건이 있다.
//     감시가 끊긴 구간에서는 위반이 없었다고 말할 수 없다. over-admit·카운터 어긋남은 사후 검사가 없어
//     이 종료 코드가 유일한 근거다.
// 시계열이 없는 틱(활성 이벤트가 없을 때)은 오류가 아니다. 대기열이 비면 대기열 게이지는 지워진다(QueueMetrics).
// 조건별 집계는 watch-summary.json에 남는다.
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
const INTERVAL = Number(a.interval);
const FOR = a.for === undefined ? null : Number(a.for);
if (!a.out || !(INTERVAL > 0) || (FOR !== null && !(FOR > 0))) {
  console.error("사용: watch-correctness.mjs --out <run 디렉터리> [--interval 초(>0)] [--for 초(>0)] [--on-violation 명령]");
  process.exitCode = 2;
} else {
  main();
}

function main() {
  const CHECKS = {
    overAdmit: "max(max by (event) (flowticket_queue_admitted) - on() group_left() max(flowticket_queue_capacity))",
    counterDrift: "max(abs(flowticket_queue_admit_drift))",
    oversold: "max(flowticket_seat_oversold)",
  };

  // 값: 숫자, 시계열 없음: null(오류 아님). 질의 실패·HTTP 오류·NaN(수집 실패)은 예외로 올린다.
  async function query(q) {
    const res = await fetch(`${a.prom}/api/v1/query?query=${encodeURIComponent(q)}`);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const body = await res.json();
    if (body?.status !== "success") throw new Error(`Prometheus 응답 status=${body?.status}`);
    const v = body?.data?.result?.[0]?.value?.[1];
    if (v === undefined) return null;
    const n = Number(v);
    if (Number.isNaN(n)) throw new Error("값이 NaN(앱 쪽 수집 실패)");
    return n;
  }

  mkdirSync(a.out, { recursive: true });
  const LOG = `${a.out}/watch-correctness.jsonl`;
  const stats = Object.fromEntries(Object.keys(CHECKS).map((k) => [k, { value: 0, noSeries: 0, error: 0 }]));
  let ticks = 0;
  let violated = false;
  let action = null; // 위반 시 실행한 명령. 끝나기 전에 감시기가 종료되지 않게 기다린다
  let stopping = false;
  let sleeper = null;
  const startedAt = Date.now();

  async function tick() {
    ticks++;
    const row = { t: new Date().toISOString() };
    const found = [];
    for (const [name, q] of Object.entries(CHECKS)) {
      try {
        row[name] = await query(q);
        stats[name][row[name] === null ? "noSeries" : "value"]++;
      } catch (e) {
        row[name] = null;
        row[`${name}Error`] = String(e.message || e);
        stats[name].error++;
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
    if (sleeper) {
      clearTimeout(sleeper.timer);
      sleeper.resolve();
    }
    // 중단 명령(예: 발생기의 k6 정지)이 아직 돌고 있으면 최대 60초 기다린다. 감시기가 먼저 끝나면 명령이 잘린다.
    if (action) {
      let t;
      await Promise.race([action, new Promise((r) => { t = setTimeout(r, 60_000); })]);
      clearTimeout(t);
    }
    const anyError = Object.values(stats).some((s) => s.error > 0);
    const neverValued = Object.entries(stats).filter(([, s]) => s.value === 0).map(([k]) => k);
    let code;
    let verdict;
    if (violated) {
      code = 3;
      verdict = "위반 있음(violation.json)";
    } else if (anyError || neverValued.length || ticks === 0) {
      code = 4;
      verdict = "판정 불가" + (anyError ? " — 조회 오류가 있었다" : "") +
        (neverValued.length ? ` — 값이 한 번도 나오지 않은 조건: ${neverValued.join(", ")}` : "");
    } else {
      code = 0;
      verdict = "위반 없음";
    }
    const summary = { ticks, startedAt: new Date(startedAt).toISOString(), endedAt: new Date().toISOString(), stats, verdict, exitCode: code };
    writeFileSync(`${a.out}/watch-summary.json`, JSON.stringify(summary, null, 2));
    console.error(`[watch] 종료: ${verdict} — 틱 ${ticks}, ` +
      Object.entries(stats).map(([k, s]) => `${k} 값 ${s.value}/없음 ${s.noSeries}/오류 ${s.error}`).join(", "));
    // process.exit 대신 종료 코드만 두고 자연스럽게 끝낸다(Windows에서 열린 핸들이 닫히는 중 exit하면 코드가 깨진 적이 있다).
    process.exitCode = code;
  }

  process.on("SIGINT", finish);
  process.on("SIGTERM", finish);

  (async () => {
    while (!stopping) {
      await tick();
      if (FOR !== null && Date.now() - startedAt >= FOR * 1000) {
        await finish();
        break;
      }
      await new Promise((resolve) => {
        sleeper = { resolve, timer: setTimeout(resolve, INTERVAL * 1000) };
      });
      sleeper = null;
    }
  })();
}
