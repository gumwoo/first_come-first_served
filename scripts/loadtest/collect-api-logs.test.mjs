// collect-api-logs.mjs: run 동안 api 파드 로그를 받아 두는 수집기. 가짜 kubectl(fixtures/fake-kubectl.mjs)로
// 파드가 새로 생기고(HPA 확장), 지워지고(축소 — 스트림이 닫힘), 살아 있는데 스트림이 끊기는 경우를 흉내 낸다.
//   node --test scripts/loadtest/collect-api-logs.test.mjs
import { test } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const SCRIPT = fileURLToPath(new URL("./collect-api-logs.mjs", import.meta.url));
const FAKE = fileURLToPath(new URL("./fixtures/fake-kubectl.mjs", import.meta.url));

function start(state, extra = []) {
  const dir = mkdtempSync(join(tmpdir(), "collect-api-logs-"));
  const stateFile = join(dir, "state.json");
  writeFileSync(stateFile, JSON.stringify(state));
  const out = join(dir, "logs");
  const child = spawn(process.execPath, [SCRIPT, "--out", out, "--since", "2026-10-07T00:00:00Z", "--interval", "0.2", ...extra],
    { env: { ...process.env, KUBECTL: FAKE, FAKE_KUBE_STATE: stateFile }, stdio: ["ignore", "ignore", "pipe"] });
  const exited = new Promise((r) => child.on("exit", r));
  return { out, stateFile, exited, setState: (s) => writeFileSync(stateFile, JSON.stringify(s)) };
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

test("새로 생긴 파드도 받고, 지워진 파드는 스트림이 닫힐 때까지 받아 complete(pod-gone)로 남긴다", async () => {
  const c = start({ pods: ["a"], logs: { a: { lines: ["a1", "a2"], mode: "hold" } } }, ["--for", "2.5"]);
  await sleep(600);
  // HPA 확장: 파드 b가 생긴다(로그는 계속 붙어 있음)
  c.setState({ pods: ["a", "b"], logs: { a: { lines: ["a1", "a2"], mode: "hold" }, b: { lines: ["b1", "queue.audit kind=admit x"], mode: "hold" } } });
  await sleep(600);
  // HPA 축소: 파드 b가 지워진다 — 받던 스트림이 닫히고 get pod는 비어 있다
  c.setState({ pods: ["a"], logs: { a: { lines: ["a1", "a2"], mode: "hold" }, b: { lines: ["b1", "queue.audit kind=admit x"], mode: "hold" } } });
  assert.equal(await c.exited, 0);
  const m = JSON.parse(readFileSync(join(c.out, "manifest.json"), "utf8"));
  assert.equal(m.since, "2026-10-07T00:00:00Z");
  assert.equal(m.pods.a.endReason, "collector-stopped");
  assert.equal(m.pods.a.complete, true);
  assert.equal(m.pods.b.endReason, "pod-gone");
  assert.equal(m.pods.b.complete, true);
  assert.equal(m.pods.b.follows, 1); // 지워진 파드는 다시 받지 않는다
  assert.match(readFileSync(join(c.out, "a.log"), "utf8"), /^\[pod\/a\/api\] a1\n\[pod\/a\/api\] a2\n$/);
  assert.match(readFileSync(join(c.out, "b.log"), "utf8"), /\[pod\/b\/api\] queue\.audit kind=admit x/);
});

test("파드가 살아 있는데 스트림이 계속 끊기면 다시 받다가(최대 횟수) stream-error로 complete=false", async () => {
  const c = start({ pods: ["a"], logs: { a: { lines: ["a1"], mode: "close" } } }, ["--for", "1.5", "--max-refollow", "2"]);
  assert.equal(await c.exited, 0);
  const m = JSON.parse(readFileSync(join(c.out, "manifest.json"), "utf8"));
  assert.equal(m.pods.a.endReason, "stream-error");
  assert.equal(m.pods.a.complete, false);
  assert.equal(m.pods.a.follows, 3); // 처음 1 + 다시 2
  assert.equal(readFileSync(join(c.out, "a.log"), "utf8"), "[pod/a/api] a1\n"); // 다시 받을 때 덮어써 줄이 겹치지 않는다
});

test("파드가 지워져 스트림이 닫히면 다시 받지 않고 pod-gone", async () => {
  const c = start({ pods: ["a"], logs: { a: { lines: ["a1"], mode: "hold" } } }, ["--for", "1.5"]);
  await sleep(400);
  c.setState({ pods: [], logs: { a: { lines: ["a1"], mode: "hold" } } });
  assert.equal(await c.exited, 0);
  const m = JSON.parse(readFileSync(join(c.out, "manifest.json"), "utf8"));
  assert.equal(m.pods.a.endReason, "pod-gone");
  assert.equal(m.pods.a.complete, true);
  assert.equal(m.pods.a.follows, 1);
});

test("--since가 UTC ISO(초)가 아니면 시작하지 않는다", async () => {
  const child = spawn(process.execPath, [SCRIPT, "--out", tmpdir(), "--since", "2026-10-07 00:00"], { stdio: "ignore" });
  assert.equal(await new Promise((r) => child.on("exit", r)), 2);
});
