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
//     관측 공백이 한 틱도 없었고, 조회 간격이 14초(스크랩 주기 15초 − 여유 1초)를 넘은 적이 없고, 승격 실패가 늘지 않았다
//   2 인자 오류
//   3 위반 있음
//   4 판정 불가 — 조회 오류(연결 끊김·HTTP 오류·조회 시간 초과)가 한 번이라도 있었거나, 값이 한 번도 나오지 않은
//     조건이 있거나, 항상 있어야 할 시계열이 빈 틱(관측 공백)이 있었거나, 조회 간격이 스크랩 주기를 넘었거나,
//     감시 중 승격 처리 실패가 늘었다. 감시가 끊긴 구간에서는 위반이 없었다고 말할 수 없다.
//     over-admit·카운터 어긋남은 실시간 근거가 이 종료 코드뿐이다. run 끝 구간은 사후 재확인(prom-recheck.mjs)이 덮는다.
// 시계열이 없는 틱은 지표에 따라 다르게 본다.
//   - 대기열 게이지(over-admit·카운터 어긋남): 활성 이벤트가 없으면 지워진다(QueueMetrics). 오류가 아니다(noSeries).
//   - 초과판매·승격 실패 카운터: 앱이 뜰 때 조건 없이 등록된다(OperationalMetrics, QueueMetrics). 비었다면
//     스크랩 실패·스테일로 관측이 끊긴 것이다(gap). 한 틱이라도 있으면 판정 불가다 — 그 구간에서는 대기열 게이지가
//     비어 있어도 "활성 이벤트 없음"인지 "관측 공백"인지 구분할 수 없다.
//   - 값이 있어도 오래된 샘플이면 관측 공백이다. Prometheus는 스크랩이 늦어지면 5분(lookback) 안의 마지막 샘플을
//     그대로 돌려준다. 항상 있어야 할 두 지표의 가장 오래된 샘플 나이가 --max-sample-age(기본 30초 = 스크랩 주기 15초 +
//     여유, 잠정값)를 넘으면 그 틱은 관측 공백(freshness)이다.
// --interval(틱 시작 사이 간격, 기본 5초)은 10초 이하만 받는다. 그래도 조회가 느리면 실제 간격이 늘어나므로, 조건마다
// 연속 조회 사이의 실제 간격을 재서 14초(스크랩 주기 15초 − 여유 1초, 잠정값)를 넘은 적이 있으면 판정 불가로 센다 —
// 그 사이 스크랩된 샘플(예: 순간적인 over-admit)을 보지 못했을 수 있다.
//
// 감시기는 구조상 run 끝을 보지 못한다(마지막 조회가 run 종료보다 앞서고, 마지막 상태는 종료 뒤 스크랩에야 들어온다).
// 그래서 감시기의 0만으로는 "위반 없음"이 아니다 — 사후 검사(check-correctness.sh → prom-recheck.mjs)가 내보낸 데이터로
// 같은 조건을 run 종료 + 30초까지 다시 보고, 그 단계까지 통과해야 위반이 없었다고 말할 수 있다.
// 조건별 집계는 watch-summary.json에 남는다.
//
// 종료(SIGINT·SIGTERM·--for 경과) 시 진행 중인 틱은 끝까지 기다린 뒤 판정한다. 조회마다 --query-timeout(기본 10초)이
// 걸려 있어 포트포워드가 멈춰도 기다림은 유한하다(시간 초과는 조회 오류 = 판정 불가). 판정을 쓴 뒤에는 새 조회를
// 하지 않고 아무것도 기록하지 않는다. 중단 명령은 최대 60초 기다린다. 그때까지 끝나지 않으면 그 사실을
// violation-action.log에 남기고 명령과의 연결을 끊은 뒤 종료한다(명령 자체는 계속 돌 수 있다).
//
// --on-violation은 child_process.exec로 실행한다 — Windows에서는 cmd.exe가 해석한다. 이 PC의 PowerShell·cmd에서는
// `bash`가 WSL로 연결돼 실패한 적이 있으므로(G1 실측), 감시기를 Git Bash에서 띄우거나 명령에 bash 경로를 직접 쓴다.
// --on-violation이 없으면 시작할 때 경고한다 — 위반이 나도 부하를 사람이 직접 멈춰야 한다.
//
// Prometheus는 클러스터 안에만 있다. 먼저 포트포워드한다:
//   kubectl -n monitoring port-forward svc/prometheus-operated 9090:9090
//
//   node scripts/loadtest/watch-correctness.mjs --out artifacts/loadtest/<session>/<run> \
//     [--interval 5] [--for 600] [--query-timeout 10] [--max-sample-age 30] [--on-violation "bash scripts/loadtest/loadgen.sh exec -- pkill -INT k6"]
import { exec } from "node:child_process";
import { appendFileSync, existsSync, mkdirSync, writeFileSync } from "node:fs";
import { parseArgs } from "node:util";

