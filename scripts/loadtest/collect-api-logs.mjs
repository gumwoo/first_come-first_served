#!/usr/bin/env node
// api 파드 로그를 run 동안 계속 받아 둔다 — HPA 축소로 지워질 파드의 감사 줄(승격·회수·이탈)을 지워지기 전에 남기기 위해서다.
//
// 사후 검사(check-correctness.sh)는 원래 run이 끝난 뒤 `kubectl logs`로 지금 살아 있는 파드만 읽는다. 반응형 확장·축소를
// 보는 run(Platform 축)에서는 HPA가 파드를 지우므로 그 파드의 줄이 빠져 대기열 순서·실효 입장 초과가 판정 불가가 된다
// (측정 세션 20261005-1440 — HPA 10 → 9로 10번째 파드의 감사 로그가 사라짐). 그래서 run 동안 파드 목록을 주기적으로 보고,
// 새 파드마다 `kubectl logs -f --prefix --since-time=<run 시작>`을 붙여 파드별 파일로 받는다. 파드가 지워지면 컨테이너가
// 끝나며 스트림이 닫히므로 마지막 줄까지 남는다. 스트림이 파드가 살아 있는데 끊기면 처음(--since-time)부터 다시 받아 덮어쓴다.
//
//   node scripts/loadtest/collect-api-logs.mjs --out <run>/api-logs --since <run 시작 UTC ISO> [--interval 5] [--for 초]
//   (SIGINT·SIGTERM 또는 --for가 지나면 멈추고 manifest.json을 쓴다)
//
// 출력: <out>/<파드>.log(`[pod/<파드>/api] <줄>` — kubectl logs --prefix와 같은 형식), <out>/manifest.json:
//   { since, startedAt, endedAt, pods: { <파드>: { firstSeenAt, follows, endReason, complete } } }
//   endReason: "pod-gone"(파드가 지워지며 스트림이 닫힘) | "collector-stopped"(수집기를 멈출 때 아직 받는 중) | "stream-error"(다시 받지 못함)
//   complete: 첫 줄부터 마지막 줄까지 받았다고 볼 수 있는가(pod-gone·collector-stopped면 true).
// 자격증명은 쓰지 않는다(kubectl 컨텍스트). KUBECTL 환경변수로 kubectl 대신 다른 실행 파일을 줄 수 있다(테스트용 — .mjs면 node로 실행).
import { spawn, spawnSync } from "node:child_process";
import { createWriteStream, mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { parseArgs } from "node:util";

const { values: a } = parseArgs({
  options: {
    out: { type: "string" },
    since: { type: "string" },
    interval: { type: "string", default: "5" },
    for: { type: "string" },
    namespace: { type: "string", default: "flowticket" },
    selector: { type: "string", default: "app=flowticket-api" },
    container: { type: "string", default: "api" },
    "max-refollow": { type: "string", default: "3" },
  },
});
if (!a.out || !a.since || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(a.since)) {
  console.error("사용: collect-api-logs.mjs --out <디렉터리> --since <YYYY-MM-DDTHH:MM:SSZ> [--interval 5] [--for 초]");
  process.exit(2);
}
const INTERVAL_MS = Number(a.interval) * 1000;
const MAX_REFOLLOW = Number(a["max-refollow"]);
const KUBECTL = process.env.KUBECTL || "kubectl";
const kube = (args) => (KUBECTL.endsWith(".mjs") ? [process.execPath, [KUBECTL, ...args]] : [KUBECTL, args]);
mkdirSync(a.out, { recursive: true });

const startedAt = new Date().toISOString();
const pods = new Map(); // name → { firstSeenAt, follows, endReason, complete, child }
let stopping = false;

function listPods() {
  const [cmd, args] = kube(["-n", a.namespace, "get", "pods", "-l", a.selector, "-o", "jsonpath={range .items[*]}{.metadata.name}{\"\\n\"}{end}"]);
  const r = spawnSync(cmd, args, { encoding: "utf8" });
  if (r.status !== 0) return null;
  return r.stdout.split(/\r?\n/).map((s) => s.trim()).filter(Boolean);
}

/** 파드가 아직 있고 지워지는 중이 아닌가. 조회 실패는 "모름"(null). */
function podAlive(name) {
  const [cmd, args] = kube(["-n", a.namespace, "get", "pod", name, "--ignore-not-found", "-o", "jsonpath={.metadata.name} {.metadata.deletionTimestamp}"]);
  const r = spawnSync(cmd, args, { encoding: "utf8" });
  if (r.status !== 0) return null;
  const [n, del] = r.stdout.trim().split(/\s+/);
  return Boolean(n) && !del;
}

function follow(name) {
  const p = pods.get(name);
  p.follows++;
  // 다시 받을 때는 처음부터(--since-time) 받아 덮어쓴다 — 끊긴 지점부터 이어 붙이면 경계 줄이 겹치거나 빠진다.
  const file = createWriteStream(join(a.out, `${name}.log`), { flags: "w" });
  const [cmd, args] = kube(["-n", a.namespace, "logs", "-f", name, "-c", a.container, "--prefix", `--since-time=${a.since}`]);
  const child = spawn(cmd, args, { stdio: ["ignore", "pipe", "ignore"] });
  p.child = child;
  child.stdout.pipe(file);
  child.on("close", () => {
    file.end();
    p.child = null;
    if (stopping) return; // 아래 stop()이 collector-stopped로 적는다
    const alive = podAlive(name);
    if (alive === false) { p.endReason = "pod-gone"; p.complete = true; return; }
    if (p.follows <= MAX_REFOLLOW) { follow(name); return; }
    p.endReason = "stream-error"; p.complete = false;
  });
}

function tick() {
  if (stopping) return;
  const names = listPods();
  if (names === null) return; // 조회 실패 — 다음 주기에 다시
  for (const n of names) {
    if (pods.has(n)) continue;
    pods.set(n, { firstSeenAt: new Date().toISOString(), follows: 0, endReason: null, complete: false, child: null });
    follow(n);
  }
}

function stop() {
  if (stopping) return;
  stopping = true;
  clearInterval(timer);
  const waits = [];
  for (const p of pods.values()) {
    if (p.child) {
      p.endReason = "collector-stopped"; p.complete = true;
      waits.push(new Promise((r) => p.child.once("close", r)));
      p.child.kill("SIGTERM");
    }
  }
  Promise.all(waits).then(() => {
    const out = { since: a.since, startedAt, endedAt: new Date().toISOString(), pods: {} };
    for (const [n, p] of pods) out.pods[n] = { firstSeenAt: p.firstSeenAt, follows: p.follows, endReason: p.endReason, complete: p.complete };
    writeFileSync(join(a.out, "manifest.json"), JSON.stringify(out, null, 1));
    const bad = Object.values(out.pods).filter((p) => !p.complete).length;
    console.error(`[collect-api-logs] 파드 ${pods.size}개, 다 받지 못한 파드 ${bad}`);
    process.exit(0);
  });
}

process.on("SIGINT", stop);
process.on("SIGTERM", stop);
const timer = setInterval(tick, INTERVAL_MS);
tick();
if (a.for) setTimeout(stop, Number(a.for) * 1000);
