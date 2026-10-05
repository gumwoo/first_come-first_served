#!/usr/bin/env node
// 실시간 조건 사후 재확인(loadtest-100k-plan §3.3). export-prom.mjs가 남긴 run 구간 데이터(prom/)로 실시간 감시기가
// 보는 조건을 run 전체 + 꼬리 30초에 걸쳐 다시 본다.
//
// 감시기는 구조상 run 끝을 보지 못한다. 마지막 조회가 run 종료보다 앞서고, run 마지막 순간의 게이지는 종료 뒤
// 스크랩에야 Prometheus에 들어오기 때문이다. 그래서 over-admit·카운터 어긋남·초과판매·승격 처리 실패를 내보낸
// 데이터로 다시 판정한다. 해상도는 내보내기 --step(기본 10초)이다. step이 MAX_STEP_SEC(10초)를 넘으면 판정 불가다 —
// step이 수집 주기(15초)에 가깝거나 넘으면 두 점 사이에 낀 샘플(순간적인 over-admit)을 못 본다.
//
// 판정의 전제는 "그 구간을 실제로 관측했다"는 것이다. 항상 있어야 할 지표(초과판매, 승격 실패 카운터)의 샘플 나이
// (prom/sample_age_max.json — 감시기의 신선도 질의와 같은 식)가 [since, until + 30초]의 모든 step에 있고
// --max-sample-age(기본 30초, 최대 60초, 잠정값) 이하여야 한다. 점이 빠졌거나 오래됐으면 그 구간은 관측 공백이라
// 판정 불가다. 또 run 종료 뒤에 항상 있어야 할 모든 시계열이 한 번 이상 스크랩됐어야 한다 — 어떤 점에서든
// (점 시각 − 가장 오래된 샘플 나이) ≥ until이면 그 시점의 모든 시계열 샘플이 run 종료 뒤의 것이다. 이것이 없으면
// run 끝 상태를 관측하지 못한 것이다(샘플 나이 30초 기준만으로는 run 종료 10초 전 샘플로도 통과할 수 있다 — G1 재현).
// 대기열 게이지(admitted·admit_drift)가 구간 내내 하나도 없어도 판정 불가다 — 측정 run에는 활성
// 이벤트가 있어야 하므로, 없다면 지표 미배포·수집 실패다(감시기의 "값이 한 번도 나오지 않은 조건"과 같은 기준).
//
//   node scripts/loadtest/prom-recheck.mjs --prom-dir <run>/prom --since <run 시작 UTC ISO> --until <run 종료 UTC ISO> \
//     [--max-sample-age 30]
//
// 종료 코드: 0 위반 없음(구간 전체를 신선하게 관측했고 조건이 모두 0), 1 위반(over-admit·카운터 어긋남·초과판매),
//            2 판정 불가(관측 공백, 승격 처리 실패, 파일·인자 문제). 위반을 찾았으면 다른 문제가 있어도 1이다.
import { readFileSync } from "node:fs";
import { parseArgs } from "node:util";

const TAIL_MS = 30_000; // run 마지막 상태가 스크랩돼 들어오는 데 필요한 꼬리(스크랩 두 주기)
const MAX_STEP_SEC = 10; // 내보내기 step 상한(수집 주기 15초보다 충분히 짧게, 잠정값)
const MAX_SAMPLE_AGE_CAP = 60; // --max-sample-age 상한. 크게 주면 lookback(5분) 안의 오래된 값을 그대로 받아들인다