const USAGE =
  "사용: watch-correctness.mjs --out <run 디렉터리> [--interval 초(0<,≤10)] [--for 초(>0)] [--query-timeout 초(>0)] [--max-sample-age 초(0<,≤60)] [--on-violation 명령]";
// 스크랩 주기(servicemonitor-api.yaml interval: 15s). 연속 조회 사이 간격이 이보다 1초 이상 짧아야 그 사이 샘플을
// 놓치지 않는다고 본다(스크랩 처리 지연의 흔들림에 대한 여유 1초, 잠정값). --interval은 여유 있게 10초까지만 받는다.
const SCRAPE_SEC = 15;
const MAX_QUERY_GAP_SEC = SCRAPE_SEC - 1;
const MAX_INTERVAL_SEC = 10;
let a;
try {
  ({ values: a } = parseArgs({
    options: {
      prom: { type: "string", default: "http://localhost:9090" },
      out: { type: "string" },
      interval: { type: "string", default: "5" },
      for: { type: "string" },
      "query-timeout": { type: "string", default: "10" },
      "max-sample-age": { type: "string", default: "30" },
      "on-violation": { type: "string" },
    },
  }));
} catch (e) {
  console.error(`${e.message}\n${USAGE}`);
}
const positive = (s) => s !== undefined && /^\d+(\.\d+)?$/.test(s) && Number(s) > 0;
if (!a || !a.out || !positive(a.interval) || Number(a.interval) > MAX_INTERVAL_SEC || (a.for !== undefined && !positive(a.for)) ||
    !positive(a["query-timeout"]) || !positive(a["max-sample-age"]) || Number(a["max-sample-age"]) > 60) {
  if (a) console.error(USAGE);
  process.exitCode = 2;
} else {
  // 감시를 시작하기 전의 실패(--out 디렉터리를 만들 수 없음 등)는 인자·환경 오류(2)다. 잡지 않으면 Node 기본값 1이 된다.
  try {
    main();
  } catch (e) {
    console.error(`[watch] 시작 실패: ${e.message || e}`);
    process.exitCode = 2;
  }
}

