#!/usr/bin/env node
// api 파드 로그를 run 동안 계속 받아 둔다 — HPA 축소로 지워질 파드의 감사 줄(승격·회수·이탈)을 지워지기 전에 남기기 위해서다.
//
// 사후 검사(check-correctness.sh)는 원래 run이 끝난 뒤 `kubectl logs`로 지금 살아 있는 파드만 읽는다. 반응형 확장·축소를
// 보는 run(Platform 축)에서는 HPA가 파드를 지우므로 그 파드의 줄이 빠져 대기열 순서·실효 입장 초과가 판정 불가가 된다
// (측정 세션 20261005-1440 — HPA 10 → 9로 10번째 파드의 감사 로그가 사라짐). 그래서 run 동안 파드 목록을 주기적으로 보고,
// api 컨테이너가 시작된 파드마다 `kubectl logs -f --prefix --timestamps --since-time=<run 시작>`을 붙여 파드별 파일에 덧붙인다.
//
// "다 받았다(complete)"는 보수적으로만 인정한다:
//   - 지워진 파드(pod-gone): **방금 끝난 그 스트림**이 연결돼 줄을 받고 있었고 종료 코드 0으로 끝났을 때만 — kubectl logs -f는 파드가 지워져
//     컨테이너가 끝나면 마지막 줄까지 낸 뒤 0으로 끝난다(측정 세션 20261007-platform에서 임시 파드로 확인). 끊긴 뒤 다시 붙지 못한 채 지워졌으면 false.
//   - 끝난 컨테이너(container-terminated): 시간 제한을 둔 마지막 받기(follow 없이)가 0으로 끝났을 때만.
//   - 수집기를 멈출 때 살아 있는 파드(collector-stopped): follow를 끝낸 뒤 시간 제한을 둔 마지막 따라잡기가 0으로 끝나고 컨테이너가 처음 본 그대로일 때만.
//   - 컨테이너가 바뀌면(재시작) 이전 컨테이너의 남은 줄을 받을 수 없어 false(container-restarted). 연속 실패가 상한을 넘으면 false(stream-error).
// 끊겨서 다시 받을 때는 지우지 않고, 마지막으로 받은 줄의 kubelet 시각(초 단위로 내림)부터 다시 받아 그 겹침 구간에서만 이미 받은 줄(시각+내용)을 건너뛴다.
// 줄바꿈으로 끝나지 않은 마지막 조각(끊긴 줄)은 버린다 — 다시 받을 때 온전한 줄로 온다.
// 시작하지 못하고(컨테이너 없이) 사라진 파드는 neverStarted로 남긴다 — 줄이 없는 것이 맞는 파드다(pod-coverage가 대상에서 뺀다).
//
//   node scripts/loadtest/collect-api-logs.mjs --out <run>/api-logs --since <run 시작 UTC ISO(초)> [--interval 5] [--for 초]
//     (SIGINT·SIGTERM 또는 --for가 지나면 멈추고 manifest.json을 쓴다. out 디렉터리가 비어 있지 않으면 시작하지 않는다 — 이전 run 섞임 방지)
//   node scripts/loadtest/collect-api-logs.mjs merge --dir <run>/api-logs --since <UTC ISO>
//     받아 둔 줄 중 kubelet 시각이 --since 이후인 줄을 `[pod/<파드>/api] <원래 줄>`(kubectl logs --prefix와 같은 형식)로 낸다. 형식 밖 줄이 있으면 실패.
//
// manifest.json: { since, startedAt, endedAt, pods: { <파드>: { firstSeenAt, containerId, follows, failures, endReason, complete, lines } }, neverStarted: [<파드>], unknown: [<파드>] }
//   unknown: Pending으로 본 뒤 조회가 오래 실패한 끝에 사라진 파드 — 시작했는지 몰라 대조 대상에 남는다(받지 못함 → 판정 불가).
// 회전: 끊긴 뒤 다시 받기 전에 kubelet이 로그를 회전(기본 10MiB)했으면 `kubectl logs`는 현재 파일만 읽어 앞부분이 빠진다 — 다시 받을 때 마지막으로
// 받은 줄이 다시 오지 않으면 그것으로 감지해 complete=false(gap). 끊김 없이 받는 동안의 회전은 따라간다고 본다(추론). 자격증명은 쓰지 않는다(kubectl 컨텍스트).
// KUBECTL 환경변수로 다른 실행 파일을 줄 수 있다(테스트용 — .mjs면 node로 실행).
import { spawn, spawnSync } from "node:child_process";
import { createReadStream, createWriteStream, existsSync, mkdirSync, readdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { parseArgs } from "node:util";
import { fileURLToPath } from "node:url";

const ISO_SEC = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/;
// `[pod/<파드>/<컨테이너>] <kubelet RFC3339Nano> <원래 줄>`
// s 플래그: 로그 내용에 줄 구분 문자(CR, 유니코드 줄·문단 구분자)가 있어도 한 줄로 맞춘다.
const LINE = /^(\[pod\/[^/\]]+\/[^\]]+\]) (\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z) (.*)$/s;
// 줄은 \n으로만 나눈다 — readline은 \r에서도 나눠 merge가 내용에 \r이 든 줄을 형식 밖 줄로 셌다(run 전체가 판정 불가).
async function* lines(file) {
  let buf = "";
  for await (const chunk of createReadStream(file, "utf8")) {
    buf += chunk;
    let i;
    while ((i = buf.indexOf("\n")) >= 0) { yield buf.slice(0, i); buf = buf.slice(i + 1); }
  }
  if (buf) yield buf;
}

