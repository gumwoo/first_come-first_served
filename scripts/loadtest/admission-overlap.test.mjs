// 실효 입장 초과 사후 재구성(admission-overlap.mjs)의 판정식 테스트. node 내장 테스트라 의존성이 없다.
//   node --test scripts/loadtest/admission-overlap.test.mjs
import assert from "node:assert/strict";
import test from "node:test";
import { analyze } from "./admission-overlap.mjs";

const SINCE = Date.parse("2026-10-05T05:00:00Z");
const UNTIL = Date.parse("2026-10-05T05:10:00Z");
const T = (s) => SINCE + s * 1000; // run 시작 뒤 s초
const admit = (tok, s, ev = 1) =>
  `[pod/a/api] x INFO queue.audit kind=admit event=${ev} token=${tok} seq=1 at=${T(s)} keyAt=${T(s)} admitExpAt=1 admitKeyTtl=300`;
const end = (kind, tok, s, ev = 1) => `[pod/a/api] x INFO queue.audit kind=${kind} event=${ev} token=${tok} at=${T(s)}`;
const run = (lines, capacity = 2, tolMs = 0) => analyze(lines, { capacity, since: SINCE, until: UNTIL, tolMs });

test("정원 안에서 회수 뒤 같은 시각에 재승격하면 위반이 아니다(반열린 구간)", () => {
  const r = run([admit("aa", 0), admit("bb", 0), end("reclaim", "aa", 60), admit("cc", 60)]);
  assert.equal(r.violations, 0);
  assert.equal(r.events["1"].maxConcurrent, 2);
});

test("회수 전에 재승격되면(점유가 겹치면) 위반이다", () => {
  const r = run([admit("aa", 0), admit("bb", 0), admit("cc", 59), end("reclaim", "aa", 60)]);
  assert.equal(r.violations, 1);
  assert.equal(r.events["1"].maxConcurrent, 3);
});

test("허용 폭 이하의 겹침은 시계 차이로 보고 세지 않고, 넘으면 센다", () => {
  const at = (s) => [admit("aa", 0), admit("bb", 0), admit("cc", 60 - s), end("reclaim", "aa", 60)];
  assert.equal(run(at(0.5), 2, 1000).violations, 0); // 0.5초 겹침 ≤ 1초
  assert.equal(run(at(1.0), 2, 1000).violations, 0); // 정확히 허용 폭
  assert.equal(run(at(1.001), 2, 1000).violations, 1); // 허용 폭을 넘음
  assert.equal(run(at(0.5), 2, 0).violations, 1);
});

test("since 이전에 이미 끝난 토큰(승격 기록 없음)은 건너뛴다", () => {
  const r = run([end("reclaim", "ee", -0.5), admit("aa", 0), admit("bb", 0)]);
  assert.equal(r.violations, 0);
  assert.equal(r.heldBeforeSince, 0);
});

test("끝나지 않은 토큰은 구간 끝까지 점유한다", () => {
  const r = run([admit("aa", 0), admit("bb", 0), admit("cc", 300)]);
  assert.equal(r.violations, 1);
  assert.equal(r.openAtEnd, 3);
});

test("since 이전에 승격된 토큰(회수만 보임)은 since부터 점유한 것으로 센다", () => {
  const r = run([end("reclaim", "ff", 30), admit("aa", 0), admit("bb", 0)]);
  assert.equal(r.heldBeforeSince, 1);
  assert.equal(r.violations, 1); // ff가 0~30초 동안 슬롯을 쥐고 있었다
});

test("이탈(leave)도 점유를 끝낸다", () => {
  const r = run([admit("aa", 0), admit("bb", 0), end("leave", "aa", 10), admit("cc", 10)]);
  assert.equal(r.violations, 0);
});

test("이벤트별로 따로 센다", () => {
  const r = run([admit("aa", 0, 1), admit("bb", 0, 1), admit("cc", 0, 2), admit("dd", 0, 2)]);
  assert.equal(r.violations, 0);
});

test("형식이 깨진 감사 줄은 세고, 같은 토큰 중복 승격도 센다", () => {
  const r = run([admit("aa", 0), "[pod/a/api] queue.audit kind=admit event=1 token=bb seq=1 at=17909", admit("aa", 5)]);
  assert.equal(r.malformed, 1);
  assert.equal(r.duplicateAdmits, 1);
});
