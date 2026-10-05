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
// 시계: 감사 시각은 파드마다의 시계이고, 승격·회수 모두 스크립트가 끝난 뒤 찍힌다. 파드 사이 시계 차이로 겹침이
// 생겨 보이는 오탐을 피하려고 구간을 양끝에서 --tolerance-ms(기본 1000, 잠정값)만큼 줄여서 센다. 그래서 그보다 짧게
// 스친 초과는 놓칠 수 있다(한계). 같은 파드 안의 회수→승격은 한 틱에서 순서대로 찍히므로 시계 차이가 없다.
//
//   node scripts/loadtest/admission-overlap.mjs --capacity 100 --since <ISO> --until <ISO> [--tolerance-ms 1000] api.log
//
// 종료 코드: 0 위반 없음, 1 위반, 2 검사 실패(인자 오류·예외·승격 기록 0건·형식이 깨진 감사 줄).
// 위반을 이미 찾았으면 1이 우선한다.
import { createReadStream } from "node:fs";
import { createInterface } from "node:readline";
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
    if (!/kind=(admit|reclaim|leave)\b/.test(line)) continue;
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
  for (const t of tokens.values()) {
    t.starts.sort((a, b) => a - b);
    t.ends.sort((a, b) => a - b);
    if (t.starts.length > 1) duplicateAdmits += t.starts.length - 1;
    let start;
    if (t.starts.length) start = t.starts[0];
    else { start = since; heldBeforeSince++; }
    const end = t.ends.find((e) => e >= start) ?? (openAtEnd++, winEnd);
    // 양끝을 허용 폭만큼 줄인다(파드 사이 시계 차이로 생기는 거짓 겹침 제거).
    const s = Math.max(start, since) + tolMs;
    const e = Math.min(end, winEnd) - tolMs;
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
  return { capacity, toleranceMs: tolMs, admits, malformed, malformedSamples, duplicateAdmits, openAtEnd, heldBeforeSince, violations, events };
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
  const lines = [];
  for (const f of files) {
    const rl = createInterface({ input: createReadStream(f), crlfDelay: Infinity });
    for await (const line of rl) lines.push(line);
  }
  const result = analyze(lines, { capacity: Number(a.capacity), since, until, tolMs: Number(a["tolerance-ms"]) });
  console.log(JSON.stringify(result, null, 2));
  // 승격 기록이 없거나 읽지 못한 감사 줄이 있으면 "위반 없음"이 아니라 판정 불가다. 위반을 찾았으면 1이 우선한다.
  process.exitCode = result.violations ? 1 : result.admits === 0 || result.malformed > 0 ? 2 : 0;
}

// 테스트에서 analyze만 가져다 쓸 수 있게, 직접 실행할 때만 main을 돈다.
if (/admission-overlap\.mjs$/.test(process.argv[1] ?? "")) {
  main().catch((e) => {
    console.error(`[admission-overlap] 검사 실패: ${e.stack || e}`);
    process.exitCode = 2;
  });
}