function main() {
  const INTERVAL = Number(a.interval);
  const FOR = a.for === undefined ? null : Number(a.for);
  const QUERY_TIMEOUT_MS = Number(a["query-timeout"]) * 1000;
  const MAX_SAMPLE_AGE = Number(a["max-sample-age"]);

  const CHECKS = {
    overAdmit: "max(max by (event) (flowticket_queue_admitted) - on() group_left() max(flowticket_queue_capacity))",
    counterDrift: "max(abs(flowticket_queue_admit_drift))",
    oversold: "max(flowticket_seat_oversold)",
  };
  // 파드별 시계열 그대로 읽는다(합치면 파드 재시작으로 줄어든 값과 다른 파드의 증가가 상쇄된다).
  const TICK_FAILURES = "flowticket_queue_admit_tick_failures_total";
  // 같은 네임스페이스로 좁힌다(다른 대상의 instance와 겹치지 않게).
  const START_TIME = 'process_start_time_seconds{namespace="flowticket"}';
  // 앱이 뜰 때 조건 없이 등록되는 지표. 비면 관측 공백이다.
  const ALWAYS_PRESENT = new Set(["oversold", "tickFailures"]);
  // 항상 있어야 할 두 지표 중 가장 오래된 샘플의 나이(초). 스크랩이 늦어 오래된 값을 "현재 값"으로 읽는 것을 막는다.
  // timestamp()는 지표를 직접 고른 식(벡터 셀렉터)에만 샘플 시각을 준다 — 그래서 지표마다 따로 건다. 또 timestamp()는
  // 지표 이름을 떼므로, 같은 파드의 두 지표는 레이블이 같아져 정규식 하나로 묶으면 "same labelset" 오류가 난다.
  // 지표마다 구분 레이블을 붙인 뒤 or로 합친다(G1이 promtool로 확인한 형태).
  const SAMPLE_AGE =
    'max(label_replace(time() - timestamp(flowticket_seat_oversold), "m", "oversold", "", "")' +
    ' or label_replace(time() - timestamp(flowticket_queue_admit_tick_failures_total), "m", "tickfail", "", ""))';

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
  // 같은 run 디렉터리에서 다시 돌리면 이전 감시의 결과(watch-summary.json)·첫 위반(violation.json)이 덮이거나 섞인다.
  // 사후 검사가 둘을 이 run의 근거로 읽으므로, 이미 있으면 시작하지 않는다(새 run 디렉터리를 쓴다).
  for (const f of ["watch-summary.json", "violation.json", "watch-correctness.jsonl"]) {
    if (existsSync(`${a.out}/${f}`)) throw new Error(`이미 감시 결과가 있다: ${a.out}/${f} — 새 run 디렉터리를 쓴다`);
  }
  const LOG = `${a.out}/watch-correctness.jsonl`;
  const stats = Object.fromEntries(
    [...Object.keys(CHECKS), "tickFailures", "freshness"].map((k) => [k, { value: 0, noSeries: 0, gap: 0, error: 0 }]),
  );
  // 승격 처리 실패: 시계열(레이블 묶음)별 마지막 값과, 감시 중 늘어난 양의 합
  const failSeries = new Map();
  let failFirstTick = true;
  let tickFailuresIncrease = 0;
  let ticks = 0;
  let violated = false;
  let action = null; // 위반 시 실행한 명령. 끝나기 전에 감시기가 종료되지 않게 기다린다
  let actionChild = null;
  let finalized = false; // 판정을 썼다. 이후에는 아무것도 기록하지 않는다
  let stopping = false;
  let crashed = false; // 감시 루프가 예외로 죽었다(파일 쓰기 실패 등)
  let inflight = null; // 진행 중인 틱. 종료 시 끝까지 기다린 뒤 판정한다
  let sleeper = null;
  const startedAt = Date.now();
  // 조건마다 직전 조회 시각과, 모든 조건을 통틀어 연속 조회 사이 간격의 최댓값. 간격이 14초를 넘으면 그 사이
  // 스크랩된 샘플을 보지 못했을 수 있다(틱 시작 간격은 --interval이지만, 조회가 느리면 그보다 길어진다).
  const lastQueryAt = {};
  let maxQueryGapSec = 0;
  let cadenceGaps = 0;

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
        let noStart = false;
        for (const s of series) {
          // 시작 시각이 없으면 재시작 판별이 "값 감소"에만 기대게 된다(재시작 뒤 같은 값까지 올라오면 놓친다). 관측 공백으로 센다.
          if (!starts.has(s.metric?.instance)) noStart = true;
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
        if (noStart) {
          stats.tickFailures.gap++;
          row.tickFailuresNoStartTime = true;
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
      const now = Date.now();
      if (lastQueryAt[name] !== undefined) {
        const gapSec = (now - lastQueryAt[name]) / 1000;
        if (gapSec > maxQueryGapSec) maxQueryGapSec = gapSec;
        if (gapSec > MAX_QUERY_GAP_SEC) {
          cadenceGaps++;
          row[`${name}QueryGapSec`] = gapSec;
        }
      }
      lastQueryAt[name] = now;
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
    try {
      const age = await query(SAMPLE_AGE);
      row.sampleAgeSec = age;
      if (age === null || age > MAX_SAMPLE_AGE) stats.freshness.gap++;
      else stats.freshness.value++;
    } catch (e) {
      row.sampleAgeSec = null;
      row.freshnessError = String(e.message || e);
      stats.freshness.error++;
    }
    row.violations = found;
    appendFileSync(LOG, JSON.stringify(row) + "\n");
    if (found.length && !violated) {
      violated = true;
      writeFileSync(`${a.out}/violation.json`, JSON.stringify(row, null, 2));
      console.error(`[watch] 정합성 위반: ${found.join(", ")} — 계획서 §3.3: 즉시 중단`);
      if (a["on-violation"]) {
        action = new Promise((resolve) => {
          actionChild = exec(a["on-violation"], (err, stdout, stderr) => {
            if (!finalized) {
              appendFileSync(`${a.out}/violation-action.log`, `${stdout}\n${stderr}\n${err ? "exit " + err.code : "exit 0"}\n`);
            }
            resolve(true);
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
      const done = await Promise.race([action, new Promise((r) => { t = setTimeout(() => r(false), 60_000); })]);
      clearTimeout(t);
      if (!done) {
        // 끝나지 않은 명령을 기다리지 않는다. 출력 연결을 끊고 참조를 풀어야 이벤트 루프가 명령에 붙잡히지 않는다.
        appendFileSync(`${a.out}/violation-action.log`, "60초 안에 끝나지 않음 — 감시기는 기다리지 않고 종료했다(명령은 계속 돌 수 있다)\n");
        actionChild?.stdout?.destroy();
        actionChild?.stderr?.destroy();
        actionChild?.unref();
      }
    }
    const anyError = Object.values(stats).some((s) => s.error > 0);
    const neverValued = Object.entries(stats).filter(([, s]) => s.value === 0).map(([k]) => k);
    const gaps = Object.entries(stats).filter(([, s]) => s.gap > 0).map(([k, s]) => `${k} ${s.gap}틱`);
    let code;
    let verdict;
    if (violated) {
      code = 3;
      verdict = "위반 있음(violation.json)";
    } else if (crashed || anyError || neverValued.length || gaps.length || cadenceGaps > 0 || ticks === 0 || tickFailuresIncrease > 0) {
      code = 4;
      verdict = "판정 불가" + (crashed ? " — 감시 루프가 예외로 멈췄다" : "") + (anyError ? " — 조회 오류가 있었다" : "") +
        (neverValued.length ? ` — 값이 한 번도 나오지 않은 조건: ${neverValued.join(", ")}` : "") +
        (gaps.length ? ` — 관측 공백(항상 있어야 할 시계열·프로세스 시작 시각이 비었거나 샘플이 오래됨): ${gaps.join(", ")}` : "") +
        (cadenceGaps > 0 ? ` — 조회 간격이 ${MAX_QUERY_GAP_SEC}초(스크랩 주기 ${SCRAPE_SEC}초 − 여유 1초)를 넘은 적이 ${cadenceGaps}번(최대 ${maxQueryGapSec.toFixed(2)}초, 그 사이 샘플을 못 봤을 수 있다)` : "") +
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
      maxQueryGapSec,
      cadenceGaps,
      verdict,
      exitCode: code,
    };
    writeFileSync(`${a.out}/watch-summary.json`, JSON.stringify(summary, null, 2));
    finalized = true;
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
  if (!a["on-violation"]) {
    console.error("[watch] 경고: --on-violation이 없다. 위반이 나도 부하를 자동으로 멈추지 않는다 — 사람이 직접 멈춰야 한다");
  }

  (async () => {
    while (!stopping) {
      const tickStart = Date.now();
      inflight = tick();
      await inflight;
      inflight = null;
      if (stopping) break; // 틱 도중 종료 신호가 왔다. 판정은 finish가 한다
      if (FOR !== null && Date.now() - startedAt >= FOR * 1000) {
        await stop();
        break;
      }
      await new Promise((resolve) => {
        // 틱 시작 간격이 --interval이 되도록 틱이 걸린 시간을 뺀다(늦어진 간격은 위에서 관측 공백으로 센다).
        sleeper = { resolve, timer: setTimeout(resolve, Math.max(0, INTERVAL * 1000 - (Date.now() - tickStart))) };
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
