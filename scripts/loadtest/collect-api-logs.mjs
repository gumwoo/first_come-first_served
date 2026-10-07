#!/usr/bin/env node
// api 파드 로그를 run 동안 계속 받아 둔다 — HPA 축소로 지워질 파드의 감사 줄(승격·회수·이탈)을 지워지기 전에 남기기 위해서다.
//
// 사후 검사(check-correctness.sh)는 원래 run이 끝난 뒤 `kubectl logs`로 지금 살아 있는 파드만 읽는다. 반응형 확장·축소를
// 보는 run(Platform 축)에서는 HPA가 파드를 지우므로 그 파드의 줄이 빠져 대기열 순서·실효 입장 초과가 판정 불가가 된다
// (측정 세션 20261005-1440 — HPA 10 → 9로 10번째 파드의 감사 로그가 사라짐). 그래서 run 동안 파드 목록을 주기적으로 보고,
// api 컨테이너가 시작된 파드마다 `kubectl logs -f --prefix --timestamps --since-time=<run 시작>`을 붙여 파드별 파일에 **덧붙인다**.
//   - 스트림이 끊기면 지우지 않고, 마지막으로 받은 줄의 kubelet 시각(초 단위로 내림)부터 다시 받아 이미 받은 줄(시각+내용)은 건너뛴다.
//   - 컨테이너가 끝났으면(삭제·종료) 마지막으로 한 번 더 받아 남은 줄을 붙이고 끝낸다(container-terminated / pod-gone).
//   - 컨테이너가 바뀌었으면(재시작) 이전 컨테이너의 남은 줄을 받을 수 없으므로 complete=false(container-restarted).
//   - 시작 전(Pending·ContainerCreating) 파드는 컨테이너가 시작될 때까지 기다렸다 받는다. 실패는 연속 횟수로 세고 간격을 둔다.
//
//   node scripts/loadtest/collect-api-logs.mjs --out <run>/api-logs --since <run 시작 UTC ISO(초)> [--interval 5] [--for 초]
//     (SIGINT·SIGTERM 또는 --for가 지나면 멈추고 manifest.json을 쓴다. out 디렉터리가 비어 있지 않으면 시작하지 않는다 — 이전 run 섞임 방지)
//   node scripts/loadtest/collect-api-logs.mjs merge --dir <run>/api-logs --since <UTC ISO>
//     받아 둔 줄 중 kubelet 시각이 --since 이후인 줄을 `[pod/<파드>/api] <원래 줄>`(kubectl logs --prefix와 같은 형식)로 낸다.
//
// manifest.json: { since, startedAt, endedAt, pods: { <파드>: { firstSeenAt, containerId, follows, failures, endReason, complete, lines } } }
//   endReason: pod-gone | container-terminated | collector-stopped | container-restarted | stream-error
//   complete: 그 컨테이너의 --since 이후 줄을 빠짐없이 받았다고 볼 수 있는가(앞의 셋이면 true). 수집기가 run 전체를 덮었는지는
//   pod-coverage.mjs --collected가 startedAt·endedAt으로 본다.
// 한계: kubelet이 로그를 회전(기본 10MiB)한 뒤 다시 받으면 `kubectl logs`는 현재 파일만 읽어 앞부분이 빠질 수 있다 — 끊김 없이 받는 동안의
// 회전은 따라간다고 본다(추론). 자격증명은 쓰지 않는다(kubectl 컨텍스트). KUBECTL 환경변수로 다른 실행 파일을 줄 수 있다(테스트용 — .mjs면 node로 실행).
import { spawn, spawnSync } from "node:child_process";
import { createWriteStream, existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { parseArgs } from "node:util";
import { fileURLToPath } from "node:url";

const ISO_SEC = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/;
// `[pod/<파드>/<컨테이너>] <kubelet RFC3339Nano> <원래 줄>`
const LINE = /^(\[pod\/[^/\]]+\/[^\]]+\]) (\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z) (.*)$/;

