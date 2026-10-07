#!/usr/bin/env node
// 실효 입장 초과 사후 재구성(loadtest-100k-plan §3.3). api 로그의 감사 줄(queue.audit)로 토큰별 슬롯 점유 구간을
// 다시 만들고, 어느 시각에든 동시에 점유한 토큰 수가 정원을 넘었는지 본다.
//
// 판정식: 이벤트마다, 시각 t에 점유 중인 토큰 수 N(t) = #{토큰 : 시작 ≤ t < 끝} 이 정원보다 크면 위반이다.
//   - 시작: 승격(kind=admit)의 at — 승격 스크립트가 반환된 시각(admitExp에 등록돼 게이트를 통과하기 시작)
//   - 끝: 같은 토큰의 첫 회수(kind=reclaim) 또는 이탈(kind=leave)의 at — admitExp에서 빠진 시각. 입장 게이트는
//         admitExp에 없는 토큰을 통과시키지 않으므로(QueueService.isAdmitted) 이 시각에 유효 입장도 끝난다.
//   - run 안에서 끝나지 않은 토큰은 구간 끝(--until + 30초)까지 점유한 것으로 본다.
//   - 회수·이탈은 있는데 승격이 구간 앞(--since 이전)이라 로그에 없는 토큰은 --since부터 점유한 것으로 본다
//     (빼면 그 슬롯을 놓쳐 동시 점유 수를 적게 센다).
// 게이트가 admitExp 원소만 받으므로 실효 입장자 ⊆ 점유 중인 토큰이다. 따라서 N(t) ≤ 정원이면 실효 입장 초과도 없다.
// 실시간 over-admit(admitExp 원소 수 > 정원)과 같은 성질을 다른 출처(토큰별 감사 기록)로 다시 세는 것이고, 스크랩
// 사이(15초)에 생겼다 사라진 초과도 여기서는 보인다.
//
// 시계: 감사 시각은 파드마다의 시계이고, 승격·회수 모두 스크립트가 끝난 뒤 찍힌다. 파드 사이 시계 차이가 최대
// --tolerance-ms(기본 1000, 잠정값)라고 보고, 구간을 양끝에서 그 절반씩 줄여서 센다 — 두 파드의 구간이 시계 차이만큼
// 겹쳐 보여도 세지 않는다. 그래서 허용 폭 이하로 겹친 초과, 그리고 점유가 허용 폭 이하인 토큰은 놓친다(한계 — 실시간
// 감시는 15초 간격의 스크랩만 보므로 이 짧은 초과를 메우지 못한다). 같은 파드 안의 회수→승격은 한 틱에서 순서대로 찍힌다.
// 회수·이탈 줄은 admitExp에서 실제로 뺀 경우에만 찍히므로 실제로는 언제나 그 토큰의 승격 뒤다. 그런데 이탈을 처리한 파드의
// 시계가 늦으면 승격보다 앞선 시각으로 찍힐 수 있다. 그래서 승격보다 허용 폭 이내로 앞선 끝도 그 승격의 끝으로 본다(빼면
// 구간이 run 끝까지 열린 채 남아 거짓 위반이 된다). 허용 폭보다 앞선 끝만 있으면 시계 전제가 깨진 것이라 그 토큰은 세지
// 않고 판정 불가로 둔다.
//
// 전제: 승격·회수·이탈이 모두 감사 줄로 남았을 것. 승격 루프 도중 예외(admit 키 쓰기 실패)가 나면 나머지 승격은
// 감사 줄 없이 남는다. 회수 루프 도중 예외가 나면 나머지 회수 줄이 빠져 거짓 위반이 된다. 두 예외 모두 승격 처리 실패
// 카운터를 올리므로, check-correctness.sh가 그런 run의 위반(1)을 판정 불가(2)로 낮추고 4단계(prom-recheck.mjs)도 그 run을
// 판정 불가로 만든다 — 이 도구 단독의 0·1은 그 단계와 함께 읽어야 한다.
// 형식이 깨진 감사 줄이 회수·이탈 줄이었으면 그 토큰이 run 끝까지 점유로 세어져 거짓 위반이 된다. 그래서 형식이 깨진 줄이
// 있으면 위반을 찾았어도 1이 아니라 판정 불가(2)로 끝낸다(진짜 위반이어도 0이 되지는 않는다).
//
//   node scripts/loadtest/admission-overlap.mjs --capacity 100 --since <ISO> --until <ISO> [--tolerance-ms 1000] api.log
//
// 종료 코드: 0 위반 없음, 1 위반, 2 검사 실패(인자 오류·예외·승격 기록 0건·형식이 깨진 감사 줄·같은 토큰의 중복 승격·
// 허용 폭보다 앞선 회수·이탈). 위반을 이미 찾았으면 1이 우선한다 — 단, 형식이 깨진 감사 줄이 있으면 위반도 2다(위).
// 2로 끝날 때는 사유를 stderr에 한 줄씩 남긴다.
import { createReadStream, realpathSync } from "node:fs";
import { createInterface } from "node:readline";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";

