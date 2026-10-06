// 분산 발생기 도구를 고정한다: run-entry.sh의 몫 계산(--gens/--gen/--print-plan)과
// entry-arrivals.mjs의 고정 창(--t0)·초별 분포·허용 범위·발생기 시작 어긋남.
//   node --test infra/loadgen/distributed-start.test.mjs
import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const RUN_ENTRY = fileURLToPath(new URL("./run-entry.sh", import.meta.url));
const ARRIVALS = fileURLToPath(new URL("./entry-arrivals.mjs", import.meta.url));
const dir = mkdtempSync(join(tmpdir(), "dist-start-"));
const users = join(dir, "users.json");
writeFileSync(users, "[]");

const plan = (...args) => {
  const r = spawnSync("bash", [RUN_ENTRY, "--session", "s", "--run", "r", "--base", "b", "--event", "1",
    "--users", users, "--print-plan", ...args], { encoding: "utf8" });
  return { code: r.status, plan: r.status === 0 ? JSON.parse(r.stdout) : null, stderr: r.stderr };
};

test("--gens 3이면 전체 사용자를 끊김 없이 나눈다(나머지는 마지막 발생기)", () => {
  const shares = ["g1", "g2", "g3"].map((g) =>
    plan("--users-n", "100000", "--offset", "5", "--gen", g, "--gens", "3", "--start-at", "2026-10-07T01:00:00Z").plan);
  assert.deepEqual(shares.map((p) => p.usersN), [33333, 33333, 33334]);
  assert.deepEqual(shares.map((p) => p.offset), [5, 33338, 66671]);
  assert.equal(shares.reduce((n, p) => n + p.usersN, 0), 100000);
  assert.ok(shares.every((p) => p.startAt === "2026-10-07T01:00:00Z"));
});

test("--gens가 없으면 기존 동작(받은 users-n·offset 그대로)", () => {
  const p = plan("--users-n", "1000", "--offset", "7").plan;
  assert.equal(p.usersN, 1000);
  assert.equal(p.offset, 7);
  assert.equal(p.gens, null);
  assert.equal(p.startAt, null);
});

test("--gen이 --gens 범위 밖이거나 형식이 틀리면 거부한다", () => {
  assert.equal(plan("--users-n", "1000", "--gen", "g4", "--gens", "3").code, 2);
  assert.equal(plan("--users-n", "1000", "--gen", "x1", "--gens", "3").code, 2);
});

test("--start-at은 UTC ISO만 받는다", () => {
  assert.equal(plan("--users-n", "1000", "--start-at", "2026-10-07").code, 2);
  assert.equal(plan("--users-n", "1000", "--start-at", "2026-10-07T01:00:00+09:00").code, 2);
});

// k6 --out json 형식의 entry_arrivals 점
const point = (iso, metric = "entry_arrivals", value = 1) =>
  JSON.stringify({ type: "Point", metric, data: { time: iso, value, tags: {} } });
const T0 = Date.parse("2026-10-07T01:00:00Z");
const at = (ms) => new Date(T0 + ms).toISOString();

function arrivals(files, ...args) {
  const paths = files.map((lines, i) => {
    const p = join(dir, `entry-g${i + 1}-${Math.random().toString(36).slice(2)}.json`);
    writeFileSync(p, lines.join("\n"));
    return p;
  });
  const r = spawnSync(process.execPath, [ARRIVALS, ...args, ...paths], { encoding: "utf8" });
  return { code: r.status, out: r.status === 0 ? JSON.parse(r.stdout) : null, stderr: r.stderr };
}

test("--t0 고정 창: 창 안·앞·뒤를 나눠 세고 T0 기준 1초 bucket을 낸다", () => {
  const g1 = [point(at(-200)), point(at(100)), point(at(1500)), point(at(2999))];
  const g2 = [point(at(50)), point(at(2000)), point(at(3000))];
  const { code, out } = arrivals([g1, g2], "--entry-seconds", "3", "--users-n", "6", "--t0", "2026-10-07T01:00:00Z");
  assert.equal(code, 0);
  assert.equal(out.window.arrivalsBeforeT0, 1);
  assert.equal(out.window.arrivalsAfterWindow, 1); // 정확히 T0+3s는 창 밖([T0, T0+3s))
  assert.equal(out.window.arrivalsInWindow, 5);
  assert.deepEqual(out.window.perSecondFromT0, [2, 1, 2]);
});

test("목표 대비 허용 범위 밖 bucket을 낸다", () => {
  // 목표 2/s, 허용 ±25% → bucket 1(1건, −50%)만 밖
  const g1 = [point(at(10)), point(at(20)), point(at(1100)), point(at(2100)), point(at(2200))];
  const { out } = arrivals([g1], "--entry-seconds", "3", "--users-n", "6", "--t0", "2026-10-07T01:00:00Z",
    "--rate-tolerance-pct", "25");
  assert.equal(out.window.targetRatePerSecond, 2);
  assert.deepEqual(out.window.bucketsOutOfTolerance.map((b) => b.bucket), [1]);
});

test("발생기별 첫 도착과 시작 어긋남(ms)을 낸다", () => {
  const { out } = arrivals([[point(at(0)), point(at(500))], [point(at(800))]], "--entry-seconds", "1");
  assert.equal(out.generators.length, 2);
  assert.equal(out.generatorStartSkewMs, 800);
});

test("--t0에는 --entry-seconds가 필요하다", () => {
  const { code } = arrivals([[point(at(0))]], "--t0", "2026-10-07T01:00:00Z");
  assert.equal(code, 2);
});
