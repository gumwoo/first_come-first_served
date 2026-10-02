#!/usr/bin/env node
// 진입 발생기 원시 출력(k6 --out json)에서 계획서 §2.3의 부하 지표를 계산한다.
//
// "10K"라고 쓸 때 그것이 걸어 준 값인지, 실제 시작된 진입인지, 처리된 진입인지, HTTP rps인지를 나눈다.
//   offered arrivals    — 발생기가 시작하려 한 진입 수 = achieved + dropped
//   achieved arrivals   — 실제 시작된 진입(entry_arrivals 시계열의 합)
//   dropped_iterations  — 시작하지 못한 진입. 0이 아니면 그 run은 무효(§3.1)
//   processed           — 토큰 발급 200(entry_processed)
//   peak 1s arrivals    — entry_arrivals를 1초 창으로 묶은 최대값. 서버 지표(15초·1분 해상도)로는 못 낸다
//   http_reqs           — 진입 외 요청까지 포함한 HTTP 요청 수(진입 arrivals와 섞지 않는다)
//
// 분산 실행이면 발생기마다 나온 파일을 모두 넘긴다. 1초 창은 벽시계 기준으로 합친다
// (발생기 사이 시계 차이가 그대로 섞인다 — 발생기는 같은 시간 동기화를 쓰는 인스턴스여야 한다).
//
//   node infra/loadgen/entry-arrivals.mjs entry-g1.json [entry-g2.json ...] > arrivals.json
import { createReadStream } from "node:fs";
import { createInterface } from "node:readline";

const files = process.argv.slice(2);
if (files.length === 0) {
  console.error("사용: entry-arrivals.mjs <k6 --out json 파일> [...]");
  process.exit(2);
}

const sums = { entry_arrivals: 0, entry_processed: 0, entry_no_user: 0, dropped_iterations: 0, http_reqs: 0 };
const perSecond = new Map(); // epoch 초 → 그 초에 시작된 진입 수

for (const f of files) {
  const rl = createInterface({ input: createReadStream(f), crlfDelay: Infinity });
  for await (const line of rl) {
    if (!line.includes('"type":"Point"')) continue;
    const p = JSON.parse(line);
    if (!(p.metric in sums)) continue;
    sums[p.metric] += p.data.value;
    if (p.metric === "entry_arrivals") {
      const sec = Math.floor(Date.parse(p.data.time) / 1000);
      perSecond.set(sec, (perSecond.get(sec) || 0) + p.data.value);
    }
  }
}

const seconds = [...perSecond.keys()].sort((a, b) => a - b);
const series = seconds.map((s) => ({ second: new Date(s * 1000).toISOString(), arrivals: perSecond.get(s) }));
const peak = series.reduce((m, x) => (x.arrivals > m.arrivals ? x : m), { second: null, arrivals: 0 });
// 첫 진입부터 마지막 진입까지의 창. 평균 도착률의 분모다(설정한 ENTRY_SECONDS가 아니라 실제 창).
const windowSeconds = seconds.length ? seconds[seconds.length - 1] - seconds[0] + 1 : 0;

const result = {
  files,
  offeredArrivals: sums.entry_arrivals + sums.dropped_iterations,
  achievedArrivals: sums.entry_arrivals,
  droppedIterations: sums.dropped_iterations,
  noUser: sums.entry_no_user,
  processed: sums.entry_processed,
  httpReqs: sums.http_reqs,
  windowSeconds,
  averageArrivalsPerSecond: windowSeconds ? sums.entry_arrivals / windowSeconds : 0,
  peak1sArrivals: peak,
  // 첫·마지막 1초 창은 부분 구간일 수 있다. peak 해석 때 함께 본다.
  perSecond: series,
};
console.log(JSON.stringify(result, null, 2));
