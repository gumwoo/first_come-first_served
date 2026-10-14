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
// 입력(export-prom.mjs가 남긴 파일):
//   prom/outbox_no_receivers_by_pod.json — 파드별 원시 카운터(increase()는 새 파드의 첫 샘플 이전 증가를 놓쳐서 쓰지 않는다)
//   prom/api_restarts.json               — 파드별 컨테이너 재시작 횟수(pod-coverage.mjs와 같은 파일)
// 계산(파드마다): 구간 안의 연속 샘플 차이를 더한다. 값이 줄면 재시작으로 보고 새 값을 더한다. 첫 샘플이 구간 시작 뒤에 있으면
// 그 파드는 구간 안에서 떴다고 보고 첫 값을 더한다(카운터는 기동 때 0으로 등록된다 — OutboxRelay 생성자).
// 재시작: 값이 줄지 않은 재시작(재시작 직후의 0이 스크랩되지 않았고 새 값이 이전 값 이상)은 차이로 구분되지 않아 증가를 놓친다.
// 그래서 구간 안에 재시작 횟수가 늘어난 파드는, 그 파드의 구간 안 값이 모두 0일 때만 0으로 인정하고 아니면 판정 불가로 둔다.
// 구간: [since, until + 30초] — 사후 재확인(prom-recheck)과 같은 꼬리. 마지막 틱이 run 끝 뒤에 스크랩되기 때문이다.
// 한계: 시계열은 있는데 구간 안에 점이 없는 파드(스크랩이 끊긴 파드)는 0으로 센다. 그 공백은 4단계(prom-recheck)의 신선도 검사가
// 대부분 잡을 것으로 본다(추론 — 그 검사는 파드별 최댓값만 봐서 한 파드만 끊긴 경우는 놓칠 수 있다).
//
// 종료 코드: 0 증가 0, 1 증가 있음(위반), 2 판정 불가·인자 오류·실행 오류(파일 없음·형식 오류·시계열 없음 — 카운터는 기동 때
// 등록되므로 시계열이 하나도 없으면 스크랩이 끊긴 것이다 — 재시작 파드의 값 확인 불가). 1일 때만 출력 verdict가 "violation"이다
// (check-correctness.sh가 종료 코드와 함께 대조한다).
import { readFileSync } from "node:fs";
import { parseArgs } from "node:util";
import { fileURLToPath } from "node:url";

const TAIL_SEC = 30;

const pointsOf = (s, end) =>
  (Array.isArray(s?.values) ? s.values : [])
    .map((v) => [Number(v?.[0]), Number(v?.[1])])
    .filter(([t, v]) => Number.isFinite(t) && Number.isFinite(v) && t <= end)
    .sort((x, y) => x[0] - y[0]);

/** 재시작 시계열에서 [sinceSec, end] 안에 재시작 횟수가 늘어난 파드 이름들. */
export function restartedPods(restartSeries, sinceSec, untilSec) {
  const end = untilSec + TAIL_SEC;
  const out = new Set();
  for (const s of restartSeries ?? []) {
    const pts = pointsOf(s, end);
    for (let i = 1; i < pts.length; i++) {
      if (pts[i][0] > sinceSec && pts[i][1] > pts[i - 1][1]) out.add(s.metric?.pod);
    }
  }
  return out;
}

/** series: Prometheus matrix 결과(result 배열). 파드별 증가·재시작으로 판정 불가인 파드와 합계를 돌려준다. */
export function noReceiverIncrease(series, sinceSec, untilSec, restarted = new Set()) {
  const end = untilSec + TAIL_SEC;
  const perPod = [];
  const undetermined = [];
  for (const s of series) {
    const pod = s.metric?.pod ?? "(none)";
    const pts = pointsOf(s, end);
    if (pts.length === 0) continue;
    if (restarted.has(pod)) {
      // 재시작이 낀 파드는 차이만으로 증가를 셀 수 없다. 구간 안 값이 모두 0이면 증가도 0이다(재시작 전후 모두 0).
      const inWindow = pts.filter(([t]) => t > sinceSec);
      if (inWindow.every(([, v]) => v === 0)) perPod.push({ pod, increase: 0, restarted: true });
      else undetermined.push(pod);
      continue;
    }
    let inc = 0;
    // 구간 시작 뒤에 처음 나타난 시계열 = 구간 안에서 뜬 파드. 기동 때 0으로 등록되므로 첫 값 전체가 구간 안의 증가다.
    if (pts[0][0] > sinceSec) inc += pts[0][1];
    for (let i = 1; i < pts.length; i++) {
      if (pts[i][0] <= sinceSec) continue; // 구간 이전의 증가는 세지 않는다(기준값으로만 쓴다)
      const d = pts[i][1] - pts[i - 1][1];
      inc += d >= 0 ? d : pts[i][1]; // 줄었으면 재시작 — 새 값이 재시작 뒤의 증가다
    }
    perPod.push({ pod, increase: inc });
  }
  return { increase: perPod.reduce((n, p) => n + p.increase, 0), perPod, undetermined };
}

function readMatrix(file) {
  const body = JSON.parse(readFileSync(file, "utf8"));
  if (body.status !== "success" || !Array.isArray(body.data?.result)) throw new Error(`질의 결과 형식이 아니다: ${file}`);
  return body.data.result;
}

function main() {
  const { values: a } = parseArgs({
    options: { "prom-dir": { type: "string" }, since: { type: "string" }, until: { type: "string" } },
  });
  const toSec = (s) => Date.parse(s) / 1000;
  if (!a["prom-dir"] || !a.since || !a.until || !Number.isFinite(toSec(a.since)) || !Number.isFinite(toSec(a.until))) {
    console.error("사용법: outbox-no-receivers.mjs --prom-dir <dir> --since <UTC> --until <UTC>");
    return 2;
  }
  const since = toSec(a.since);
  const until = toSec(a.until);
  let series;
  let restarts;
  try {
    series = readMatrix(`${a["prom-dir"]}/outbox_no_receivers_by_pod.json`);
    restarts = readMatrix(`${a["prom-dir"]}/api_restarts.json`);
  } catch (e) {
    console.log(JSON.stringify({ verdict: "undetermined", reason: `입력을 읽지 못했다: ${e.message}` }));
    return 2;
  }
  if (series.length === 0) {
    console.log(JSON.stringify({ verdict: "undetermined", reason: "시계열이 없다(카운터는 기동 때 등록되므로 스크랩이 끊긴 것이다)" }));
    return 2;
  }
  const r = noReceiverIncrease(series, since, until, restartedPods(restarts, since, until));
  const window = { since: a.since, until: a.until, tailSec: TAIL_SEC };
  // 위반이 판정 불가보다 우선한다(다른 정합성 도구와 같은 규칙).
  if (r.increase > 0) {
    console.log(JSON.stringify({ verdict: "violation", ...r, window }, null, 2));
    return 1;
  }
  if (r.undetermined.length > 0) {
    console.log(JSON.stringify({ verdict: "undetermined", reason: "구간 안에 재시작한 파드의 카운터가 0이 아니라 증가를 셀 수 없다", ...r, window }, null, 2));
    return 2;
  }
  console.log(JSON.stringify({ verdict: "ok", ...r, window }, null, 2));
  return 0;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  let code;
  try {
    code = main();
  } catch (e) {
    // 처리 못 한 예외가 node 기본 종료 코드 1(= 위반)로 새지 않게 판정 불가로 끝낸다.
    console.log(JSON.stringify({ verdict: "undetermined", reason: `실행 오류: ${e?.message ?? e}` }));
    code = 2;
  }
  process.exit(code);
}
