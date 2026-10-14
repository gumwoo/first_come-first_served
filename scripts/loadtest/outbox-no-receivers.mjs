#!/usr/bin/env node
// 구독자 0으로 나간 아웃박스 발행 수(run 구간). check-correctness.sh 3단계의 "구독자 부재" 판정.
//
//   node scripts/loadtest/outbox-no-receivers.mjs --prom-dir <run>/prom --since <UTC> --until <UTC>
//
// 무엇을 보나(ADR-028 §6): 릴레이는 Redis 채널(sse:order)로 발행하고, 발행 호출이 성공하면 PUBLISHED로 마킹한다.
// 그때 받은 구독자가 0이면 앱이 flowticket.outbox.publish.no_receivers 카운터를 올린다 — 모든 파드의 구독이 끊긴 순간이라
// 그 알림은 아무 SSE 연결에도 닿지 못했다. 이 카운터가 run 구간에 늘지 않았으면 "발행 시점에 구독자가 있었다"까지 말할 수 있다.
// 브라우저가 실제로 받았는지는 서버에서 판정할 수 없다(파드의 로컬 전달·SSE 전송 실패는 이 값에 안 잡힌다).
//
// 입력: export-prom.mjs가 남긴 prom/outbox_no_receivers_by_pod.json(파드별 원시 카운터 — increase()는 새 파드의 첫 샘플 이전
// 증가를 놓쳐서 쓰지 않는다)과 prom/_meta.json(step).
// 계산(파드마다): 구간 안의 연속 샘플 차이를 더한다. 값이 줄면 재시작으로 보고 새 값을 더한다. 첫 샘플이 구간 시작 뒤에 있으면
// 그 파드는 구간 안에서 떴다고 보고 첫 값을 더한다(카운터는 기동 때 0으로 등록된다 — OutboxRelay 생성자).
// 구간: [since, until + 30초] — 사후 재확인(prom-recheck)과 같은 꼬리. 마지막 틱이 run 끝 뒤에 스크랩되기 때문이다.
//
// 종료 코드: 0 증가 0, 1 증가 있음(위반), 2 판정 불가(파일 없음·형식 오류·시계열 없음 — 카운터는 기동 때 등록되므로
// 시계열이 하나도 없으면 스크랩이 끊긴 것이다).
import { readFileSync } from "node:fs";
import { parseArgs } from "node:util";
import { fileURLToPath } from "node:url";

const TAIL_SEC = 30;

/** series: Prometheus matrix 결과(result 배열). sinceSec·untilSec: 초. 파드별 증가와 합계를 돌려준다. */
export function noReceiverIncrease(series, sinceSec, untilSec) {
  const end = untilSec + TAIL_SEC;
  const perPod = [];
  for (const s of series) {
    const pts = (s.values ?? [])
      .map((v) => [Number(v[0]), Number(v[1])])
      .filter(([t, v]) => Number.isFinite(t) && Number.isFinite(v) && t <= end)
      .sort((x, y) => x[0] - y[0]);
    if (pts.length === 0) continue;
    let inc = 0;
    // 구간 시작 뒤에 처음 나타난 시계열 = 구간 안에서 뜬 파드. 기동 때 0으로 등록되므로 첫 값 전체가 구간 안의 증가다.
    if (pts[0][0] > sinceSec) inc += pts[0][1];
    for (let i = 1; i < pts.length; i++) {
      if (pts[i][0] <= sinceSec) continue; // 구간 이전의 증가는 세지 않는다(기준값으로만 쓴다)
      const d = pts[i][1] - pts[i - 1][1];
      inc += d >= 0 ? d : pts[i][1]; // 줄었으면 재시작 — 새 값이 재시작 뒤의 증가다
    }
    perPod.push({ pod: s.metric?.pod ?? "(none)", increase: inc });
  }
  return { increase: perPod.reduce((n, p) => n + p.increase, 0), perPod };
}

function main() {
  const { values: a } = parseArgs({
    options: { "prom-dir": { type: "string" }, since: { type: "string" }, until: { type: "string" } },
  });
  const toSec = (s) => Date.parse(s) / 1000;
  if (!a["prom-dir"] || !a.since || !a.until || !Number.isFinite(toSec(a.since)) || !Number.isFinite(toSec(a.until))) {
    console.error("사용법: outbox-no-receivers.mjs --prom-dir <dir> --since <UTC> --until <UTC>");
    process.exit(2);
  }
  let series;
  try {
    const body = JSON.parse(readFileSync(`${a["prom-dir"]}/outbox_no_receivers_by_pod.json`, "utf8"));
    if (body.status !== "success" || !Array.isArray(body.data?.result)) throw new Error("질의 결과 형식이 아니다");
    series = body.data.result;
  } catch (e) {
    console.log(JSON.stringify({ verdict: "undetermined", reason: `입력을 읽지 못했다: ${e.message}` }));
    process.exit(2);
  }
  if (series.length === 0) {
    console.log(JSON.stringify({ verdict: "undetermined", reason: "시계열이 없다(카운터는 기동 때 등록되므로 스크랩이 끊긴 것이다)" }));
    process.exit(2);
  }
  const r = noReceiverIncrease(series, toSec(a.since), toSec(a.until));
  const verdict = r.increase > 0 ? "violation" : "ok";
  console.log(JSON.stringify({ verdict, ...r, window: { since: a.since, until: a.until, tailSec: TAIL_SEC } }, null, 2));
  process.exit(r.increase > 0 ? 1 : 0);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) main();
