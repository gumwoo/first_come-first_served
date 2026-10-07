// collect-api-logs.mjs: run 동안 api 파드 로그를 받아 두는 수집기. 가짜 kubectl(fixtures/fake-kubectl.mjs)로
// 시작 전 파드(Pending), 확장(새 파드), 축소(파드 삭제), 살아 있는데 끊긴 스트림, 컨테이너 교체(재시작), 종료 중 컨테이너를 흉내 낸다.
//   node --test scripts/loadtest/collect-api-logs.test.mjs
import { test } from "node:test";
import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { mergeLine, tsKey } from "./collect-api-logs.mjs";

const SCRIPT = fileURLToPath(new URL("./collect-api-logs.mjs", import.meta.url));
const FAKE = fileURLToPath(new URL("./fixtures/fake-kubectl.mjs", import.meta.url));
const SINCE = "2026-10-07T00:00:00Z";
const T = (s, ns = "000000000") => `2026-10-07T00:00:${String(s).padStart(2, "0")}.${ns}Z`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function start(state, extra = []) {
  const dir = mkdtempSync(join(tmpdir(), "collect-api-logs-"));
  const stateFile = join(dir, "state.json");
  writeFileSync(stateFile, JSON.stringify(state));
  const out = join(dir, "logs");
  const child = spawn(process.execPath, [SCRIPT, "--out", out, "--since", SINCE, "--interval", "0.2", "--retry-ms", "100", ...extra],
    { env: { ...process.env, KUBECTL: FAKE, FAKE_KUBE_STATE: stateFile }, stdio: ["ignore", "ignore", "pipe"] });
  const exited = new Promise((r) => child.on("exit", r));
  // 임시 파일 + rename — 가짜 kubectl이 쓰는 도중의 빈 파일을 읽지 않게(CI에서 실제로 흔들렸다)
  // Windows는 읽는 중인 파일로 rename하면 EPERM — 잠깐 쉬었다 다시 한다(Linux에서는 한 번에 된다).
  const set = (fn) => {
    const s = JSON.parse(readFileSync(stateFile, "utf8")); fn(s); writeFileSync(stateFile + ".t", JSON.stringify(s));
    for (let i = 0; ; i++) {
      try { renameSync(stateFile + ".t", stateFile); return; } catch (e) { if (e.code !== "EPERM" || i >= 50) throw e; Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 10); }
    }
  };
  const manifest = () => JSON.parse(readFileSync(join(out, "manifest.json"), "utf8"));
  const log = (pod) => readFileSync(join(out, `${pod}.log`), "utf8");
  return { out, exited, set, manifest, log };
}
const running = (cid = "c1") => ({ containerID: cid, state: "running" });

test("시작 전(Pending) 파드는 컨테이너가 뜰 때까지 기다렸다 받는다 — 확장 run에서 stream-error로 끝나지 않는다", async () => {
  const c = start({ pods: { a: { containerID: null, state: "waiting" } }, logs: { a: { lines: [[T(1), "a1"]], mode: "hold" } } }, ["--for", "3"]);
  await sleep(800);
  c.set((s) => { s.pods.a = running(); });
  assert.equal(await c.exited, 0);
  const m = c.manifest();
  assert.equal(m.pods.a.endReason, "collector-stopped");
  assert.equal(m.pods.a.complete, true);
  assert.equal(m.pods.a.failures, 0); // 시작 전 파드에 붙었다가 실패한 시도가 없다(따라잡기까지 follows 2)
  assert.match(c.log("a"), /a1/);
});

