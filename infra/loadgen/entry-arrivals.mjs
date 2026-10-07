#!/usr/bin/env node
// 진입 발생기 원시 출력(k6 --out json)에서 계획서 §2.3의 부하 지표를 계산한다.
//
// "10K"라고 쓸 때 그것이 걸어 준 값인지, 실제 시작된 진입인지, 처리된 진입인지, HTTP rps인지를 나눈다.
//   offered arrivals    — 발생기가 시작하려 한 진입 수 = achieved + no-user + dropped
//   achieved arrivals   — 실제로 진입 요청을 보낸 수(entry_arrivals 시계열의 합)
//   no-user             — iteration은 시작됐지만 배정할 사용자가 없어 요청을 보내지 않은 수(entry_no_user)
//   dropped_iterations  — 시작하지 못한 진입. 0이 아니면 그 run은 무효(§3.1)
//   processed           — 토큰 발급 200(entry_processed)
//   peak 1s arrivals    — entry_arrivals를 1초 창으로 묶은 최대값. 서버 지표(15초·1분 해상도)로는 못 낸다
//   http_reqs           — 진입 외 요청까지 포함한 HTTP 요청 수(진입 arrivals와 섞지 않는다)
//
// 분산 실행이면 발생기마다 나온 파일을 모두 넘긴다. 1초 창은 벽시계 기준으로 합친다
// (발생기 사이 시계 차이가 그대로 섞인다 — 발생기는 같은 시간 동기화를 쓰는 인스턴스여야 한다).
//
// 평균 도착률의 분모는 진입 시간(--entry-seconds)이다(§2.3 "achieved / 진입 시간"). 벽시계 1초 창의 개수를
// 쓰면 앞뒤의 부분 초가 1초씩 더해져 평균이 체계적으로 낮아진다. --entry-seconds가 없으면 첫 진입부터 마지막
// 진입까지의 ms 간격을 쓴다. --users-n을 주면 목표 대비 부족분(도착률 반올림 등)도 낸다.
//
//   node infra/loadgen/entry-arrivals.mjs --entry-seconds 10 --users-n 25000 entry-g1.json [entry-g2.json ...]
//
// 고정 창(--t0, UTC ISO): 시험 전에 정한 창 [T0, T0 + 진입 시간)에 들어온 도착만 따로 센다. 결과를 본 뒤 가장 잘 나온
// 창을 고르지 않기 위해서다. T0는 분산 발생기에 준 공통 START_AT이다. T0 기준 1초 bucket(0..진입시간−1)과, 목표 도착률
// (users-n / entry-seconds) 대비 ±--rate-tolerance-pct 밖 bucket을 낸다. 발생기별 첫 도착 시각과 그 차이(시작 어긋남)도
// 낸다. 이것은 iteration이 시작된 시각이다 — 연결 대기(entryConnectionWait)만큼 실제 송신보다 이를 수 있고,
// 서버가 받은 시각의 증거(ALB access log)는 따로 남긴다.
import { createReadStream } from "node:fs";
import { createInterface } from "node:readline";
import { parseArgs } from "node:util";

const { values: opts, positionals: files } = parseArgs({
  allowPositionals: true,
  options: {
    "entry-seconds": { type: "string" },
    "users-n": { type: "string" },
    t0: { type: "string" },
    "rate-tolerance-pct": { type: "string" },
  },
});
if (files.length === 0) {
  console.error("사용: entry-arrivals.mjs [--entry-seconds 초] [--users-n 목표 사용자 수] <k6 --out json 파일> [...]");
  process.exit(2);
}

const sums = { entry_arrivals: 0, entry_processed: 0, entry_no_user: 0, dropped_iterations: 0, http_reqs: 0 };
const perSecond = new Map(); // epoch 초 → 그 초에 시작된 진입 수
let firstMs = Infinity;
let malformedLines = 0; // 강제 종료로 잘린 줄
let lastMs = -Infinity;
const t0 = opts.t0 ? Date.parse(opts.t0) : null;
if (opts.t0 && !Number.isFinite(t0)) {
  console.error(`--t0을 읽지 못했다: ${opts.t0}`);
  process.exit(2);
}
if (t0 !== null && !/^[1-9]\d*$/.test(opts["entry-seconds"] ?? "")) {
  console.error("--t0을 쓰려면 --entry-seconds(양의 정수, 창 길이)가 필요하다");
  process.exit(2);
}
const firstByFile = new Map(); // 파일(발생기) → 첫 도착 ms
const arrivalMs = []; // [ms, value] — 고정 창 계산용
// 진입 요청이 보내지기 전 연결 대기(k6 http_req_blocked: 연결 풀 대기 + 새 TCP·TLS 수립). entry_arrivals는 iteration 시작
// 시각이라 이 대기만큼 실제 송신보다 이르다 — 대기가 길면 도착 분포가 목표대로 보여도 서버에는 늦게 닿는다.
// (측정 세션 20261005-1440 10,000/s burst: entry_arrivals 매초 약 1만인데 새 연결 대기 p95 3.7초로 첫 1초 실제 송신 약 2천.)
const blockedMs = [];