const TAIL_MS = 30_000;
const MAX_TOL = 60_000;
const USAGE =
  "사용: admission-overlap.mjs --capacity <정원> --since <ISO> --until <ISO> [--tolerance-ms 0~60000(기본 1000)] <api 로그> [...]";

// 감사 줄 형식(QueueAudit). 메시지가 줄 끝이다(Spring Boot 기본 콘솔 패턴).
const ADMIT = /queue\.audit kind=admit event=(\d+) token=([0-9a-f]+) seq=\d+(?:\.\d+)? at=(\d+) keyAt=\d+ admitExpAt=\d+ admitKeyTtl=\d+\s*$/;
const END = /queue\.audit kind=(reclaim|leave) event=(\d+) token=([0-9a-f]+) at=(\d+)\s*$/;

export function analyze(lines, { capacity, since, until, tolMs }) {
  const winEnd = until + TAIL_MS;
  const tokens = new Map(); // key event:token → {event, start:[], end:[]}
  let malformed = 0;
  const malformedSamples = [];
  let admits = 0;
  for (const line of lines) {
    if (!line.includes("queue.audit kind=")) continue;
    // 감사 줄의 종류는 admit·reclaim·leave뿐이다. 다른 종류(잘린 kind=adm 등)도 형식이 깨진 줄로 센다.
    let m;
    if ((m = ADMIT.exec(line))) {
      const k = `${m[1]}:${m[2]}`;
      if (!tokens.has(k)) tokens.set(k, { event: m[1], token: m[2], starts: [], ends: [] });
      tokens.get(k).starts.push(Number(m[3]));
      admits++;
    } else if ((m = END.exec(line))) {
      const k = `${m[2]}:${m[3]}`;
      if (!tokens.has(k)) tokens.set(k, { event: m[2], token: m[3], starts: [], ends: [] });
      tokens.get(k).ends.push(Number(m[4]));
    } else {
      // 조용히 버리면 그 토큰의 점유를 놓친다. 세어서 검사 실패로 끝낸다.
      malformed++;
      if (malformedSamples.length < 5) malformedSamples.push(line.slice(0, 300));
    }
  }

  // 토큰별 점유 구간. 한 토큰은 한 번 승격된다(재진입은 새 토큰). 두 번 이상이면 이상 징후로 따로 센다.
  const byEvent = new Map();
  let duplicateAdmits = 0;
  let openAtEnd = 0;
  let heldBeforeSince = 0;
  let endsBeforeAdmit = 0;
  for (const t of tokens.values()) {
    t.starts.sort((a, b) => a - b);
    t.ends.sort((a, b) => a - b);
    if (t.starts.length > 1) duplicateAdmits += t.starts.length - 1;
    let start;
    if (t.starts.length) start = t.starts[0];
    else {
      // 승격이 구간 앞이라 로그에 없다. 회수·이탈이 since 이후면 since부터 점유한 것으로 보고, since 이전에 끝났으면
      // run 구간과 관계없는 토큰이라 건너뛴다.
      if (!t.ends.some((e) => e >= since)) continue;
      start = since;
      heldBeforeSince++;
    }
    // 승격 기록이 있으면 허용 폭 이내로 앞선 끝(시계 차이)도 이 승격의 끝이다. 위 머리말의 "시계" 참고.
    let end = t.ends.find((e) => e >= (t.starts.length ? start - tolMs : start));
    if (end === undefined && t.ends.length && t.starts.length) { endsBeforeAdmit++; continue; }
    if (end === undefined) { openAtEnd++; end = winEnd; }
    // 양끝을 허용 폭의 절반씩 줄인다 — 두 파드의 구간이 시계 차이(≤ 허용 폭)만큼 겹쳐 보이는 것을 지운다.
    const s = Math.max(start, since) + tolMs / 2;
    const e = Math.min(end, winEnd) - tolMs / 2;
    if (e <= s) continue;
    if (!byEvent.has(t.event)) byEvent.set(t.event, []);
    byEvent.get(t.event).push([s, e]);
  }

  const events = {};
  let violations = 0;
  for (const [event, iv] of byEvent) {
    // 경계 쓸기. 같은 시각이면 끝을 먼저 처리한다(반열린 구간 [s, e)).
    const pts = [];
    for (const [s, e] of iv) { pts.push([s, 1]); pts.push([e, -1]); }
    pts.sort((a, b) => a[0] - b[0] || a[1] - b[1]);
    let cur = 0, max = 0, maxAt = null, over = 0, overSince = null;
    const samples = [];
    for (const [t, d] of pts) {
      const before = cur;
      cur += d;
      if (cur > max) { max = cur; maxAt = t; }
      if (before <= capacity && cur > capacity) { over++; overSince = t; }
      if (before > capacity && cur <= capacity && samples.length < 10) {
        samples.push({ from: new Date(overSince).toISOString(), to: new Date(t).toISOString() });
      }
    }
    events[event] = { tokens: iv.length, maxConcurrent: max, maxAt: maxAt === null ? null : new Date(maxAt).toISOString(), overCapacityIntervals: over, samples };
    violations += over;
  }
  return { capacity, toleranceMs: tolMs, admits, malformed, malformedSamples, duplicateAdmits, openAtEnd, heldBeforeSince, endsBeforeAdmit, violations, events };
}