test("축소로 지워진 파드는 스트림이 닫힐 때까지 받고 pod-gone(complete)", async () => {
  const c = start({ pods: { a: running(), b: running("cb") },
    logs: { a: { lines: [[T(1), "a1"]], mode: "hold" }, b: { lines: [[T(1), "b1"], [T(2), "queue.audit kind=admit x"]], mode: "hold" } } }, ["--for", "3"]);
  await sleep(800);
  c.set((s) => { s.logs.b.lines.push([T(3), "queue.audit kind=leave y"]); });
  await sleep(500);
  c.set((s) => { delete s.pods.b; });
  assert.equal(await c.exited, 0);
  const m = c.manifest();
  assert.equal(m.pods.b.endReason, "pod-gone");
  assert.equal(m.pods.b.complete, true);
  assert.equal(m.pods.b.lines, 3);
  assert.match(c.log("b"), /kind=leave y/);
});

test("살아 있는데 스트림이 끊기면 지우지 않고 이어 받는다 — 같은 줄을 두 번 쓰지 않는다", async () => {
  // close: 줄을 내고 바로 끊김. 다시 받을 때 마지막 초부터 다시 오는 줄은 건너뛴다.
  const c = start({ pods: { a: running() }, logs: { a: { lines: [[T(1, "1"), "a1"], [T(1, "2"), "a2"]], mode: "close" } } }, ["--for", "3", "--max-failures", "50"]);
  await sleep(600);
  c.set((s) => { s.logs.a.lines.push([T(1, "3"), "a3"], [T(2), "a4"]); });
  await sleep(600);
  c.set((s) => { s.logs.a.mode = "hold"; });
  assert.equal(await c.exited, 0);
  const lines = c.log("a").trim().split("\n");
  assert.deepEqual(lines.map((l) => l.split(" ").pop()), ["a1", "a2", "a3", "a4"]);
  assert.ok(c.manifest().pods.a.follows > 1);
  assert.equal(c.manifest().pods.a.complete, true);
});

test("지워진 직후 get pod가 한 번 실패하면 받아 둔 줄은 지우지 않되, 그 사이를 확인하지 못했으므로 complete=false(보수적)", async () => {
  const c = start({ pods: { a: running() }, logs: { a: { lines: [[T(1), "queue.audit kind=admit a"], [T(2), "queue.audit kind=leave a"]], mode: "hold" } } }, ["--for", "3"]);
  await sleep(600);
  c.set((s) => { delete s.pods.a; s.getPodFailOnce = ["a"]; });
  assert.equal(await c.exited, 0);
  const m = c.manifest();
  assert.equal(m.pods.a.endReason, "pod-gone");
  assert.equal(m.pods.a.complete, false);
  assert.equal(c.log("a").trim().split("\n").length, 2);
});

test("컨테이너가 바뀌면(재시작) 이전 컨테이너의 남은 줄을 알 수 없어 container-restarted·complete=false", async () => {
  const c = start({ pods: { a: running("c1") }, logs: { a: { lines: [[T(1), "a1"]], mode: "hold" } } }, ["--for", "2.5"]);
  await sleep(600);
  c.set((s) => { s.pods.a = running("c2"); });
  assert.equal(await c.exited, 0);
  assert.equal(c.manifest().pods.a.endReason, "container-restarted");
  assert.equal(c.manifest().pods.a.complete, false);
});

test("종료 중 컨테이너는 마지막으로 한 번 더 받아 남은 줄을 붙이고 container-terminated", async () => {
  const c = start({ pods: { a: running() }, logs: { a: { lines: [[T(1), "a1"]], mode: "hold" } } }, ["--for", "2.5"]);
  await sleep(600);
  c.set((s) => { s.pods.a.state = "terminated"; s.logs.a.lines.push([T(2), "shutdown leave"]); });
  assert.equal(await c.exited, 0);
  const m = c.manifest();
  assert.equal(m.pods.a.endReason, "container-terminated");
  assert.equal(m.pods.a.complete, true);
  assert.match(c.log("a"), /shutdown leave/);
});

test("계속 실패하면(연속 횟수 초과) stream-error·complete=false, 받는 중이 아닐 때 멈추면 complete=false", async () => {
  const c = start({ pods: { a: running() }, logs: { a: { lines: [], mode: "fail" } } }, ["--for", "1.5", "--max-failures", "3"]);
  assert.equal(await c.exited, 0);
  assert.equal(c.manifest().pods.a.endReason, "stream-error");
  assert.equal(c.manifest().pods.a.complete, false);
});