for (const f of files) {
  if (!firstByFile.has(f)) firstByFile.set(f, null); // 도착이 0인 발생기도 남긴다
  const rl = createInterface({ input: createReadStream(f), crlfDelay: Infinity });
  for await (const line of rl) {
    if (!line.trim()) continue;
    // 발생기가 강제 종료되면 마지막 줄이 잘려 있을 수 있다. 그 줄은 버리고 센다 — 잘린 줄 하나 때문에
    // 그때까지의 지표를 통째로 잃지 않는다.
    let p;
    try {
      p = JSON.parse(line);
    } catch {
      malformedLines++;
      continue;
    }
    if (p?.type !== "Point") continue;
    if (p.metric === "http_req_blocked" && p.data.tags?.name === "queue_entry") {
      blockedMs.push(p.data.value);
      continue;
    }
    if (!(p.metric in sums)) continue;
    sums[p.metric] += p.data.value;
    if (p.metric === "entry_arrivals") {
      const ms = Date.parse(p.data.time);
      const sec = Math.floor(ms / 1000);
      perSecond.set(sec, (perSecond.get(sec) || 0) + p.data.value);
      firstMs = Math.min(firstMs, ms);
      lastMs = Math.max(lastMs, ms);
      if (firstByFile.get(f) == null || ms < firstByFile.get(f)) firstByFile.set(f, ms);
      if (t0 !== null) arrivalMs.push([ms, p.data.value]);
    }
  }
}

const seconds = [...perSecond.keys()].sort((a, b) => a - b);
const series = seconds.map((s) => ({ second: new Date(s * 1000).toISOString(), arrivals: perSecond.get(s) }));
const peak = series.reduce((m, x) => (x.arrivals > m.arrivals ? x : m), { second: null, arrivals: 0 });
const spanSeconds = Number.isFinite(firstMs) ? (lastMs - firstMs) / 1000 : 0;
const entrySeconds = opts["entry-seconds"] ? Number(opts["entry-seconds"]) : null;
const denominator = entrySeconds ?? spanSeconds;
const usersN = opts["users-n"] ? Number(opts["users-n"]) : null;
const offered = sums.entry_arrivals + sums.entry_no_user + sums.dropped_iterations;

// 발생기별 첫 도착과 시작 어긋남(가장 이른 첫 도착 대비).
// 도착이 0인 발생기는 firstArrival: null로 남긴다(시작하지 못한 발생기를 숨기지 않는다).
const firsts = [...firstByFile.entries()].map(([file, ms]) => ({ file, firstArrival: ms == null ? null : new Date(ms).toISOString() }));
const firstVals = [...firstByFile.values()].filter((ms) => ms != null);
const generatorsWithoutArrivals = firsts.filter((g) => g.firstArrival === null).map((g) => g.file);
const startSkewMs = firstVals.length > 1 ? Math.max(...firstVals) - Math.min(...firstVals) : 0;

// 고정 창 [T0, T0 + entrySeconds)
let window = null;
if (t0 !== null) {
  const endMs = t0 + entrySeconds * 1000;
  const buckets = Array.from({ length: entrySeconds }, () => 0);
  let inWindow = 0;
  let before = 0;
  let after = 0;
  for (const [ms, v] of arrivalMs) {
    if (ms < t0) before += v;
    else if (ms >= endMs) after += v;
    else {
      inWindow += v;
      buckets[Math.floor((ms - t0) / 1000)] += v;
    }
  }
  const targetRate = usersN != null ? usersN / entrySeconds : null;
  const tolPct = opts["rate-tolerance-pct"] != null ? Number(opts["rate-tolerance-pct"]) : null;
  const outOfTolerance =
    targetRate != null && tolPct != null
      ? buckets
          .map((n, i) => ({ bucket: i, arrivals: n, deviationPct: ((n - targetRate) / targetRate) * 100 }))
          .filter((b) => Math.abs(b.deviationPct) > tolPct)
      : null;
  window = {
    t0: new Date(t0).toISOString(),
    end: new Date(endMs).toISOString(),
    arrivalsInWindow: inWindow,
    arrivalsBeforeT0: before,
    arrivalsAfterWindow: after,
    targetRatePerSecond: targetRate,
    rateTolerancePct: tolPct,
    perSecondFromT0: buckets,
    bucketsOutOfTolerance: outOfTolerance,
  };
}

// 진입 요청의 연결 대기 요약. over100ms가 크면 도착(iteration 시작)과 실제 송신이 어긋난 run이다.
function connectionWait(values) {
  if (values.length === 0) return null;
  const s = [...values].sort((a, b) => a - b);
  const q = (p) => +s[Math.min(s.length - 1, Math.floor(s.length * p))].toFixed(1);
  return { requests: s.length, over100ms: s.filter((v) => v > 100).length, p50Ms: q(0.5), p95Ms: q(0.95), maxMs: +s.at(-1).toFixed(1) };
}

const result = {
  files,
  malformedLines,
  targetUsers: usersN,
  offeredArrivals: offered,
  // 목표 사용자 수보다 덜 시작된 수(도착률 정수 반올림 등). 음수면 목표보다 더 시작됐다.
  shortfallVsTarget: usersN == null ? null : usersN - offered,
  achievedArrivals: sums.entry_arrivals,
  droppedIterations: sums.dropped_iterations,
  noUser: sums.entry_no_user,
  processed: sums.entry_processed,
  httpReqs: sums.http_reqs,
  entrySeconds,
  firstToLastArrivalSeconds: spanSeconds,
  averageArrivalsPerSecond: denominator ? sums.entry_arrivals / denominator : 0,
  averageDenominator: entrySeconds != null ? "entry-seconds" : "first-to-last-arrival",
  peak1sArrivals: peak,
  generators: firsts,
  generatorStartSkewMs: startSkewMs,
  generatorsWithoutArrivals,
  window,
  entryConnectionWait: connectionWait(blockedMs),
  // 첫·마지막 1초 창은 부분 구간일 수 있다. peak 해석 때 함께 본다.
  perSecond: series,
};
console.log(JSON.stringify(result, null, 2));