function main() {
  const { values: a } = parseArgs({
    options: {
      "prom-dir": { type: "string" },
      since: { type: "string" },
      until: { type: "string" },
      "max-sample-age": { type: "string", default: "30" },
    },
  });
  if (!a["prom-dir"] || !a.since || !a.until) throw new Error("--prom-dir, --since, --until이 필요하다");
  const since = Date.parse(a.since);
  const until = Date.parse(a.until);
  if (!Number.isFinite(since) || !Number.isFinite(until) || !(until > since)) throw new Error(`구간이 잘못됐다: ${a.since} ~ ${a.until}`);
  if (!/^\d+(\.\d+)?$/.test(a["max-sample-age"]) || !(Number(a["max-sample-age"]) > 0) || Number(a["max-sample-age"]) > MAX_SAMPLE_AGE_CAP) {
    throw new Error(`--max-sample-age는 0보다 크고 ${MAX_SAMPLE_AGE_CAP} 이하인 수다`);
  }
  const maxAge = Number(a["max-sample-age"]);
  const winEnd = until + TAIL_MS;

  const dir = a["prom-dir"];
  const meta = JSON.parse(readFileSync(`${dir}/_meta.json`, "utf8"));
  const stepMs = Number(meta.step) * 1000;
  if (!(stepMs > 0)) throw new Error("_meta.json의 step을 읽지 못했다");

  // query_range 결과 → [{metric, points: [[ms, value]]}] (구간 안의 점만)
  const load = (name) => {
    const body = JSON.parse(readFileSync(`${dir}/${name}.json`, "utf8"));
    if (body?.status !== "success") throw new Error(`${name}.json: Prometheus 응답 status=${body?.status}`);
    return (body.data?.result ?? []).map((s) => ({
      metric: s.metric ?? {},
      points: (s.values ?? [])
        // Prometheus는 값을 숫자 문자열로 낸다. 그 밖의 값(""·null 등)은 Number()가 0으로 읽으므로 NaN으로 바꿔 "숫자가 아닌 값"으로 잡는다.
        .map(([t, v]) => [Number(t) * 1000, typeof v === "string" && /^[-+0-9.eE]+$/.test(v) ? Number(v) : NaN])
        .filter(([t]) => t >= since && t <= winEnd),
    }));
  };

  const violations = [];
  const problems = [];
  // 내보내기 조건: step 상한, 구간이 [since, until + 30초]를 덮음(사후 검사 0단계와 같은 확인 — 단독 실행 대비)
  if (stepMs > MAX_STEP_SEC * 1000) problems.push(`내보내기 step ${stepMs / 1000}초가 ${MAX_STEP_SEC}초를 넘는다 — 두 점 사이 샘플을 놓칠 수 있다(--step ${MAX_STEP_SEC} 이하로 다시 내보낸다)`);
  if (!(Number(meta.startSec) * 1000 <= since)) problems.push("내보낸 구간이 run 시작보다 늦게 시작한다(또는 startSec 없음)");
  if (!(Number(meta.endSec) * 1000 >= winEnd)) problems.push("내보낸 구간이 run 종료 + 30초를 덮지 않는다(또는 endSec 없음)");

  // 1) 관측 연속성·신선도: sample_age_max는 시계열 하나(max)다. [since, winEnd]의 모든 step에 점이 있고 나이가 기준 이하.
  const ages = load("sample_age_max");
  const agePts = ages.length === 1 ? ages[0].points : [];
  if (ages.length !== 1) problems.push(`sample_age_max 시계열이 ${ages.length}개다(1개여야 한다)`);
  let ageMax = null;
  const stale = [];
  const holes = [];
  if (agePts.length === 0) problems.push("구간 안에 관측(sample_age_max) 점이 없다");
  else {
    // 첫 점은 since에서 한 step 안, 마지막 점은 winEnd에서 한 step 안이어야 한다. 사이 간격은 step을 넘으면 안 된다.
    if (agePts[0][0] - since > stepMs) holes.push(`시작 ${a.since} ~ ${new Date(agePts[0][0]).toISOString()}`);
    if (winEnd - agePts[agePts.length - 1][0] > stepMs) holes.push(`${new Date(agePts[agePts.length - 1][0]).toISOString()} ~ 끝(run 종료 + 30초)`);
    for (let i = 1; i < agePts.length; i++) {
      if (agePts[i][0] - agePts[i - 1][0] > stepMs) holes.push(`${new Date(agePts[i - 1][0]).toISOString()} ~ ${new Date(agePts[i][0]).toISOString()}`);
    }
    for (const [t, v] of agePts) {
      if (!Number.isFinite(v)) { stale.push(`${new Date(t).toISOString()}=${v}`); continue; }
      if (ageMax === null || v > ageMax) ageMax = v;
      if (v > maxAge) stale.push(`${new Date(t).toISOString()}=${v}s`);
    }
  }
  if (holes.length) problems.push(`관측 점이 빠진 구간: ${holes.slice(0, 5).join(", ")}${holes.length > 5 ? ` 외 ${holes.length - 5}` : ""}`);
  if (stale.length) problems.push(`샘플이 ${maxAge}초보다 오래된 점: ${stale.slice(0, 5).join(", ")}${stale.length > 5 ? ` 외 ${stale.length - 5}` : ""}`);
  // run 끝 상태 관측: 어떤 점에서 (점 시각 − 가장 오래된 샘플 나이) ≥ until이면, 그 시점에 항상 있어야 할 모든 시계열의
  // 최신 샘플이 run 종료 뒤의 것이다. 그런 점이 없으면 run 종료 뒤 스크랩을 하나도 확인하지 못한 것이다.
  const postUntil = agePts.find(([t, v]) => Number.isFinite(v) && t - v * 1000 >= until);
  if (agePts.length && !postUntil) {
    problems.push("run 종료 뒤에 스크랩된 샘플을 확인하지 못했다(모든 항상-있는 시계열 기준) — run 끝 상태를 관측하지 못했다");
  }

  // 값이 숫자가 아니면 판정할 수 없다(수집 실패 NaN 등)
  const maxOf = (series, label) => {
    let m = null;
    for (const s of series) for (const [t, v] of s.points) {
      if (!Number.isFinite(v)) { problems.push(`${label}: 숫자가 아닌 값 ${v} (${new Date(t).toISOString()})`); continue; }
      if (m === null || v > m) m = v;
    }
    return m;
  };

  // 2) 승격 처리 실패(파드 합 rate). 0보다 크면 그 구간의 대기열 게이지가 멈췄을 수 있다 → 판정 불가.
  const tickRate = load("queue_admit_tick_failures_rate");
  const tickFailuresMaxRate = maxOf(tickRate, "queue_admit_tick_failures_rate");
  if (tickRate.every((s) => s.points.length === 0)) problems.push("구간 안에 승격 처리 실패 rate 점이 없다");
  if (tickFailuresMaxRate !== null && tickFailuresMaxRate > 0) problems.push(`승격 처리 실패가 있었다(최대 ${tickFailuresMaxRate}/s) — 대기열 게이지가 멈췄을 수 있다`);

  // 3) 초과판매(항상 있어야 하는 지표 — 점이 없으면 판정 불가)
  const oversold = load("seat_oversold");
  const oversoldMax = maxOf(oversold, "seat_oversold");
  if (oversoldMax === null) problems.push("구간 안에 초과판매 점이 없다");
  else if (oversoldMax > 0) violations.push(`초과판매 ${oversoldMax}`);

  // 4) over-admit: 같은 시각의 이벤트별 admitted − 정원. 대기열 게이지는 활성 이벤트가 없으면 비어 있는 것이 정상이다.
  const capacity = new Map();
  for (const s of load("queue_capacity")) for (const [t, v] of s.points) capacity.set(t, v);
  let overAdmitMax = null;
  const overAdmitAt = [];
  const admitted = load("queue_admitted");
  if (admitted.every((s) => s.points.length === 0)) problems.push("구간 안에 대기열 게이지(admitted)가 하나도 없다 — 활성 이벤트가 없었거나 지표 미배포·수집 실패");
  for (const s of admitted) {
    for (const [t, v] of s.points) {
      const cap = capacity.get(t);
      if (cap === undefined) { problems.push(`over-admit: ${new Date(t).toISOString()}에 admitted는 있는데 정원 값이 없다`); continue; }
      if (!Number.isFinite(v) || !Number.isFinite(cap)) { problems.push(`over-admit: 숫자가 아닌 값 (${new Date(t).toISOString()})`); continue; }
      const d = v - cap;
      if (overAdmitMax === null || d > overAdmitMax) overAdmitMax = d;
      if (d > 0 && overAdmitAt.length < 5) overAdmitAt.push(`event=${s.metric.event} ${new Date(t).toISOString()} +${d}`);
    }
  }
  if (overAdmitMax !== null && overAdmitMax > 0) violations.push(`over-admit 최대 +${overAdmitMax} (${overAdmitAt.join(", ")})`);

  // 5) 카운터 어긋남(|admit_drift|, 이벤트별)
  // 내보내기 질의가 abs()를 걸지만 여기서도 절댓값으로 본다(질의가 바뀌어도 음수 어긋남을 놓치지 않게).
  const drift = load("queue_admit_drift").map((s) => ({ ...s, points: s.points.map(([t, v]) => [t, Math.abs(v)]) }));
  if (drift.every((s) => s.points.length === 0)) problems.push("구간 안에 카운터 어긋남 게이지가 하나도 없다");
  const driftMax = maxOf(drift, "queue_admit_drift");
  if (driftMax !== null && driftMax > 0) violations.push(`카운터 어긋남 최대 ${driftMax}`);

  const result = {
    window: { since: a.since, until: a.until, end: new Date(winEnd).toISOString(), stepSec: stepMs / 1000 },
    observedPoints: agePts.length,
    sampleAgeMaxSec: ageMax,
    // run 종료 뒤 모든 시계열이 스크랩됐음을 처음 확인한 점(없으면 null)
    postUntilObservedAt: postUntil ? new Date(postUntil[0]).toISOString() : null,
    maxSampleAgeSec: maxAge,
    tickFailuresMaxRate,
    oversoldMax,
    overAdmitMax,
    driftMax,
    violations,
    problems,
  };
  console.log(JSON.stringify(result, null, 2));
  process.exitCode = violations.length ? 1 : problems.length ? 2 : 0;
}

try {
  main();
} catch (e) {
  console.error(`[prom-recheck] 확인 실패: ${e.message || e}`);
  process.exitCode = 2;
}
