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

test("--gens를 쓰면서 --gen을 빠뜨리면 거부한다(발생기들이 같은 사용자를 중복으로 쓰지 않게)", () => {
  const r = plan("--users-n", "100", "--gens", "3");
  assert.equal(r.code, 2);
  assert.match(r.stderr, /--gen gK를 반드시/);
});

test("발생기 수가 전체 사용자보다 많으면 모든 발생기에서 거부한다", () => {
  for (const g of ["g1", "g2", "g3"]) assert.equal(plan("--users-n", "2", "--gen", g, "--gens", "3").code, 2);
});

test("--start-at이 달력상 없는 시각이면 거부한다", () => {
  assert.equal(plan("--users-n", "100", "--start-at", "2026-02-30T00:00:00Z").code, 2);
  assert.equal(plan("--users-n", "100", "--start-at", "2026-13-01T00:00:00Z").code, 2);
});

// 실행 경로(--print-plan 없음)의 시작 대기 검사는 디렉터리를 만들기 전에 끝난다.
const runNoPlan = (startAt) => spawnSync("bash", [RUN_ENTRY, "--session", "s-test", "--run", `r-${Date.now()}`,
  "--base", "b", "--event", "1", "--users", users, "--users-n", "10", "--start-at", startAt], { encoding: "utf8" });
const isoSec = (ms) => new Date(ms).toISOString().replace(/\.\d+Z$/, "Z");

test("--start-at이 이미 지났으면 실행 전에 거부한다", () => {
  const r = runNoPlan(isoSec(Date.now() - 5000));
  assert.equal(r.status, 2);
  assert.match(r.stderr, /이미 .*초 지났다/);
});

test("--start-at이 SETUP_TIMEOUT − 30초보다 멀면 실행 전에 거부한다", () => {
  const r = runNoPlan(isoSec(Date.now() + 2 * 3600 * 1000));
  assert.equal(r.status, 2);
  assert.match(r.stderr, /SETUP_TIMEOUT/);
});

test("T0 정각에 찍힌 도착은 창 안이다(왼쪽 경계 포함)", () => {
  const { out } = arrivals([[point(at(0)), point(at(-1))]], "--entry-seconds", "1", "--t0", "2026-10-07T01:00:00Z");
  assert.equal(out.window.arrivalsInWindow, 1);
  assert.equal(out.window.arrivalsBeforeT0, 1);
});

test("허용 범위 경계값(정확히 ±X%)은 범위 안이다", () => {
  // 목표 4/s, 허용 ±25% → 3건(−25%)은 안, 2건(−50%)은 밖
  const g = [0, 1, 2].map((i) => point(at(i * 100))).concat([1000, 1100].map((ms) => point(at(ms))));
  const { out } = arrivals([g], "--entry-seconds", "2", "--users-n", "8", "--t0", "2026-10-07T01:00:00Z", "--rate-tolerance-pct", "25");
  assert.deepEqual(out.window.perSecondFromT0, [3, 2]);
  assert.deepEqual(out.window.bucketsOutOfTolerance.map((b) => b.bucket), [1]);
});

test("도착이 0인 발생기도 결과에 남는다(시작 못 한 발생기를 숨기지 않는다)", () => {
  const { out } = arrivals([[point(at(0))], [point(at(0), "http_reqs")]], "--entry-seconds", "1");
  assert.equal(out.generators.length, 2);
  assert.equal(out.generators.filter((g) => g.firstArrival === null).length, 1);
  assert.equal(out.generatorsWithoutArrivals.length, 1);
});

test("--t0과 함께 쓰는 --entry-seconds는 양의 정수여야 한다", () => {
  assert.equal(arrivals([[point(at(0))]], "--t0", "2026-10-07T01:00:00Z", "--entry-seconds", "2.5").code, 2);
  assert.equal(arrivals([[point(at(0))]], "--t0", "2026-10-07T01:00:00Z", "--entry-seconds", "abc").code, 2);
});

test("--warm-seconds는 --start-at과 함께만 받고 계획에 남는다", () => {
  const p = plan("--users-n", "1000", "--start-at", "2026-10-07T01:00:00Z", "--warm-seconds", "30").plan;
  assert.equal(p.warmSeconds, 30);
  assert.equal(plan("--users-n", "1000").plan.warmSeconds, 0);
  assert.equal(plan("--users-n", "1000", "--start-at", "2026-10-07T01:00:00Z", "--warm-seconds", "1.5").code, 2);
  assert.equal(plan("--users-n", "1000", "--warm-seconds", "30").code, 2); // --start-at 없이 거부
});

// k6 http_req_blocked 점(name 태그로 진입·연결 미리 맺기 요청을 가른다)
const blocked = (ms, name = "queue_entry") =>
  JSON.stringify({ type: "Point", metric: "http_req_blocked", data: { time: at(0), value: ms, tags: { name } } });

test("진입 요청의 연결 대기만 따로 요약한다(연결 미리 맺기 요청은 뺀다)", () => {
  const { out } = arrivals([[point(at(0)), blocked(0), blocked(0.2), blocked(3700), blocked(9000, "warm_connect")]], "--entry-seconds", "1");
  assert.equal(out.entryConnectionWait.requests, 3);
  assert.equal(out.entryConnectionWait.over100ms, 1);
  assert.equal(out.entryConnectionWait.maxMs, 3700);
});

test("연결 대기 점이 없으면 null(옛 출력과 구별)", () => {
  const { out } = arrivals([[point(at(0))]], "--entry-seconds", "1");
  assert.equal(out.entryConnectionWait, null);
});

test("--rate는 양의 정수·constant에서만 받고 계획에 남는다", () => {
  assert.equal(plan("--users-n", "100000", "--gen", "g1", "--gens", "3", "--rate", "3350").plan.arrivalRate, 3350);
  assert.equal(plan("--users-n", "1000").plan.arrivalRate, null);
  assert.equal(plan("--users-n", "1000", "--rate", "0").code, 2);
  assert.equal(plan("--users-n", "1000", "--rate", "33.5").code, 2);
  assert.equal(plan("--users-n", "1000", "--rate", "100", "--dist", "frontloaded").code, 2);
  assert.equal(plan("--users-n", "1000", "--rate", "00").code, 2); // 정규화 뒤 0
  // rate × 진입 시간 < 몫이면 거부(너무 낮은 rate)
  assert.equal(plan("--users-n", "1000", "--entry-seconds", "10", "--rate", "99").code, 2);
  assert.equal(plan("--users-n", "1000", "--entry-seconds", "10", "--rate", "100").plan.arrivalRate, 100);
});