/** kubelet 시각 → 정렬·비교 가능한 [초(ms), 나노 부분] 문자열 키. */
export function tsKey(ts) {
  const m = /^(.*T\d{2}:\d{2}:\d{2})(?:\.(\d+))?Z$/.exec(ts);
  if (!m) return null;
  return `${Date.parse(m[1] + "Z")}.${(m[2] ?? "").padEnd(9, "0")}`;
}

/** 받아 둔 파일들에서 --since 이후 줄만 kubectl logs --prefix 형식으로. 시각을 읽지 못한 줄은 그대로 세어 알린다. */
export function mergeLines(texts, sinceMs) {
  const out = [];
  let bad = 0;
  for (const t of texts) {
    for (const line of t.split("\n")) {
      if (!line) continue;
      const m = LINE.exec(line);
      if (!m) { bad++; continue; }
      const key = tsKey(m[2]);
      if (key === null) { bad++; continue; }
      if (Number(key.split(".")[0]) < sinceMs) continue; // --since는 초 단위라 같은 초의 줄은 모두 이후다
      out.push(`${m[1]} ${m[3]}`);
    }
  }
  return { lines: out, bad };
}

function mergeMain(argv) {
  const { values: a } = parseArgs({ args: argv, options: { dir: { type: "string" }, since: { type: "string" } } });
  if (!a.dir || !a.since || !ISO_SEC.test(a.since)) {
    console.error("사용: collect-api-logs.mjs merge --dir <디렉터리> --since <YYYY-MM-DDTHH:MM:SSZ>");
    process.exit(2);
  }
  const files = readdirSync(a.dir).filter((f) => f.endsWith(".log")).map((f) => readFileSync(join(a.dir, f), "utf8"));
  const { lines, bad } = mergeLines(files, Date.parse(a.since));
  if (bad) { console.error(`[collect-api-logs merge] 형식을 읽지 못한 줄 ${bad}개 — 판정 불가로 본다`); process.exit(2); }
  process.stdout.write(lines.map((l) => l + "\n").join(""));
}