test("out 디렉터리가 비어 있지 않거나 인자가 잘못되면 시작하지 않는다", () => {
  const dir = mkdtempSync(join(tmpdir(), "collect-api-logs-x-"));
  writeFileSync(join(dir, "manifest.json"), "{}");
  const r1 = spawnSync(process.execPath, [SCRIPT, "--out", dir, "--since", SINCE], { encoding: "utf8" });
  assert.equal(r1.status, 2);
  const empty = join(mkdtempSync(join(tmpdir(), "collect-api-logs-y-")), "o");
  mkdirSync(empty);
  assert.equal(spawnSync(process.execPath, [SCRIPT, "--out", empty, "--since", "2026-10-07 00:00"]).status, 2);
  assert.equal(spawnSync(process.execPath, [SCRIPT, "--out", empty, "--since", SINCE, "--interval", "abc"]).status, 2);
  assert.equal(spawnSync(process.execPath, [SCRIPT, "--out", empty, "--since", SINCE, "--for", "x"]).status, 2);
});

test("merge: --since 이전 줄은 빼고 kubectl logs --prefix 형식으로 낸다, 형식 밖 줄은 undefined", () => {
  const s = Date.parse(SINCE);
  assert.equal(mergeLine("[pod/a/api] 2026-10-06T23:59:59.999999999Z before", s), null);
  assert.equal(mergeLine(`[pod/a/api] ${T(0, "000000001")} at since`, s), "[pod/a/api] at since");
  assert.equal(mergeLine(`[pod/a/api] ${T(5)} after x y`, s), "[pod/a/api] after x y");
  assert.equal(mergeLine("깨진 줄", s), undefined);
  assert.ok(tsKey(T(1, "000000002")) < tsKey(T(1, "000000010"))); // kubelet 나노 부분(9자리)은 문자열로 비교해도 순서가 맞다
  assert.ok(tsKey(T(1, "999999999")) < tsKey(T(2, "000000000")));
});

test("끊긴 뒤(정상 종료) 다시 붙지 못한 채 지워지면 그 사이 줄을 모르므로 complete=false(G1 재현 — 오래 남는 정상 종료 표시)", async () => {
  const c = start({ pods: { a: running() }, logs: { a: { lines: [[T(1), "queue.audit kind=admit a"]], mode: "close" } } }, ["--for", "3", "--max-failures", "50"]);
  await sleep(500);
  c.set((s) => { s.logs.a.mode = "fail"; s.logs.a.lines.push([T(2), "queue.audit kind=leave a"]); }); // 다시 받기가 실패하는 동안 줄이 찍힘
  await sleep(500);
  c.set((s) => { delete s.pods.a; });
  assert.equal(await c.exited, 0);
  const m = c.manifest();
  assert.equal(m.pods.a.endReason, "pod-gone");
  assert.equal(m.pods.a.complete, false);
});

test("멈출 때 다시 받기가 무응답이었으면 마지막 따라잡기 실패 → complete=false(G1 재현 — 받는 중으로 보이기만 한 경우)", async () => {
  const c = start({ pods: { a: running() }, logs: { a: { lines: [[T(1), "a1"]], mode: "close" } } }, ["--for", "2", "--max-failures", "50"]);
  await sleep(400);
  c.set((s) => { s.logs.a.mode = "hang"; s.logs.a.lines.push([T(2), "a2 run 중 줄"]); });
  assert.equal(await c.exited, 0);
  const m = c.manifest();
  assert.equal(m.pods.a.endReason, "collector-stopped");
  assert.equal(m.pods.a.complete, false);
});

