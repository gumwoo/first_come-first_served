// pod-coverage.mjs의 대상 판정(run 구간과 겹친 파드만)과 재시작 판정(run 구간의 증가만)을 고정한다.
//   node --test scripts/loadtest/pod-coverage.test.mjs
import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const SCRIPT = fileURLToPath(new URL("./pod-coverage.mjs", import.meta.url));
const T0 = Date.parse("2026-10-05T17:01:00Z") / 1000; // run 시작(초)
const RUN_SEC = 90; // run 종료 = T0 + 90
const iso = (sec) => new Date(sec * 1000).toISOString().replace(".000Z", "Z");

// 10초 간격 표본(평가 시각 from..to, 초)
const samples = (from, to, value = "1") => {
  const out = [];
  for (let t = from; t <= to; t += 10) out.push([t, value]);
  return out;
};
const promBody = (result) => JSON.stringify({ status: "success", data: { resultType: "matrix", result } });

// pods: [{ name, from, to, restarts?: [[t, v]...], existing?: { restarts, startedAt(sec) }, firstLine?(sec) }]
function run(pods) {
  const dir = mkdtempSync(join(tmpdir(), "pod-coverage-"));
  const f = (n, body) => { const p = join(dir, n); writeFileSync(p, body); return p; };
  const podsFile = f("api_pods.json", promBody(pods.map((p) => ({ metric: { pod: p.name }, values: samples(p.from, p.to) }))));
  const restartsFile = f("api_restarts.json", promBody(pods.map((p) => ({
    metric: { pod: p.name }, values: p.restarts ?? samples(p.from, p.to, "0"),
  }))));
  const existing = pods.filter((p) => p.existing)
    .map((p) => `${p.name} ${p.existing.restarts} ${iso(p.existing.startedAt)}`).join("\n");
  const firstLines = pods.filter((p) => p.existing).map((p) => `${p.name} ${iso(p.firstLine)}`).join("\n");
  const r = spawnSync(process.execPath, [SCRIPT,
    "--pods", podsFile, "--restarts", restartsFile,
    "--existing", f("existing.txt", existing), "--first-lines", f("first.txt", firstLines),
    "--since", iso(T0), "--until", iso(T0 + RUN_SEC),
    "--meta", f("_meta.json", JSON.stringify({ startSec: T0 - 120 })),
  ], { encoding: "utf8" });
  let out = null;
  try { out = JSON.parse(r.stdout); } catch { /* 확인 실패면 stdout이 비어 있다 */ }
  return { code: r.status, out, stderr: r.stderr };
}

// run 전체에 있었고 지금도 있는 정상 파드
const whole = { name: "api-whole", from: T0 - 120, to: T0 + 150, existing: { restarts: 0, startedAt: T0 - 600 }, firstLine: T0 - 590 };

test("run 시작 전에 사라진 파드는 대상이 아니다(리셋·축소로 지운 파드 오탐 방지)", () => {
  const gone = { name: "api-gone-before", from: T0 - 120, to: T0 - 50 };
  const { code, out } = run([whole, gone]);
  assert.equal(code, 0);
  assert.deepEqual(out.seen, ["api-whole"]);
  assert.deepEqual(out.missing, []);
  assert.equal(out.outsideRun[0].pod, "api-gone-before");
  assert.equal(out.outsideRun[0].reason, "run 시작 전에 사라짐");
});

test("run 종료 뒤에 생긴 파드는 대상이 아니다", () => {
  const late = { name: "api-after", from: T0 + RUN_SEC + 60, to: T0 + 200 };
  const { code, out } = run([whole, late]);
  assert.equal(code, 0);
  assert.deepEqual(out.seen, ["api-whole"]);
  assert.equal(out.outsideRun[0].reason, "run 종료 뒤에 생김");
});

test("run 전체에 있었고 지금도 있으면 정상이다", () => {
  const { code, out } = run([whole]);
  assert.equal(code, 0);
  assert.equal(out.complete, true);
});

test("run 도중 지워진 파드는 대상이고 지금 없으므로 빠짐 — 판정 불가", () => {
  const killed = { name: "api-killed-mid", from: T0 - 120, to: T0 + 40 };
  const { code, out } = run([whole, killed]);
  assert.equal(code, 2);
  assert.deepEqual(out.missing, ["api-killed-mid"]);
  assert.equal(out.complete, false);
});

test("run 도중 새로 뜬 파드는 대상이고, 지금 있고 로그 앞부분이 남아 있으면 정상이다", () => {
  const born = { name: "api-born-mid", from: T0 + 30, to: T0 + 150, existing: { restarts: 0, startedAt: T0 + 25 }, firstLine: T0 + 35 };
  const { code, out } = run([whole, born]);
  assert.equal(code, 0);
  assert.ok(out.seen.includes("api-born-mid"));
});

test("run 도중 새로 뜬 파드라도 로그 앞부분이 회전으로 지워졌으면 판정 불가다", () => {
  const born = { name: "api-born-rotated", from: T0 + 30, to: T0 + 150, existing: { restarts: 0, startedAt: T0 + 25 }, firstLine: T0 + 300 };
  const { code, out } = run([whole, born]);
  assert.equal(code, 2);
  assert.equal(out.rotatedOrUnknown[0].pod, "api-born-rotated");
});

test("run 시작 전에 재시작한 파드는 run 중 재시작으로 세지 않는다", () => {
  const restartedBefore = {
    ...whole, name: "api-restarted-before",
    restarts: [...samples(T0 - 120, T0 - 70, "0"), ...samples(T0 - 60, T0 + 150, "1")],
    existing: { restarts: 1, startedAt: T0 - 60 }, firstLine: T0 - 55,
  };
  const { code, out } = run([restartedBefore]);
  assert.equal(code, 0);
  assert.deepEqual(out.restartedInRun, []);
});

test("run 도중 재시작하면 판정 불가다", () => {
  const restartedMid = {
    ...whole, name: "api-restarted-mid",
    restarts: [...samples(T0 - 120, T0 + 30, "0"), ...samples(T0 + 40, T0 + 150, "1")],
    existing: { restarts: 1, startedAt: T0 + 40 }, firstLine: T0 + 45,
  };
  const { code, out } = run([restartedMid]);
  assert.equal(code, 2);
  assert.deepEqual(out.restartedInRun, ["api-restarted-mid"]);
});

test("--until이 없으면 확인 실패(종료 코드 2)다", () => {
  const r = spawnSync(process.execPath, [SCRIPT, "--pods", "x", "--restarts", "x", "--existing", "x", "--first-lines", "x",
    "--since", iso(T0), "--meta", "x"], { encoding: "utf8" });
  assert.equal(r.status, 2);
  assert.match(r.stderr, /--until/);
});
