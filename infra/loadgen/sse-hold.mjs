#!/usr/bin/env node
// SSE 연결 발생기(loadtest-100k-plan §2.4 ②): 대기 토큰마다 /sse/queue/{token} 연결을 열고 붙든다.
//
// 왜 k6가 아닌가: k6 코어에는 SSE 클라이언트가 없고, http.get으로 붙들면 VU 하나가 연결 하나를 대기 내내
// 쥔다. 동시 대기 수만큼 VU가 필요해져 발생기가 먼저 무너진다. Node는 연결 하나가 소켓 하나라 가볍다.
//
// 입력: 표준입력의 "QTOKEN <token>" 줄(queue-entry-rate.js의 EMIT_TOKENS=1 출력), 또는 --tokens 파일(줄마다 토큰).
//   k6 run --log-format=raw -e EMIT_TOKENS=1 ... 2>&1 | node infra/loadgen/sse-hold.mjs --base ... --out <run 디렉터리>
//
// 출력(--out 디렉터리):
//   sse-timeline.jsonl    — 1초마다 {t, open, opened, failed, closed, events}. SSE 활성 연결 수의 발생기 쪽 관측
//   sse-connections.jsonl — 연결마다 {token(해시), startedAt, openedAt, status, endedAt, endReason, events}
//   sse-summary.json      — 최종 집계
//
// 연결 유지율은 "열린 뒤 서버가 끊은 연결"로 본다. 발생기가 --hold 시간 뒤에 스스로 닫은 것은 유지로 센다.
import http from "node:http";
import https from "node:https";
import { createHash } from "node:crypto";
import { createReadStream, createWriteStream, mkdirSync, writeFileSync } from "node:fs";
import { createInterface } from "node:readline";
import { parseArgs } from "node:util";

const { values: args } = parseArgs({
  options: {
    base: { type: "string" },              // 예: https://flow-ticket.com/api (ALB 공인 경로, §4)
    out: { type: "string" },               // run 디렉터리
    tokens: { type: "string" },            // 토큰 파일. 없으면 표준입력
    hold: { type: "string", default: "300" },        // 연결을 붙드는 최대 초
    "max-conn": { type: "string", default: "100000" },
  },
});
if (!args.base || !args.out) {
  console.error("사용: sse-hold.mjs --base <URL> --out <디렉터리> [--tokens <파일>] [--hold 초] [--max-conn N]");
  process.exit(2);
}

const HOLD_MS = Number(args.hold) * 1000;
const MAX_CONN = Number(args["max-conn"]);
const base = new URL(args.base);
const client = base.protocol === "https:" ? https : http;
// 연결마다 소켓을 따로 쓴다. 기본 에이전트의 풀링은 SSE처럼 끝나지 않는 응답에서 연결 수를 제한한다.
const agent = new client.Agent({ keepAlive: false, maxSockets: Infinity });

mkdirSync(args.out, { recursive: true });
const connLog = createWriteStream(`${args.out}/sse-connections.jsonl`);
const timeline = createWriteStream(`${args.out}/sse-timeline.jsonl`);

const stat = { started: 0, opened: 0, failed: 0, serverClosed: 0, heldToEnd: 0, events: {} };
let open = 0;
let inputDone = false;
const live = new Set();

// 토큰은 입장 권한과 묶인 값이라 원문을 파일에 남기지 않는다(QueueAudit과 같은 해시 앞 16자).
const ref = (t) => createHash("sha256").update(t).digest("hex").slice(0, 16);

function connect(token) {
  if (stat.started >= MAX_CONN) return;
  stat.started++;
  const rec = { token: ref(token), startedAt: Date.now(), openedAt: null, status: null, endedAt: null, endReason: null, events: {} };
  let finished = false;
  const finish = (reason) => {
    if (finished) return;
    finished = true;
    rec.endedAt = Date.now();
    rec.endReason = reason;
    if (rec.openedAt) open--;
    live.delete(req);
    connLog.write(JSON.stringify(rec) + "\n");
    maybeExit();
  };

  const url = new URL(`${base.pathname.replace(/\/$/, "")}/sse/queue/${encodeURIComponent(token)}`, base);
  const req = client.get(url, { agent, headers: { Accept: "text/event-stream" } }, (res) => {
    rec.status = res.statusCode;
    if (res.statusCode !== 200) {
      stat.failed++;
      res.resume();
      finish("http_" + res.statusCode);
      return;
    }
    rec.openedAt = Date.now();
    stat.opened++;
    open++;
    const timer = setTimeout(() => {
      stat.heldToEnd++;
      req.destroy();
      finish("held");
    }, HOLD_MS);
    let buf = "";
    res.setEncoding("utf8");
    res.on("data", (chunk) => {
      buf += chunk;
      let i;
      while ((i = buf.indexOf("\n\n")) >= 0) {
        const frame = buf.slice(0, i);
        buf = buf.slice(i + 2);
        const m = frame.match(/^event:\s*(.+)$/m);
        if (m) {
          const name = m[1].trim();
          rec.events[name] = (rec.events[name] || 0) + 1;
          stat.events[name] = (stat.events[name] || 0) + 1;
        }
      }
    });
    res.on("end", () => {
      clearTimeout(timer);
      if (!finished) stat.serverClosed++;
      finish("server_end");
    });
    res.on("error", () => {
      clearTimeout(timer);
      if (!finished) stat.serverClosed++;
      finish("stream_error");
    });
  });
  req.on("error", (e) => {
    if (!rec.openedAt && !finished) stat.failed++;
    finish("conn_error:" + (e.code || e.message));
  });
  live.add(req);
}

const t0 = Date.now();
const tick = setInterval(() => {
  timeline.write(JSON.stringify({ t: Date.now(), sinceStartMs: Date.now() - t0, open, ...stat, events: { ...stat.events } }) + "\n");
}, 1000);

function maybeExit() {
  if (!inputDone || live.size > 0) return;
  clearInterval(tick);
  const summary = { base: args.base, holdSeconds: Number(args.hold), ...stat, endedAt: Date.now(), elapsedMs: Date.now() - t0 };
  writeFileSync(`${args.out}/sse-summary.json`, JSON.stringify(summary, null, 2));
  connLog.end();
  timeline.end();
  console.error(`[sse-hold] 종료: 시작 ${stat.started}, 열림 ${stat.opened}, 실패 ${stat.failed}, 서버가 끊음 ${stat.serverClosed}, 끝까지 유지 ${stat.heldToEnd}`);
}

const input = args.tokens ? createReadStream(args.tokens) : process.stdin;
const rl = createInterface({ input, crlfDelay: Infinity });
rl.on("line", (line) => {
  // 표준입력이면 k6 로그가 섞여 들어온다. QTOKEN 줄만 쓴다. 토큰 파일이면 줄 전체가 토큰이다.
  const m = args.tokens ? [null, line.trim()] : line.match(/QTOKEN\s+(\S+)/);
  if (m && m[1]) connect(m[1]);
  else if (!args.tokens) process.stdout.write(line + "\n"); // k6 출력은 그대로 흘려 보낸다
});
rl.on("close", () => {
  inputDone = true;
  maybeExit();
});

// 측정 세션을 끊을 때(Ctrl+C) 붙든 연결을 닫고 요약을 남긴다.
process.on("SIGINT", () => {
  inputDone = true;
  for (const r of live) r.destroy();
});