async function main() {
  let a, files;
  try {
    ({ values: a, positionals: files } = parseArgs({
      allowPositionals: true,
      options: {
        capacity: { type: "string" },
        since: { type: "string" },
        until: { type: "string" },
        "tolerance-ms": { type: "string", default: "1000" },
      },
    }));
  } catch (e) {
    console.error(`${e.message}\n${USAGE}`);
    process.exitCode = 2;
    return;
  }
  const since = Date.parse(a.since ?? ""), until = Date.parse(a.until ?? "");
  if (!files.length || !/^[1-9]\d*$/.test(a.capacity ?? "") || !Number.isFinite(since) || !Number.isFinite(until) ||
      !(until > since) || !/^\d+$/.test(a["tolerance-ms"]) || Number(a["tolerance-ms"]) > MAX_TOL) {
    console.error(USAGE);
    process.exitCode = 2;
    return;
  }
  // 100K run의 api 로그는 크다. 감사 줄만 남기며 읽는다.
  const lines = [];
  for (const f of files) {
    const rl = createInterface({ input: createReadStream(f), crlfDelay: Infinity });
    for await (const line of rl) if (line.includes("queue.audit kind=")) lines.push(line);
  }
  const result = analyze(lines, { capacity: Number(a.capacity), since, until, tolMs: Number(a["tolerance-ms"]) });
  console.log(JSON.stringify(result, null, 2));
  // 승격 기록이 없거나, 읽지 못한 감사 줄이 있거나, 같은 토큰이 두 번 승격됐거나(첫 구간만 세므로 두 번째 점유가 빠진다),
  // 허용 폭보다 앞선 회수·이탈만 있는 토큰이 있으면(시계 전제가 깨져 그 토큰을 세지 않았다) "위반 없음"이 아니라 판정
  // 불가다. 위반을 찾았으면 1이 우선한다 — 단, 읽지 못한 감사 줄이 회수·이탈 줄이었다면 위반 자체가 거짓일 수 있어 2다.
  const reasons = [];
  if (result.admits === 0) reasons.push("승격 기록이 0건이다");
  if (result.malformed > 0) reasons.push(`형식이 깨진 감사 줄 ${result.malformed}개(회수·이탈 줄이었으면 거짓 위반·거짓 0 모두 가능)`);
  if (result.duplicateAdmits > 0) reasons.push(`같은 토큰의 중복 승격 ${result.duplicateAdmits}건(두 번째 점유를 세지 못한다)`);
  if (result.endsBeforeAdmit > 0) reasons.push(`승격보다 허용 폭(${result.toleranceMs}ms)보다 앞선 회수·이탈만 있는 토큰 ${result.endsBeforeAdmit}개(시계 전제가 깨져 세지 않았다)`);
  process.exitCode = result.violations && result.malformed === 0 ? 1 : reasons.length || result.violations ? 2 : 0;
  if (process.exitCode === 2) for (const r of reasons) console.error(r);
}

// 테스트에서 analyze만 가져다 쓸 수 있게, 직접 실행할 때만 main을 돈다.
// 파일 이름이 아니라 실제 경로로 비교한다. 다른 이름으로 복사해 실행해도, 심볼릭 링크·junction 경로로 실행해도
// (Node는 메인 모듈의 import.meta.url을 실제 경로로 잡지만 argv[1]은 링크 경로 그대로다) 조용히 0으로 끝나지 않게.
const isMain = () => {
  try {
    return !!process.argv[1] && realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url));
  } catch {
    return false;
  }
};
if (isMain()) {
  main().catch((e) => {
    console.error(`[admission-overlap] 검사 실패: ${e.stack || e}`);
    process.exitCode = 2;
  });
}
