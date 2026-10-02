#!/usr/bin/env node
// 대기열 순서 위반 사후 대조(loadtest-100k-plan §3.3). api 로그의 승격 감사 줄(queue.audit kind=admit)을 읽는다.
//
// 승격은 Redis Lua의 ZPOPMIN이라 진입 순번(seq)이 작은 것부터 원자적으로 나간다. 그래서 정상이라면 seq가 작은
// 토큰은 큰 토큰보다 늦게 승격되지 않는다. 다만 감사 시각(at)은 Lua가 끝난 뒤 각 파드의 시계로 찍히므로,
// 파드 사이 시계 차이와 처리 지연만큼은 순서가 뒤집혀 보일 수 있다. 그 허용 폭(--tolerance-ms)을 넘어서
// 뒤집힌 경우만 위반으로 센다.
//
//   kubectl -n flowticket logs -l app=flowticket-api --since-time=<run 시작> --tail=-1 --prefix > api.log
//   node scripts/loadtest/queue-order.mjs --tolerance-ms 1000 api.log
import { createReadStream } from "node:fs";
import { createInterface } from "node:readline";
import { parseArgs } from "node:util";

// 종료 코드: 0 위반 없음, 1 위반, 2 검사 실패(인자 오류·예외·승격 기록 0건·형식이 깨진 승격 줄). 셸이 1을 위반으로 분류하므로
// (check-correctness.sh) 인자 오류나 예외가 1로 끝나면 안 된다.
const MAX_TOL = 60_000;
const USAGE = `사용: queue-order.mjs [--tolerance-ms 0~${MAX_TOL} 정수(기본 1000)] <api 로그 파일> [...]`;
let a;
let files;
try {
  ({ values: a, positionals: files } = parseArgs({
    allowPositionals: true,
    options: { "tolerance-ms": { type: "string", default: "1000" } },
  }));
} catch (e) {
  console.error(`${e.message}\n${USAGE}`);
}
// 허용 폭이 숫자가 아니면(NaN) 모든 비교가 거짓이 돼 순서 검사가 통째로 꺼진다. 너무 크면 사실상 꺼진다.
// 0~60초의 정수만 받는다.
if (!a || files.length === 0 || !/^\d+$/.test(a["tolerance-ms"]) || Number(a["tolerance-ms"]) > MAX_TOL) {
  if (a) console.error(USAGE);
  process.exitCode = 2;
} else {
  main().catch((e) => {
    console.error(`[queue-order] 검사 실패: ${e.stack || e}`);
    process.exitCode = 2;
  });
}

async function main() {
  const TOL = Number(a["tolerance-ms"]);

  const byEvent = new Map(); // event → [{seq, at, token}]
  let malformed = 0; // 승격 감사 줄인데 event·seq·at을 읽지 못한 줄(잘린 줄 등)
  const malformedSamples = [];
  for (const f of files) {
    const rl = createInterface({ input: createReadStream(f), crlfDelay: Infinity });
    for await (const line of rl) {
      if (!line.includes("queue.audit kind=admit")) continue;
      const kv = Object.fromEntries([...line.matchAll(/(\w+)=(\S+)/g)].map((m) => [m[1], m[2]]));
      const seq = Number(kv.seq);
      const at = Number(kv.at);
      if (!kv.event || !/^\d+(\.\d+)?$/.test(kv.seq ?? "") || !/^\d+$/.test(kv.at ?? "")) {
        // 조용히 버리면 그 줄의 역전을 놓친 채 0이 나온다. 세어서 검사 실패로 끝낸다.
        malformed++;
        if (malformedSamples.length < 5) malformedSamples.push(line.slice(0, 300));
        continue;
      }
      if (!byEvent.has(kv.event)) byEvent.set(kv.event, []);
      byEvent.get(kv.event).push({ seq, at, token: kv.token });
    }
  }

  const result = { toleranceMs: TOL, events: {} };
  let total = 0;
  for (const [event, xs] of byEvent) {
    xs.sort((p, q) => p.seq - q.seq);
    // seq 순서로 지나가며, 앞(작은 seq)에서 본 가장 늦은 승격 시각보다 허용 폭 넘게 이른 승격이 있으면 위반이다.
    let maxAt = -Infinity;
    let maxSeq = null;
    const samples = [];
    let inversions = 0;
    for (const x of xs) {
      if (x.at + TOL < maxAt) {
        inversions++;
        // 순번이 큰 토큰(x)이 순번이 작은 토큰보다 허용 폭 넘게 먼저 승격됐다.
        if (samples.length < 10) samples.push({ higherSeq: x.seq, higherAt: x.at, lowerSeq: maxSeq, lowerAt: maxAt });
      }
      if (x.at > maxAt) {
        maxAt = x.at;
        maxSeq = x.seq;
      }
    }
    // 같은 seq가 두 번 승격됐다면 그것도 위반이다(한 토큰이 두 번 빠져나감).
    const dupSeq = xs.length - new Set(xs.map((x) => x.seq)).size;
    total += inversions + dupSeq;
    result.events[event] = { admits: xs.length, inversions, duplicateSeq: dupSeq, samples };
  }
  result.violations = total;
  result.admits = [...byEvent.values()].reduce((n, xs) => n + xs.length, 0);
  result.malformed = malformed;
  result.malformedSamples = malformedSamples;
  console.log(JSON.stringify(result, null, 2));
  // 승격 기록이 한 줄도 없거나 읽지 못한 승격 줄이 있으면 "위반 없음"이 아니라 "판정 불가"다
  // (로그 수집 실패, 감사 로그 미배포, 잘린 줄). 위반을 이미 찾았으면 1이 우선한다.
  process.exitCode = total ? 1 : result.admits === 0 || malformed > 0 ? 2 : 0;
}
