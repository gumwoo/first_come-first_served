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
// 승격 처리 실패(flowticket_queue_admit_tick_failures_total)도 함께 본다. 승격 워커가 이벤트 처리에 실패하면
// 대기열 게이지는 직전 값에 멈춘다. 그 구간의 over-admit·카운터 어긋남 값은 "현재 상태"가 아니므로, 감시 중
// 실패가 한 번이라도 늘었으면 위반이 없었다고 말할 수 없다(판정 불가). 파드별 시계열을 따로 추적해 파드 재시작
// (카운터 초기화)이나 감시 도중 새로 뜬 파드의 실패도 놓치지 않는다. 재시작은 process_start_time_seconds가 바뀐
// 것으로 판별한다 — 재시작 뒤 카운터가 이전 값 이상으로 올라와도 그만큼을 증가로 센다.
//
// 위반이 나오면 계획서의 "실시간 조건에 걸리면 즉시 중단"을 따른다: violation.json을 남기고, --on-violation 명령을
// 한 번 실행한다(예: 발생기의 k6를 멈추는 명령). 감시는 계속하고, 끝날 때 종료 코드로 결과를 알린다:
//   0 위반 없음 — 조회 오류가 한 번도 없었고, 세 조건과 승격 실패 카운터 모두 감시 중 한 번 이상 값이 나왔고,
//     승격 실패가 늘지 않았다
//   2 인자 오류
//   3 위반 있음
//   4 판정 불가 — 조회 오류(연결 끊김·HTTP 오류·조회 시간 초과)가 한 번이라도 있었거나, 값이 한 번도 나오지 않은
//     조건이 있거나, 항상 있어야 할 시계열이 빈 틱(관측 공백)이 있었거나, 감시 중 승격 처리 실패가 늘었다. 감시가 끊긴 구간에서는 위반이 없었다고 말할 수 없다.
//     over-admit·카운터 어긋남은 사후 검사가 없어 이 종료 코드가 유일한 근거다.
// 시계열이 없는 틱은 지표에 따라 다르게 본다.
//   - 대기열 게이지(over-admit·카운터 어긋남): 활성 이벤트가 없으면 지워진다(QueueMetrics). 오류가 아니다(noSeries).
//   - 초과판매·승격 실패 카운터: 앱이 뜰 때 조건 없이 등록된다(OperationalMetrics, QueueMetrics). 비었다면
//     스크랩 실패·스테일로 관측이 끊긴 것이다(gap). 한 틱이라도 있으면 판정 불가다 — 그 구간에서는 대기열 게이지가
//     비어 있어도 "활성 이벤트 없음"인지 "관측 공백"인지 구분할 수 없다.
// 조건별 집계는 watch-summary.json에 남는다.
//
// 종료(SIGINT·SIGTERM·--for 경과) 시 진행 중인 틱은 끝까지 기다린 뒤 판정한다. 조회마다 --query-timeout(기본 10초)이
// 걸려 있어 포트포워드가 멈춰도 기다림은 유한하다(시간 초과는 조회 오류 = 판정 불가). 판정을 쓴 뒤에는 새 조회를
// 하지 않는다. 단, 60초 안에 끝나지 않은 중단 명령의 출력은 판정 뒤에 violation-action.log에 덧붙을 수 있다.
//
// Prometheus는 클러스터 안에만 있다. 먼저 포트포워드한다:
//   kubectl -n monitoring port-forward svc/prometheus-operated 9090:9090
//
//   node scripts/loadtest/watch-correctness.mjs --out artifacts/loadtest/<session>/<run> \
//     [--interval 5] [--for 600] [--query-timeout 10] [--on-violation "bash scripts/loadtest/loadgen.sh exec -- pkill -INT k6"]
import { exec } from "node:child_process";
import { appendFileSync, mkdirSync, writeFileSync } from "node:fs";
import { parseArgs } from "node:util";

const USAGE =
  "사용: watch-correctness.mjs --out <run 디렉터리> [--interval 초(>0)] [--for 초(>0)] [--query-timeout 초(>0)] [--on-violation 명령]";
let a;
try {
  ({ values: a } = parseArgs({
    options: {
      prom: { type: "string", default: "http://localhost:9090" },
      out: { type: "string" },
      interval: { type: "string", default: "5" },
      for: { type: "string" },
      "query-timeout": { type: "string", default: "10" },
      "on-violation": { type: "string" },
    },
  }));
} catch (e) {
  console.error(`${e.message}\n${USAGE}`);
}
const positive = (s) => s !== undefined && /^\d+(\.\d+)?$/.test(s) && Number(s) > 0;
if (!a || !a.out || !positive(a.interval) || (a.for !== undefined && !positive(a.for)) || !positive(a["query-timeout"])) {
  if (a) console.error(USAGE);
  process.exitCode = 2;
} else {
  main();
}

