// 입장 인지 지연(admit-latency.mjs): 감사 줄 파싱과 ref로 맞춘 지연 요약.
//   node --test scripts/loadtest/admit-latency.test.mjs
import { test } from "node:test";
import assert from "node:assert/strict";
import { parseAdmits, summarize } from "./admit-latency.mjs";

const line = (ref, at) =>
  `2026-10-07T01:00:00.000Z INFO flowticket.queue.audit - queue.audit kind=admit event=7 token=${ref} seq=12 at=${at} keyAt=${at + 3} admitExpAt=1791300300 admitKeyTtl=300`;

test("승격 줄에서 ref와 at(승격 시각)만 읽는다 — keyAt·admitExpAt은 at이 아니다", () => {
  const m = parseAdmits([line("aaaaaaaaaaaaaaaa", 1000), "queue.audit kind=reclaim event=7 token=bbbbbbbbbbbbbbbb at=5", "무관한 줄"]);
  assert.deepEqual([...m], [["aaaaaaaaaaaaaaaa", 1000]]);
});

test("같은 ref가 두 번이면 첫 승격 시각을 쓴다", () => {
  const m = parseAdmits([line("aaaaaaaaaaaaaaaa", 2000), line("aaaaaaaaaaaaaaaa", 1000)]);
  assert.equal(m.get("aaaaaaaaaaaaaaaa"), 1000);
});

test("지연 = 클라이언트가 본 시각 − 승격 시각, 음수와 한쪽에만 있는 것은 따로 센다", () => {
  const admits = new Map([["a", 1000], ["b", 2000], ["c", 3000]]);
  const seen = new Map([["a", 3000], ["b", 1990], ["z", 5000]]);
  const s = summarize(admits, seen);
  assert.equal(s.matched, 2);
  assert.equal(s.negative, 1); // b: 시계 차이
  assert.equal(s.seenWithoutAdmit, 1); // z
  assert.equal(s.admitWithoutSeen, 1); // c: 승격됐지만 클라이언트가 못 봄(hold 끝 등)
  assert.deepEqual([s.latencyMs.min, s.latencyMs.max], [-10, 2000]);
});