function collectMain(argv) {
  const { values: a } = parseArgs({
    args: argv,
    options: {
      out: { type: "string" },
      since: { type: "string" },
      interval: { type: "string", default: "5" },
      for: { type: "string" },
      namespace: { type: "string", default: "flowticket" },
      selector: { type: "string", default: "app=flowticket-api" },
      container: { type: "string", default: "api" },
      "max-failures": { type: "string", default: "10" },
      "retry-ms": { type: "string", default: "2000" },
    },
  });
  const num = (name, v, min) => {
    const n = Number(v);
    if (!Number.isFinite(n) || n < min) { console.error(`--${name}는 ${min} 이상의 수다: ${v}`); process.exit(2); }
    return n;
  };
  if (!a.out || !a.since || !ISO_SEC.test(a.since)) {
    console.error("사용: collect-api-logs.mjs --out <디렉터리> --since <YYYY-MM-DDTHH:MM:SSZ> [--interval 5] [--for 초]");
    process.exit(2);
  }
  const INTERVAL_MS = num("interval", a.interval, 0.1) * 1000;
  const FOR_S = a.for === undefined ? null : num("for", a.for, 1);
  const MAX_FAILURES = num("max-failures", a["max-failures"], 1);
  const RETRY_MS = num("retry-ms", a["retry-ms"], 0);
  if (existsSync(a.out) && readdirSync(a.out).length) {
    console.error(`out 디렉터리가 비어 있지 않다: ${a.out} — 이전 run의 로그·manifest와 섞이지 않게 새 디렉터리를 준다`);
    process.exit(2);
  }
  mkdirSync(a.out, { recursive: true });
  const KUBECTL = process.env.KUBECTL || "kubectl";
  const kube = (args) => (KUBECTL.endsWith(".mjs") ? [process.execPath, [KUBECTL, ...args]] : [KUBECTL, args]);
  // API가 응답하지 않을 때 이벤트 루프가 막히지 않게 시간 제한을 둔다.
  const kubeSync = (args) => {
    const [cmd, full] = kube(["--request-timeout=10s", ...args]);
    const r = spawnSync(cmd, full, { encoding: "utf8", timeout: 15_000, maxBuffer: 64 * 1024 * 1024 });
    if (r.status !== 0) { console.error(`[collect-api-logs] kubectl ${args.slice(0, 4).join(" ")} 실패: ${(r.stderr || r.error || "").toString().trim().slice(0, 200)}`); return null; }
    return r.stdout;
  };
  const apiStatus = (pod) => (pod?.status?.containerStatuses ?? []).find((c) => c.name === a.container) ?? null;
  const getPod = (name) => { // undefined = 조회 실패, null = 없음
    const out = kubeSync(["-n", a.namespace, "get", "pod", name, "--ignore-not-found", "-o", "json"]);
    if (out === null) return undefined;
    if (!out.trim()) return null;
    try { return JSON.parse(out); } catch { return undefined; }
  };

  const startedAt = new Date().toISOString();
  const pods = new Map();
  let stopping = false;

  function track(name, containerId) {
    const p = { name, firstSeenAt: new Date().toISOString(), containerId, follows: 0, failures: 0, endReason: null, complete: false,
      lines: 0, lastTs: null, lastSecondKeys: new Set(), child: null, file: createWriteStream(join(a.out, `${name}.log`), { flags: "a" }),
      done: false, retryTimer: null };
    pods.set(name, p);
    return p;
  }

  // 받은 줄을 덧붙인다. 다시 받을 때는 이미 받은 마지막 초의 줄(시각+내용)을 건너뛴다.
  function take(p, line) {
    const m = LINE.exec(line);
    if (!m) { p.file.write(line + "\n"); p.lines++; return; } // 형식 밖 줄도 남긴다(merge가 판정 불가로 알린다)
    const key = tsKey(m[2]);
    const sec = key.split(".")[0];
    if (p.lastTs !== null) {
      const lastSec = p.lastTs.split(".")[0];
      if (Number(sec) < Number(lastSec)) return;
      if (sec === lastSec && p.lastSecondKeys.has(key + line)) return;
    }
    if (p.lastTs === null || sec !== p.lastTs.split(".")[0]) p.lastSecondKeys = new Set();
    p.lastSecondKeys.add(key + line);
    if (p.lastTs === null || key > p.lastTs) p.lastTs = key;
    p.file.write(line + "\n");
    p.lines++;
  }

  function sinceFor(p) {
    if (p.lastTs === null) return a.since;
    return new Date(Number(p.lastTs.split(".")[0])).toISOString().replace(".000Z", "Z");
  }

  function finish(p, reason, complete) {
    p.done = true; p.endReason = reason; p.complete = complete;
  }

  function follow(p, { once = false } = {}) {
    p.follows++;
    const [cmd, args] = kube(["-n", a.namespace, "logs", ...(once ? [] : ["-f"]), p.name, "-c", a.container, "--prefix", "--timestamps", `--since-time=${sinceFor(p)}`]);
    const child = spawn(cmd, args, { stdio: ["ignore", "pipe", "ignore"] });
    p.child = child;
    let buf = "";
    let connected = false; // 줄을 하나라도 받았나 — 연결 자체가 실패한 시도는 "직전 스트림의 끝"을 바꾸지 않는다
    child.stdout.setEncoding("utf8");
    child.stdout.on("data", (d) => {
      connected = true;
      buf += d;
      let i;
      while ((i = buf.indexOf("\n")) >= 0) { take(p, buf.slice(0, i)); buf = buf.slice(i + 1); }
    });
    child.on("close", (code) => {
      if (buf) take(p, buf);
      p.child = null;
      // 연결된 스트림이 정상 종료(컨테이너가 끝나 닫힘)했는가. 연결 실패(줄 없이 0 아닌 종료)는 기록을 바꾸지 않는다.
      if (code === 0) p.lastCleanClose = true; else if (connected) p.lastCleanClose = false;
      if (stopping || p.done) return;
      if (once) { // 끝난 컨테이너의 마지막 받기
        if (code === 0) { finish(p, p.pendingReason, true); return; }
        // 받지 못했는데 파드가 이미 없으면, 직전 스트림이 정상 종료(컨테이너가 끝나 닫힘)였을 때만 다 받았다고 본다.
        if (getPod(p.name) === null) { finish(p, "pod-gone", p.lastCleanClose === true); return; }
        retry(p, { once: true });
        return;
      }
      afterStream(p, code);
    });
  }

  function retry(p, opts) {
    p.failures++;
    if (p.failures > MAX_FAILURES) { finish(p, "stream-error", false); return; }
    p.retryTimer = setTimeout(() => { p.retryTimer = null; if (!stopping && !p.done) follow(p, opts); }, RETRY_MS);
  }

  function afterStream(p, code) {
    const pod = getPod(p.name);
    if (pod === undefined) { retry(p); return; } // 조회 실패 — 잠시 뒤 다시
    if (pod === null) { // 파드가 없어졌다. 컨테이너 로그도 함께 사라지므로 더 받을 수 없다 — 마지막으로 연결된 스트림이 정상 종료였을 때만 다 받았다고 본다.
      finish(p, "pod-gone", p.lastCleanClose === true);
      return;
    }
    const c = apiStatus(pod);
    if (!c || c.containerID !== p.containerId) { finish(p, "container-restarted", false); return; }
    if (c.state?.terminated) { // 컨테이너가 끝났다(삭제 중·종료) — 남은 줄을 한 번 더 받고 끝낸다
      p.pendingReason = "container-terminated";
      follow(p, { once: true });
      return;
    }
    if (code === 0) p.failures = 0; // 정상 종료였는데 컨테이너가 살아 있으면(예: API 서버 쪽 끊김) 연속 실패로 세지 않고 다시 받는다
    retry(p);
  }

  function tick() {
    if (stopping) return;
    const out = kubeSync(["-n", a.namespace, "get", "pods", "-l", a.selector, "-o", "json"]);
    if (out === null) return;
    let items;
    try { items = JSON.parse(out).items ?? []; } catch { return; }
    for (const pod of items) {
      const name = pod.metadata?.name;
      if (!name || pods.has(name)) continue;
      const c = apiStatus(pod);
      if (!c?.containerID) continue; // 아직 컨테이너가 없다(Pending·ContainerCreating) — 다음 주기에 다시 본다
      follow(track(name, c.containerID));
    }
  }

  function stop() {
    if (stopping) return;
    stopping = true;
    clearInterval(timer);
    const waits = [];
    for (const p of pods.values()) {
      if (p.retryTimer) { clearTimeout(p.retryTimer); p.retryTimer = null; }
      if (!p.done) finish(p, "collector-stopped", p.child !== null); // 받는 중이 아니었다면(재시도 대기 중) 빈 구간이 있을 수 있다
      if (p.child) { waits.push(new Promise((r) => p.child.once("close", r))); p.child.kill("SIGTERM"); }
    }
    Promise.all(waits)
      .then(() => Promise.all([...pods.values()].map((p) => new Promise((r) => p.file.end(r)))))
      .then(() => {
        const out = { since: a.since, startedAt, endedAt: new Date().toISOString(), pods: {} };
        for (const [n, p] of pods) {
          out.pods[n] = { firstSeenAt: p.firstSeenAt, containerId: p.containerId, follows: p.follows, failures: p.failures,
            endReason: p.endReason, complete: p.complete, lines: p.lines };
        }
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
  if (FOR_S !== null) setTimeout(stop, FOR_S * 1000);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const argv = process.argv.slice(2);
  if (argv[0] === "merge") mergeMain(argv.slice(1)); else collectMain(argv);
}