function main() {
  const INTERVAL = Number(a.interval);
  const FOR = a.for === undefined ? null : Number(a.for);
  const QUERY_TIMEOUT_MS = Number(a["query-timeout"]) * 1000;

  const CHECKS = {
    overAdmit: "max(max by (event) (flowticket_queue_admitted) - on() group_left() max(flowticket_queue_capacity))",
    counterDrift: "max(abs(flowticket_queue_admit_drift))",
    oversold: "max(flowticket_seat_oversold)",
  };
  // 파드별 시계열 그대로 읽는다(합치면 파드 재시작으로 줄어든 값과 다른 파드의 증가가 상쇄된다).
  const TICK_FAILURES = "flowticket_queue_admit_tick_failures_total";
  const START_TIME = "process_start_time_seconds";
  // 앱이 뜰 때 조건 없이 등록되는 지표. 비면 관측 공백이다.
  const ALWAYS_PRESENT = new Set(["oversold", "tickFailures"]);

  // Prometheus 즉시 질의. 시간 초과·HTTP 오류·status≠success는 예외로 올린다.
  async function promQuery(q) {
    const res = await fetch(`${a.prom}/api/v1/query?query=${encodeURIComponent(q)}`, {
      signal: AbortSignal.timeout(QUERY_TIMEOUT_MS),
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const body = await res.json();
    if (body?.status !== "success") throw new Error(`Prometheus 응답 status=${body?.status}`);
    return body?.data?.result ?? [];
  }

  // 값: 숫자, 시계열 없음: null(오류 아님). NaN(앱 쪽 수집 실패)은 예외로 올린다.
  async function query(q) {
    const v = (await promQuery(q))[0]?.value?.[1];
    if (v === undefined) return null;
    const n = Number(v);
    if (Number.isNaN(n)) throw new Error("값이 NaN(앱 쪽 수집 실패)");
    return n;
  }

  mkdirSync(a.out, { recursive: true });
  const LOG = `${a.out}/watch-correctness.jsonl`;
  const stats = Object.fromEntries(
    [...Object.keys(CHECKS), "tickFailures"].map((k) => [k, { value: 0, noSeries: 0, gap: 0, error: 0 }]),
  );
  // 승격 처리 실패: 시계열(레이블 묶음)별 마지막 값과, 감시 중 늘어난 양의 합
  const failSeries = new Map();
  let failFirstTick = true;
  let tickFailuresIncrease = 0;
  let ticks = 0;
  let violated = false;
  let action = null; // 위반 시 실행한 명령. 끝나기 전에 감시기가 종료되지 않게 기다린다
  let stopping = false;
  let crashed = false; // 감시 루프가 예외로 죽었다(파일 쓰기 실패 등)
  let inflight = null; // 진행 중인 틱. 종료 시 끝까지 기다린 뒤 판정한다
  let sleeper = null;
  const startedAt = Date.now();

  async function checkTickFailures(row) {
    try {
      const series = await promQuery(TICK_FAILURES);
      // 인스턴스별 프로세스 시작 시각. 바뀌었으면 재시작이다.
      const starts = new Map((await promQuery(START_TIME)).map((s) => [s.metric?.instance, s.value?.[1]]));
      if (series.length === 0) {
        stats.tickFailures.gap++;
        row.tickFailures = null;
      } else {
        stats.tickFailures.value++;
        let inc = 0;
        for (const s of series) {
          // 같은 파드라도 프로세스가 바뀌면 다른 시계열로 본다(재시작 = 카운터가 0부터 다시 센다).
          const key = `${JSON.stringify(s.metric)}@${starts.get(s.metric?.instance) ?? "?"}`;
          const v = Number(s.value?.[1]);
          if (!Number.isFinite(v)) throw new Error(`승격 실패 카운터 값이 숫자가 아니다: ${s.value?.[1]}`);
          const prev = failSeries.get(key);
          // 첫 틱에 본 값은 기준선이다. 감시 도중 처음 나타난 시계열(새 파드·재시작한 프로세스)은 값 전체가
          // 감시 중 실패다. 시작 시각을 못 읽었는데 값이 줄었으면 초기화로 보고 현재 값 전체를 증가로 센다.
          if (prev === undefined) inc += failFirstTick ? 0 : v;
          else inc += v >= prev ? v - prev : v;
          failSeries.set(key, v);
        }
        tickFailuresIncrease += inc;
        row.tickFailures = inc;
      }
      failFirstTick = false;
    } catch (e) {
      row.tickFailures = null;
      row.tickFailuresError = String(e.message || e);
      stats.tickFailures.error++;
    }
  }

  async function tick() {
    ticks++;
    const row = { t: new Date().toISOString() };
    const found = [];
    for (const [name, q] of Object.entries(CHECKS)) {
      try {
        row[name] = await query(q);
        stats[name][row[name] !== null ? "value" : ALWAYS_PRESENT.has(name) ? "gap" : "noSeries"]++;
      } catch (e) {
        row[name] = null;
        row[`${name}Error`] = String(e.message || e);
        stats[name].error++;
      }
      if (row[name] !== null && row[name] > 0) found.push(name);
    }
    await checkTickFailures(row);
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
    process.exitCode = 4; // 판정을 끝까지 못 쓰고 죽으면 판정 불가로 남는다
    if (sleeper) {
      clearTimeout(sleeper.timer);
      sleeper.resolve();
    }
    // 진행 중인 틱을 끝까지 기다린다. 조회마다 시간 제한이 있어 기다림은 유한하다.
    // 기다리지 않고 판정하면 늦게 온 위반 응답이 violation.json을 남기고도 종료 코드가 0이 된다.
    if (inflight) {
      try {
        await inflight;
      } catch {
        crashed = true;
      }
    }
    // 중단 명령(예: 발생기의 k6 정지)이 아직 돌고 있으면 최대 60초 기다린다. 감시기가 먼저 끝나면 명령이 잘린다.
    if (action) {
      let t;
      await Promise.race([action, new Promise((r) => { t = setTimeout(r, 60_000); })]);
      clearTimeout(t);
    }
    const anyError = Object.values(stats).some((s) => s.error > 0);
    const neverValued = Object.entries(stats).filter(([, s]) => s.value === 0).map(([k]) => k);
    const gaps = Object.entries(stats).filter(([, s]) => s.gap > 0).map(([k, s]) => `${k} ${s.gap}틱`);
    let code;
    let verdict;
    if (violated) {
      code = 3;
      verdict = "위반 있음(violation.json)";
    } else if (crashed || anyError || neverValued.length || gaps.length || ticks === 0 || tickFailuresIncrease > 0) {
      code = 4;
      verdict = "판정 불가" + (crashed ? " — 감시 루프가 예외로 멈췄다" : "") + (anyError ? " — 조회 오류가 있었다" : "") +
        (neverValued.length ? ` — 값이 한 번도 나오지 않은 조건: ${neverValued.join(", ")}` : "") +
        (gaps.length ? ` — 관측 공백(항상 있어야 할 시계열이 빔): ${gaps.join(", ")}` : "") +
        (tickFailuresIncrease > 0 ? ` — 감시 중 승격 처리 실패 ${tickFailuresIncrease}건(대기열 게이지가 멈췄을 수 있다)` : "");
    } else {
      code = 0;
      verdict = "위반 없음";
    }
    const summary = {
      ticks,
      startedAt: new Date(startedAt).toISOString(),
      endedAt: new Date().toISOString(),
      stats,
      tickFailuresIncrease,
      verdict,
      exitCode: code,
    };
    writeFileSync(`${a.out}/watch-summary.json`, JSON.stringify(summary, null, 2));
    console.error(`[watch] 종료: ${verdict} — 틱 ${ticks}, ` +
      Object.entries(stats).map(([k, s]) => `${k} 값 ${s.value}/없음 ${s.noSeries}/공백 ${s.gap}/오류 ${s.error}`).join(", "));
    // process.exit 대신 종료 코드만 두고 자연스럽게 끝낸다(Windows에서 열린 핸들이 닫히는 중 exit하면 코드가 깨진 적이 있다).
    process.exitCode = code;
  }

  const stop = () =>
    finish().catch((e) => {
      console.error(`[watch] 판정 기록 실패: ${e.stack || e}`);
      process.exitCode = 4;
    });
  process.on("SIGINT", stop);
  process.on("SIGTERM", stop);

  (async () => {
    while (!stopping) {
      inflight = tick();
      await inflight;
      inflight = null;
      if (stopping) break; // 틱 도중 종료 신호가 왔다. 판정은 finish가 한다
      if (FOR !== null && Date.now() - startedAt >= FOR * 1000) {
        await stop();
        break;
      }
      await new Promise((resolve) => {
        sleeper = { resolve, timer: setTimeout(resolve, INTERVAL * 1000) };
      });
      sleeper = null;
    }
  })().catch((e) => {
    // 감시 루프 자체가 예외로 죽으면(파일 쓰기 실패 등) 위반이 없었다고 말할 수 없다. 판정 불가로 마무리한다.
    console.error(`[watch] 감시 실패: ${e.stack || e}`);
    crashed = true;
    inflight = null;
    stop();
  });
}
