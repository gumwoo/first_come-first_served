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

const { values: a, positionals: files } = parseArgs({
  allowPositionals: true,
  options: { "tolerance-ms": { type: "string", default: "1000" } },
});
if (files.length === 0) {
  console.error("사용: queue-order.mjs [--tolerance-ms 1000] <api 로그 파일> [...]");
  process.exit(2);
}
// 예외로 죽으면 "위반(1)"이 아니라 "검사 실패(2)"다. 셸이 종료 코드 1을 위반으로 분류한다(check-correctness.sh).
async function main() {
  const TOL = Number(a["tolerance-ms"]);

  const byEvent = new Map(); // event → [{seq, at, token}]
  for (const f of files) {
    const rl = createInterface({ input: createReadStream(f), crlfDelay: Infinity });
    for await (const line of rl) {
      if (!line.includes("queue.audit kind=admit")) continue;
      const kv = Object.fromEntries([...line.matchAll(/(\w+)=(\S+)/g)].map((m) => [m[1], m[2]]));
      const seq = Number(kv.seq);
      const at = Number(kv.at);
      if (!kv.event || !Number.isFinite(seq) || !Number.isFinite(at)) continue;
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
  console.log(JSON.stringify(result, null, 2));
  // 승격 기록이 한 줄도 없으면 "위반 없음"이 아니라 "판정 불가"다(로그 수집 실패, 감사 로그 미배포).
  process.exitCode = result.admits === 0 ? 2 : total ? 1 : 0;
}

main().catch((e) => {
  console.error(`[queue-order] 검사 실패: ${e.stack || e}`);
  process.exitCode = 2;
});
