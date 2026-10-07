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

// pods: [{ name, from, to, step?, restarts?: [[t, v]...], existing?: { restarts, startedAt(sec) }, firstLine?(sec) }]
// meta: 내보낸 구간(기본: run 시작 −120s ~ 종료 +60s, step 10s — 실제 run-one과 같은 설정)
function run(pods, meta = { startSec: T0 - 120, endSec: T0 + RUN_SEC + 60, step: 10 }) {
  const dir = mkdtempSync(join(tmpdir(), "pod-coverage-"));
  const f = (n, body) => { const p = join(dir, n); writeFileSync(p, body); return p; };
  const podsFile = f("api_pods.json", promBody(pods.map((p) => ({ metric: { pod: p.name }, values: p.values ?? samples(p.from, p.to) }))));
  // (격자 어긋남 시험은 from에 5초를 더해 평가 시각이 run 시작과 맞지 않게 한다)
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
    "--meta", f("_meta.json", JSON.stringify(meta)),
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

test("run 종료 + 여유 뒤에 재시작했으면 지금 컨테이너에 run 로그가 없다 — 판정 불가(거짓 통과 방지)", () => {
  const restartedAfter = {
    ...whole, name: "api-restarted-after-grace",
    restarts: [...samples(T0 - 120, T0 + 140, "0"), ...samples(T0 + 150, T0 + 150, "1")],
    existing: { restarts: 1, startedAt: T0 + 145 }, firstLine: T0 + 146,
  };
  const { code, out } = run([restartedAfter]);
  assert.equal(code, 2);
  assert.deepEqual(out.restartedAfterOrUnknown, ["api-restarted-after-grace"]);
});

test("평가 격자가 run 시작과 어긋나도 run 시작 직후 지워진 파드는 대상(빠짐)이다", () => {
  // 표본이 T0−115, …, T0−5에 찍히고 다음 평가(T0+5) 전에 지워진 파드 — 마지막 표본이 run 시작보다 앞이다.
  const killedJustAfter = { name: "api-killed-just-after", from: T0 - 115, to: T0 - 5 };
  const { code, out } = run([whole, killedJustAfter]);
  assert.equal(code, 2);
  assert.deepEqual(out.missing, ["api-killed-just-after"]);
});

test("run과 겹치지 않은 파드의 재시작은 판정에 영향이 없다", () => {
  const goneRestarted = { name: "api-gone-restarted", from: T0 - 120, to: T0 - 60,
    restarts: [...samples(T0 - 120, T0 - 100, "0"), ...samples(T0 - 90, T0 - 60, "1")] };
  const { code, out } = run([whole, goneRestarted]);
  assert.equal(code, 0);
  assert.deepEqual(out.restartedInRun, []);
});

test("run 종료 직전에 떠서 첫 표본이 종료 + 40초에 찍힌 파드도 대상이다(여유 45초 경계)", () => {
  const lateBorn = { name: "api-late-born", from: T0 + RUN_SEC + 40, to: T0 + 150,
    existing: { restarts: 0, startedAt: T0 + RUN_SEC - 2 }, firstLine: T0 + RUN_SEC };
  const { code, out } = run([whole, lateBorn]);
  assert.equal(code, 0);
  assert.ok(out.seen.includes("api-late-born"));
});

test("내보낸 구간이 run 종료 + 여유를 덮지 않으면 확인 실패다", () => {
  const { code, stderr } = run([whole], { startSec: T0 - 120, endSec: T0 + RUN_SEC + 30, step: 10 });
  assert.equal(code, 2);
  assert.match(stderr, /run 종료 \+ 여유/);
});

test("kube-state-metrics 수집 공백이 run 시작에 걸치면 판정 불가다(공백 때문에 run 직후 삭제가 run 전 삭제로 보이는 거짓 통과 방지)", () => {
  // T0−35 수집 성공 → T0−5·T0+5·T0+15 수집 실패(시계열 공백) → T0+25부터 다시 성공. api-gap-b는 T0+10에 지워졌다.
  const gapValues = (to) => [...samples(T0 - 120, T0 - 40), ...samples(T0 + 30, to)];
  const wholeWithGap = { ...whole, values: gapValues(T0 + 150) };
  const killedInGap = { name: "api-gap-b", from: T0 - 120, to: T0 - 40, values: samples(T0 - 120, T0 - 40) };
  const { code, stderr } = run([wholeWithGap, killedInGap]);
  assert.equal(code, 2);
  assert.match(stderr, /표본이 빈 평가 시각/);
});

// ---- 받아 둔 로그(--collected — collect-api-logs.mjs의 manifest.json) ----
function runCollected(pods, manifest) {
  const dir = mkdtempSync(join(tmpdir(), "pod-coverage-c-"));
  const f = (n, body) => { const p = join(dir, n); writeFileSync(p, body); return p; };
  const podsFile = f("api_pods.json", promBody(pods.map((p) => ({ metric: { pod: p.name }, values: samples(p.from, p.to) }))));
  const restartsFile = f("api_restarts.json", promBody(pods.map((p) => ({ metric: { pod: p.name }, values: p.restarts ?? samples(p.from, p.to, "0") }))));
  const r = spawnSync(process.execPath, [SCRIPT, "--pods", podsFile, "--restarts", restartsFile,
    "--collected", f("manifest.json", JSON.stringify(manifest)),
    "--since", iso(T0), "--until", iso(T0 + RUN_SEC),
    "--meta", f("_meta.json", JSON.stringify({ startSec: T0 - 120, endSec: T0 + RUN_SEC + 60, step: 10 })),
  ], { encoding: "utf8" });
  let out = null;
  try { out = JSON.parse(r.stdout); } catch { /* 확인 실패 */ }
  return { code: r.status, out, stderr: r.stderr };
}
const got = (endReason, complete = true) => ({ firstSeenAt: iso(T0 - 5), follows: 1, endReason, complete });
// 정상 수집기 구간: run 시작 전에 떠서 run 종료 + 여유(45초) 뒤에 멈춤
const win = { startedAt: iso(T0 - 10), endedAt: iso(T0 + RUN_SEC + 60) };

test("받아 둔 로그: run 중 HPA 축소로 지워진 파드도 받았으면(pod-gone) 판정 가능하다 — 지금 없어도 된다", () => {
  const scaledIn = { name: "api-scaled-in", from: T0 - 120, to: T0 + 40 };
  const { code, out } = runCollected([whole, scaledIn],
    { since: iso(T0), ...win, pods: { "api-whole": got("collector-stopped"), "api-scaled-in": got("pod-gone") } });
  assert.equal(code, 0);
  assert.equal(out.mode, "collected");
  assert.deepEqual(out.seen, ["api-whole", "api-scaled-in"]);
  assert.equal(out.complete, true);
});

test("받아 둔 로그: run과 겹친 파드를 받지 못했거나(누락) 스트림이 끊긴 채면(stream-error) 판정 불가", () => {
  const added = { name: "api-added", from: T0 + 20, to: T0 + 150 };
  const r1 = runCollected([whole, added], { since: iso(T0), ...win, pods: { "api-whole": got("collector-stopped") } });
  assert.equal(r1.code, 2);
  assert.deepEqual(r1.out.notCollected, ["api-added"]);
  const r2 = runCollected([whole, added],
    { since: iso(T0), ...win, pods: { "api-whole": got("collector-stopped"), "api-added": got("stream-error", false) } });
  assert.equal(r2.code, 2);
  assert.equal(r2.out.incomplete[0].pod, "api-added");
});

test("받아 둔 로그: 수집을 run 시작보다 늦게 시작했으면 판정 불가", () => {
  const { code, stderr } = runCollected([whole], { since: iso(T0 + 10), ...win, pods: { "api-whole": got("collector-stopped") } });
  assert.equal(code, 2);
  assert.match(stderr, /run 시작보다 늦은 시각부터/);
});

test("받아 둔 로그: run 중 재시작한 파드는 이전 컨테이너 줄이 빠지므로 판정 불가", () => {
  const restarted = { name: "api-restarted", from: T0 - 120, to: T0 + 150, restarts: [...samples(T0 - 120, T0 + 30, "0"), ...samples(T0 + 40, T0 + 150, "1")] };
  const { code, out } = runCollected([restarted], { since: iso(T0), ...win, pods: { "api-restarted": got("collector-stopped") } });
  assert.equal(code, 2);
  assert.deepEqual(out.restartedInRun, ["api-restarted"]);
});

test("받아 둔 로그: 수집기가 run 종료 + 여유 전에 멈췄거나 run 시작 뒤에 떴으면 판정 불가(complete 표시를 믿지 않는다)", () => {
  const pods = { "api-whole": got("collector-stopped") };
  const early = runCollected([whole], { since: iso(T0), startedAt: iso(T0 - 10), endedAt: iso(T0 + 60), pods });
  assert.equal(early.code, 2);
  assert.match(early.stderr, /전에 멈췄다/);
  const late = runCollected([whole], { since: iso(T0), startedAt: iso(T0 + 5), endedAt: iso(T0 + RUN_SEC + 60), pods });
  assert.equal(late.code, 2);
  assert.match(late.stderr, /run 시작 뒤에 떴다/);
  const none = runCollected([whole], { since: iso(T0), pods });
  assert.equal(none.code, 2);
  assert.match(none.stderr, /startedAt·endedAt/);
});

test("받아 둔 로그: 시작하지 못하고 사라진 파드(neverStarted — Pending 중 축소)는 받지 못함이 아니다", () => {
  const pendingGone = { name: "api-pending-gone", from: T0 + 10, to: T0 + 40 };
  const { code, out } = runCollected([whole, pendingGone],
    { since: iso(T0), ...win, pods: { "api-whole": got("collector-stopped") }, neverStarted: ["api-pending-gone"] });
  assert.equal(code, 0);
  assert.deepEqual(out.notCollected, []);
});