test("줄 중간에서 끊긴 조각은 쓰지 않고, 다시 받을 때 온전한 줄 하나만 남는다(G1 재현 — 잘린 줄 중복)", async () => {
  const c = start({ pods: { a: running() }, logs: { a: { lines: [[T(1), "a1"], [T(2), "queue.audit kind=admit seq=7 admitKeyTtl=300"]], mode: "partial" } } }, ["--for", "2.5", "--max-failures", "50"]);
  await sleep(500);
  c.set((s) => { s.logs.a.mode = "hold"; });
  assert.equal(await c.exited, 0);
  const lines = c.log("a").trim().split("\n");
  assert.equal(lines.filter((l) => l.includes("seq=7")).length, 1);
  assert.match(lines.find((l) => l.includes("seq=7")), /admitKeyTtl=300$/);
  assert.equal(c.manifest().pods.a.complete, true);
});

test("시작하지 못하고(컨테이너 없이) 사라진 파드는 neverStarted로 남긴다", async () => {
  const c = start({ pods: { a: running(), p: { containerID: null, state: "waiting" } }, logs: { a: { lines: [[T(1), "a1"]], mode: "hold" } } }, ["--for", "2"]);
  await sleep(600);
  c.set((s) => { delete s.pods.p; });
  assert.equal(await c.exited, 0);
  const m = c.manifest();
  assert.deepEqual(m.neverStarted, ["p"]);
  assert.equal(m.pods.p, undefined);
});

test("Pending으로 본 뒤 목록 조회가 오래 실패한 끝에 사라진 파드는 neverStarted가 아니라 unknown(G1 재현 — 그 사이 시작했을 수 있다)", async () => {
  const c = start({ pods: { a: running(), p: { containerID: null, state: "waiting" } }, logs: { a: { lines: [[T(1), "a1"]], mode: "hold" } } }, ["--for", "4"]);
  await sleep(500);
  c.set((s) => { s.getPodsFail = true; });
  await sleep(1800); // 2 × interval(0.2초) + 1초보다 길게
  c.set((s) => { delete s.pods.p; s.getPodsFail = false; });
  assert.equal(await c.exited, 0);
  const m = c.manifest();
  assert.deepEqual(m.neverStarted, []);
  assert.deepEqual(m.unknown, ["p"]);
});

test("끊긴 뒤 다시 받기 전에 로그가 회전돼 앞부분이 잘리면(마지막으로 받은 줄이 다시 오지 않음) complete=false(G1 재현 — 회전 구멍)", async () => {
  const c = start({ pods: { a: running() }, logs: { a: { lines: [[T(1), "a1"]], mode: "close" } } }, ["--for", "3", "--max-failures", "50"]);
  await sleep(400);
  // 다시 받기 전에: T2 leave 줄이 찍히고, 회전으로 현재 파일은 T3부터만 남는다
  c.set((s) => { s.logs.a.lines.push([T(2), "queue.audit kind=leave a"], [T(3), "a3"]); s.logs.a.rotatedFrom = T(3); s.logs.a.mode = "hold"; });
  assert.equal(await c.exited, 0);
  assert.equal(c.manifest().pods.a.complete, false);
  assert.ok(!c.log("a").includes("kind=leave"));
});

test("첫 받기가 실패하는 사이 회전으로 앞부분이 잘리면 complete=false — run 시작 뒤 뜬 파드(G1 재현 rotateWithin120)", async () => {
  const M = (mmss) => `2026-10-07T00:${mmss}.000000000Z`;
  const c = start({ pods: { b: { containerID: "cb", state: "running", startedAt: "2026-10-07T00:00:01Z" } },
    logs: { b: { lines: [[M("00:02"), "b start"], [M("00:40"), "queue.audit kind=admit B"], [M("01:01"), "b after rotation"]], mode: "fail" } } }, ["--for", "3", "--max-failures", "50"]);
  await sleep(700);
  c.set((s) => { s.logs.b.mode = "hold"; s.logs.b.rotatedFrom = M("01:00"); });
  assert.equal(await c.exited, 0);
  assert.equal(c.manifest().pods.b.complete, false);
});