/** kubelet 시각 → "<초 ms 13자리>.<나노 9자리>" — 문자열 비교로 순서가 맞는 키. 읽지 못하면 null. */
export function tsKey(ts) {
  const m = /^(.*T\d{2}:\d{2}:\d{2})(?:\.(\d+))?Z$/.exec(ts);
  if (!m) return null;
  const ms = Date.parse(m[1] + "Z");
  if (!Number.isFinite(ms)) return null;
  return `${String(ms).padStart(13, "0")}.${(m[2] ?? "").padEnd(9, "0").slice(0, 9)}`;
}
const secOf = (key) => Number(key.split(".")[0]);

/** 한 줄 → 합칠 줄(since 이전이면 null). 형식 밖이면 undefined. */
export function mergeLine(line, sinceMs) {
  const m = LINE.exec(line);
  if (!m) return undefined;
  const key = tsKey(m[2]);
  if (key === null) return undefined;
  if (secOf(key) < sinceMs) return null; // --since는 초 단위라 같은 초의 줄은 모두 이후다(kubectl --since-time과 같은 경계)
  return `${m[1]} ${m[3]}`;
}

async function mergeMain(argv) {
  const { values: a } = parseArgs({ args: argv, options: { dir: { type: "string" }, since: { type: "string" } } });
  if (!a.dir || !a.since || !ISO_SEC.test(a.since)) {
    console.error("사용: collect-api-logs.mjs merge --dir <디렉터리> --since <YYYY-MM-DDTHH:MM:SSZ>");
    process.exit(2);
  }
  const sinceMs = Date.parse(a.since);
  let bad = 0;
  for (const f of readdirSync(a.dir).filter((x) => x.endsWith(".log"))) {
    for await (const line of lines(join(a.dir, f))) {
      if (!line) continue;
      const out = mergeLine(line, sinceMs);
      if (out === undefined) { bad++; continue; }
      if (out !== null && !process.stdout.write(out + "\n")) await new Promise((r) => process.stdout.once("drain", r));
    }
  }
  if (bad) { console.error(`[collect-api-logs merge] 형식을 읽지 못한 줄 ${bad}개 — 판정 불가로 본다`); process.exit(2); }
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
      "attempt-slack": { type: "string", default: "5" }, // run 전부터 있던 파드의 첫 시도가 run 시작보다 이만큼(초) 넘게 늦으면 "늦게 붙음"
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
  const SINCE_MS = Date.parse(a.since);
  const FIRST_SLACK_MS = 30_000; // 첫 받기 회전 판정의 여유(위 take 주석)
  const FOR_S = a.for === undefined ? null : num("for", a.for, 1);
  const MAX_FAILURES = num("max-failures", a["max-failures"], 1);
  const RETRY_MS = num("retry-ms", a["retry-ms"], 0);
  const ATTEMPT_SLACK_MS = num("attempt-slack", a["attempt-slack"], 0) * 1000;
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
  const pending = new Set(); // 아직 컨테이너가 없는 파드(Pending·ContainerCreating) — 시작하지 못하고 사라지면 neverStarted
  const neverStarted = new Set();
  const unknown = new Set(); // Pending으로 본 뒤 오랜 조회 공백 끝에 사라진 파드 — 시작했는지 모른다(대조 대상에 남긴다)
  let lastListOk = null;
  let stopping = false;

  // startedMs: 컨테이너 시작 시각(ms, 모르면 NaN). run 시작 뒤에 뜬 파드의 첫 받기가 회전으로 잘렸는지 보는 데 쓴다(take).
  function track(name, containerId, startedMs = NaN) {
    const p = { name, firstSeenAt: new Date().toISOString(), containerId, startedMs, follows: 0, failures: 0, endReason: null, complete: false,
      lines: 0, lastTs: null, lastSecond: new Set(), overlap: null, child: null,
      file: createWriteStream(join(a.out, `${name}.log`), { flags: "a" }), done: false, timer: null };
    pods.set(name, p);
    return p;
  }

  // 받은 줄을 덧붙인다. 다시 받은 직후의 겹침 구간(이전 마지막 시각까지)에서만 이미 받은 줄을 건너뛴다.
  function take(p, line) {
    const m = LINE.exec(line);
    const key = m ? tsKey(m[2]) : null;
    if (key === null) { p.file.write(line + "\n"); p.lines++; return; } // 형식 밖 줄도 남긴다(merge가 판정 불가로 알린다)
    // 첫 받기의 회전: 첫 받기가 제때 붙지 못한 사이(실패·늦게 띄움) 로그가 회전되면 앞부분이 잘린다. 첫 시도가 실패했거나 늦게 띄워졌고(troubled)
    // 첫 줄이 기준(run 시작 뒤 뜬 파드는 컨테이너 시작 + 30초, 그 밖은 run 시작)보다 늦으면 그 사이를 확인할 수 없어 구멍으로 본다.
    // 제때 붙은 한가한 파드(첫 줄이 늦을 뿐)는 통과한다 — 한가하면 회전도 없다(추론).
    // 남는 구멍: 첫 시도를 제때 띄웠는데 연결 단계(dial·TLS)에서 오래 걸린 경우는 구분하지 못한다(시간 제한 범위 안 — 한계).
    if (p.lines === 0 && p.lastTs === null) {
      const startedAfterSince = Number.isFinite(p.startedMs) && p.startedMs >= SINCE_MS;
      const ref = startedAfterSince ? p.startedMs : SINCE_MS;
      // 첫 시도가 늦었나: 확인할 수 없는 구간의 시작(run 뒤 뜬 파드는 컨테이너 시작, 그 밖은 run 시작)보다 늦게 띄웠으면(목록 조회 장애 등으로 발견이 늦음).
      // 여유: run 뒤 뜬 파드는 30초(새 파일은 비어서 시작), 그 밖은 --attempt-slack(기본 5초 — 수집기를 run 시작 전·직전에 띄우면 첫 조회가 그 안에 끝난다).
      const troubled = p.follows > 1 || p.firstAttemptAt > (startedAfterSince ? p.startedMs + FIRST_SLACK_MS : SINCE_MS + ATTEMPT_SLACK_MS);
      // 줄 시각 여유는 run 시작 뒤에 뜬 파드에만 준다 — 새 컨테이너의 로그 파일은 비어서 시작하므로 30초 안에 회전할 만큼 차지 않는다(추론).
      // run 시작 전부터 있던 파드(또는 시작 시각을 모름)는 파일이 이미 차 있어 run 시작 직후에도 회전할 수 있으므로 여유 0.
      if (troubled && secOf(key) > ref + (startedAfterSince ? FIRST_SLACK_MS : 0)) p.gap = true;
    }
    if (p.overlap) {
      if (key < p.overlap.fromKey) return; // 다시 받기의 since(초 단위)보다 앞 — 오지 않아야 하지만 방어
      if (key <= p.overlap.lastKey) {
        if (p.overlap.seen.has(key + line)) { if (key === p.overlap.lastKey) p.overlap.sawLast = true; return; }
      } else {
        // 이전 마지막 시각을 넘었다 — 겹침 구간 끝. 다시 받기의 since는 마지막 줄의 초로 내림이라 kubelet은 그 마지막 줄을 반드시 다시 보낸다.
        // 오지 않았다면 그 사이 로그가 회전돼 앞부분이 잘린 것이다 — 빠진 줄이 있을 수 있어 다 받았다고 보지 않는다.
        if (!p.overlap.sawLast) p.gap = true;
        p.overlap = null;
      }
    }
    if (p.lastTs === null || key > p.lastTs) {
      if (p.lastTs === null || secOf(key) !== secOf(p.lastTs)) p.lastSecond = new Set();
      p.lastTs = key;
    }
    if (secOf(key) === secOf(p.lastTs)) p.lastSecond.add(key + line);
    p.file.write(line + "\n");
    p.lines++;
  }

  const sinceArg = (p) => (p.lastTs === null ? a.since : new Date(secOf(p.lastTs)).toISOString().replace(".000Z", "Z"));

  // 회전으로 잘린 구간(gap)이 있었으면 어떤 끝이든 다 받았다고 보지 않는다.
  function finish(p, reason, complete) { p.done = true; p.endReason = reason; p.complete = complete && !p.gap; }

  /** 로그 받기 한 번. once면 follow 없이(시간 제한) 지금까지의 줄만. 끝나면 cb(code, connected). */
  function stream(p, { once = false } = {}, cb) {
    p.follows++;
    if (p.follows === 1) p.firstAttemptAt = Date.now();
    if (p.lastTs !== null) p.overlap = { fromKey: `${String(secOf(p.lastTs)).padStart(13, "0")}.000000000`, lastKey: p.lastTs, seen: new Set(p.lastSecond), sawLast: false };
    const args = ["-n", a.namespace, "logs", ...(once ? ["--request-timeout=20s"] : ["-f"]), p.name, "-c", a.container, "--prefix", "--timestamps", `--since-time=${sinceArg(p)}`];
    const [cmd, full] = kube(args);
    const child = spawn(cmd, full, { stdio: ["ignore", "pipe", "ignore"] });
    p.child = child;
    let buf = "", connected = false;
    const killer = once ? setTimeout(() => child.kill("SIGTERM"), 30_000) : null;
    child.stdout.setEncoding("utf8");
    child.stdout.on("data", (d) => {
      connected = true;
      buf += d;
      let i;
      while ((i = buf.indexOf("\n")) >= 0) { take(p, buf.slice(0, i)); buf = buf.slice(i + 1); }
    });
    child.on("close", (code) => {
      if (killer) clearTimeout(killer);
      p.child = null;
      // buf에 남은 조각(줄바꿈 없음)은 끊긴 줄이다 — 버린다(다시 받을 때 온전한 줄로 온다).
      if (connected) p.failures = 0;
      // 다시 받기가 0으로 끝났는데 마지막으로 받았던 줄을 한 번도 다시 받지 못했다(줄이 없거나 그보다 뒤만) — 회전으로 잘렸다.
      if (code === 0 && p.overlap && !p.overlap.sawLast) p.gap = true;
      p.overlap = null;
      cb(code, connected);
    });
  }

  function later(p, fn) { p.timer = setTimeout(() => { p.timer = null; if (!stopping && !p.done) fn(); }, RETRY_MS); }
  function fail(p, fn) { p.failures++; if (p.failures > MAX_FAILURES) finish(p, "stream-error", false); else later(p, fn); }

  function follow(p) {
    stream(p, {}, (code, connected) => { if (!stopping && !p.done) check(p, code === 0 && connected); });
  }

  /** 스트림이 끝난 뒤 파드 상태로 다음을 정한다. cleanJustNow: 방금 끝난 스트림이 연결돼 있었고 0으로 끝났는가. */
  function check(p, cleanJustNow) {
    const pod = getPod(p.name);
    // 조회만 다시 — 다시 받지 않는다. 다만 "방금 정상 종료"는 스트림이 닫힌 직후의 조회에만 의미가 있다: 조회가 실패한 사이 컨테이너가
    // 종료 처리 중 줄을 찍고 지워졌을 수 있으므로, 재시도 뒤 파드가 없으면 다 받았다고 보지 않는다(보수적 — 드문 경우라 판정 불가 비용이 작다).
    if (pod === undefined) { fail(p, () => check(p, false)); return; }
    if (pod === null) { finish(p, "pod-gone", cleanJustNow); return; }
    const c = apiStatus(pod);
    if (!c || c.containerID !== p.containerId) { finish(p, "container-restarted", false); return; }
    if (c.state?.terminated) { // 컨테이너가 끝났다(삭제 중·종료) — 남은 줄을 한 번 더 받고 끝낸다
      stream(p, { once: true }, (code) => {
        if (stopping || p.done) return;
        if (code === 0) finish(p, "container-terminated", true);
        else fail(p, () => check(p, false));
      });
      return;
    }
    // 살아 있는데 끝났다(API 쪽 끊김 등) — 이어 받는다. 연결되지 않은 시도는 연속 실패로 센다.
    if (cleanJustNow) later(p, () => follow(p)); else fail(p, () => follow(p));
  }

  function tick() {
    if (stopping) return;
    const qStart = Date.now();
    const out = kubeSync(["-n", a.namespace, "get", "pods", "-l", a.selector, "-o", "json"]);
    if (out === null) return;
    let items;
    try { items = JSON.parse(out).items ?? []; } catch { return; }
    const now = Date.now();
    // 직전 성공 조회와의 간격이 짧을 때만 "Pending으로 봤다 → 지금 없다"를 시작하지 못하고 사라진 것으로 본다. 조회가 오래 실패했다면
    // 그 사이 시작해 줄을 찍고 지워졌을 수 있다 — 그런 파드는 unknown으로 남겨 대조 대상에서 빼지 않는다.
    // 간격은 직전 조회 **시작**부터 이번 조회 끝까지로 잰다(조회 한 번이 길어도 두 목록 사이를 넉넉히 덮게).
    const recent = lastListOk !== null && now - lastListOk <= 2 * INTERVAL_MS + 1000;
    const present = new Set();
    for (const pod of items) {
      const name = pod.metadata?.name;
      if (!name) continue;
      present.add(name);
      if (pods.has(name)) continue;
      const c = apiStatus(pod);
      if (!c?.containerID) { pending.add(name); continue; } // 아직 컨테이너가 없다 — 다음 주기에 다시 본다
      pending.delete(name);
      follow(track(name, c.containerID, Date.parse(c.state?.running?.startedAt ?? c.state?.terminated?.startedAt ?? "")));
    }
    for (const name of [...pending]) if (!present.has(name)) { pending.delete(name); (recent ? neverStarted : unknown).add(name); }
    lastListOk = qStart;
  }

  async function stop() {
    if (stopping) return;
    stopping = true;
    clearInterval(timer);
    const live = [];
    for (const p of pods.values()) {
      if (p.timer) { clearTimeout(p.timer); p.timer = null; }
      if (p.done) continue;
      if (p.child) { const ch = p.child; await new Promise((r) => { ch.once("close", r); ch.kill("SIGTERM"); }); }
      live.push(p);
    }
    // 살아 있던 파드: follow 없이 시간 제한을 둔 마지막 따라잡기(파드마다 동시에) — 성공하고 컨테이너가 처음 본 그대로일 때만 다 받았다고 본다.
    // 멈추는 사이 지워졌으면 그 스트림의 끝을 확인하지 못했으므로 다 받지 못한 것으로 둔다.
    const codes = await Promise.all(live.map((p) => new Promise((r) => stream(p, { once: true }, (c) => r(c)))));
    live.forEach((p, i) => {
      const pod = getPod(p.name);
      const same = Boolean(pod) && apiStatus(pod)?.containerID === p.containerId;
      finish(p, pod === null ? "pod-gone" : "collector-stopped", codes[i] === 0 && same);
    });
    // 아직 Pending인 파드: 마지막 성공 조회가 최근일 때만 시작하지 못한 것으로 본다(그 뒤 줄은 수집 구간 밖).
    const recent = lastListOk !== null && Date.now() - lastListOk <= 2 * INTERVAL_MS + 1000;
    for (const name of pending) (recent ? neverStarted : unknown).add(name);
    await Promise.all([...pods.values()].map((p) => new Promise((r) => p.file.end(r))));
    const out = { since: a.since, startedAt, endedAt: new Date().toISOString(), attemptSlackSec: ATTEMPT_SLACK_MS / 1000, pods: {}, neverStarted: [...neverStarted], unknown: [...unknown] };
    for (const [n, p] of pods) {
      out.pods[n] = { firstSeenAt: p.firstSeenAt, containerId: p.containerId, follows: p.follows, failures: p.failures,
        endReason: p.endReason, complete: p.complete, lines: p.lines };
    }
    writeFileSync(join(a.out, "manifest.json"), JSON.stringify(out, null, 1));
    const bad = Object.values(out.pods).filter((p) => !p.complete).length;
    console.error(`[collect-api-logs] 파드 ${pods.size}개, 다 받지 못한 파드 ${bad}, 시작하지 못한 파드 ${neverStarted.size}`);
    process.exit(0);
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