test("run 시작 전부터 있던 파드도 첫 받기가 실패하는 사이 회전되면 complete=false(G1 재현 existingLate)", async () => {
  const c = start({ pods: { a: { containerID: "ca", state: "running", startedAt: "2026-10-06T00:00:00Z" } },
    logs: { a: { lines: [[T(5), "queue.audit kind=admit A"], [T(31), "a after rotation"]], mode: "fail" } } }, ["--for", "3", "--max-failures", "50"]);
  await sleep(700);
  c.set((s) => { s.logs.a.mode = "hold"; s.logs.a.rotatedFrom = T(30); });
  assert.equal(await c.exited, 0);
  assert.equal(c.manifest().pods.a.complete, false);
});

test("run 시작 전부터 있던 파드의 첫 받기가 실패하는 사이 run 시작 직후 회전되면 complete=false(G1 재현 existingEarlyRotate — 여유 0)", async () => {
  const c = start({ pods: { a: { containerID: "ca", state: "running", startedAt: "2026-10-06T00:00:00Z" } },
    logs: { a: { lines: [[T(2), "queue.audit kind=admit A"], [T(6), "a after rotation"]], mode: "fail" } } }, ["--for", "3", "--max-failures", "50"]);
  await sleep(700);
  c.set((s) => { s.logs.a.mode = "hold"; s.logs.a.rotatedFrom = T(5); });
  assert.equal(await c.exited, 0);
  assert.equal(c.manifest().pods.a.complete, false);
});

test("시작 시각을 모르는 파드도 첫 받기 실패 중 회전되면 complete=false(G1 재현 firstStreamRotate)", async () => {
  const c = start({ pods: { a: running() }, logs: { a: { lines: [[T(1), "admit"], [T(5), "after rotation"]], mode: "fail" } } }, ["--for", "3", "--max-failures", "50"]);
  await sleep(700);
  c.set((s) => { s.logs.a.mode = "hold"; s.logs.a.rotatedFrom = T(4); });
  assert.equal(await c.exited, 0);
  assert.equal(c.manifest().pods.a.complete, false);
});

test("첫 시도가 제때 붙은 파드는 첫 줄이 늦어도(한가한 파드) 회전으로 보지 않는다", async () => {
  // 수집기를 since 직후에 띄운 것처럼: 실제 시각을 since로 준다
  const now = new Date(Math.floor(Date.now() / 1000) * 1000);
  const since = now.toISOString().replace(".000Z", "Z");
  const later = new Date(now.getTime() + 60_000).toISOString().replace(".000Z", ".000000000Z");
  const dir = mkdtempSync(join(tmpdir(), "collect-api-logs-idle-"));
  const stateFile = join(dir, "state.json");
  writeFileSync(stateFile, JSON.stringify({ pods: { a: { containerID: "ca", state: "running" } }, logs: { a: { lines: [[later, "idle first line"]], mode: "hold" } } }));
  const out = join(dir, "logs");
  const child = spawn(process.execPath, [SCRIPT, "--out", out, "--since", since, "--interval", "0.2", "--for", "2"],
    { env: { ...process.env, KUBECTL: FAKE, FAKE_KUBE_STATE: stateFile }, stdio: "ignore" });
  assert.equal(await new Promise((r) => child.on("exit", r)), 0);
  assert.equal(JSON.parse(readFileSync(join(out, "manifest.json"), "utf8")).pods.a.complete, true);
});

test("run 시작 뒤에 뜬 파드라도 첫 줄이 기동 줄(시작 직후)이면 회전으로 보지 않는다", async () => {  const c = start({ pods: { b: { containerID: "cb", state: "running", startedAt: "2026-10-07T00:00:01Z" } },
    logs: { b: { lines: [[T(2), "b start"], [T(5), "queue.audit kind=admit B"]], mode: "hold" } } }, ["--for", "2"]);
  assert.equal(await c.exited, 0);
  assert.equal(c.manifest().pods.b.complete, true);
});
