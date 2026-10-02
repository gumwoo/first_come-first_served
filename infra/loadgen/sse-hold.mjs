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
//   sse-timeline.jsonl    — 1초마다 {t, sinceStartMs, open, started, opened, failed, serverClosed, expiredClosed,
//                           heldToEnd, clientStopped, stoppedBeforeOpen, skippedOverMax, skippedAfterStop, events}.
//                           SSE 활성 연결 수의 발생기 쪽 관측
//   sse-connections.jsonl — 연결마다 {token(해시), startedAt, openedAt, status, endedAt, endReason, events}
//   sse-summary.json      — 최종 집계
//
// 열린 연결은 정확히 한 가지 끝으로 센다(opened = serverClosed + expiredClosed + heldToEnd + clientStopped + 아직 열림):
//   serverClosed   — 서버가 예고 없이 끊음. **연결 유지 실패**다
//   expiredClosed  — queue.expired를 받은 뒤 서버가 정상 종료함(입장창 만료의 정상 흐름). 유지 실패가 아니다
//   heldToEnd      — 발생기가 --hold 시간 뒤 스스로 닫음. 유지로 센다
//   clientStopped  — 운영자가 Ctrl+C로 끊음. 유지 실패가 아니다
// 열지 못한 연결은 failed, 열리기 전에 Ctrl+C로 끊긴 연결은 stoppedBeforeOpen,
// --max-conn을 넘어 버린 토큰은 skippedOverMax, Ctrl+C 뒤에 들어온 토큰은 skippedAfterStop으로 센다
// (started = opened + failed + stoppedBeforeOpen + 아직 여는 중).
// Ctrl+C를 누르면 새 연결을 더 열지 않고 입력을 닫는다. 두 번째 Ctrl+C는 즉시 종료한다.
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

const stat = {
  started: 0, opened: 0, failed: 0,
  serverClosed: 0, expiredClosed: 0, heldToEnd: 0, clientStopped: 0, stoppedBeforeOpen: 0,
  skippedOverMax: 0, skippedAfterStop: 0, events: {},
};
let open = 0;
let inputDone = false;
let summarized = false;
let stopping = false; // Ctrl+C 이후에는 새 연결을 열지 않는다
const live = new Map(); // req → 그 연결을 끝내는 함수

// 토큰은 입장 권한과 묶인 값이라 원문을 파일에 남기지 않는다(SHA-256 앞 16자).
const ref = (t) => createHash("sha256").update(t).digest("hex").slice(0, 16);

function connect(token) {
  if (stopping) {
    stat.skippedAfterStop++;
    return;
  }
  if (stat.started >= MAX_CONN) {
    stat.skippedOverMax++; // 버리되 센다. 0이 아니면 목표 연결 수를 다 만들지 못했다
    return;
  }
  stat.started++;
  const rec = { token: ref(token), startedAt: Date.now(), openedAt: null, status: null, endedAt: null, endReason: null, events: {} };
  let finished = false;
  let timer = null;
  // 끝의 종류는 여기서 한 번만 센다. destroy()가 뒤이어 error/end를 일으켜도 다시 세지 않는다.
  const finish = (reason, counter) => {
    if (finished) return;
    finished = true;
    if (timer) clearTimeout(timer);
    if (counter === "clientStopped" && !rec.openedAt) counter = "stoppedBeforeOpen";
    if (counter) stat[counter]++;
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
      res.resume();
      finish("http_" + res.statusCode, "failed");
      return;
    }
    rec.openedAt = Date.now();
    stat.opened++;
    open++;
    timer = setTimeout(() => {
      finish("held", "heldToEnd");
      req.destroy();
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
    // 입장창 만료(queue.expired) 뒤의 종료는 서버의 정상 흐름이다(QueueAdmissionService.reclaim).
    const serverEnd = (reason) =>
      rec.events["queue.expired"] ? finish("expired_end", "expiredClosed") : finish(reason, "serverClosed");
    res.on("end", () => serverEnd("server_end"));
    res.on("error", () => serverEnd("stream_error"));
  });
  req.on("error", (e) => {
    const reason = "conn_error:" + (e.code || e.message);
    if (!rec.openedAt) finish(reason, "failed");
    else if (rec.events["queue.expired"]) finish("expired_end", "expiredClosed"); // 만료 뒤 RST도 정상 종료다
    else finish(reason, "serverClosed");
  });
  live.set(req, finish);
}

const t0 = Date.now();
const tick = setInterval(() => {
  timeline.write(JSON.stringify({ t: Date.now(), sinceStartMs: Date.now() - t0, open, ...stat, events: { ...stat.events } }) + "\n");
}, 1000);

function maybeExit() {
  if (summarized || !inputDone || live.size > 0) return;
  summarized = true;
  clearInterval(tick);
  const summary = { base: args.base, holdSeconds: Number(args.hold), ...stat, endedAt: Date.now(), elapsedMs: Date.now() - t0 };
  writeFileSync(`${args.out}/sse-summary.json`, JSON.stringify(summary, null, 2));
  connLog.end();
  timeline.end();
  console.error(`[sse-hold] 종료: 시작 ${stat.started}, 열림 ${stat.opened}, 실패 ${stat.failed}, ` +
    `서버가 끊음 ${stat.serverClosed}, 만료 후 종료 ${stat.expiredClosed}, 끝까지 유지 ${stat.heldToEnd}, ` +
    `운영자 중단 ${stat.clientStopped}(열기 전 ${stat.stoppedBeforeOpen}), 상한 초과로 버림 ${stat.skippedOverMax}, ` +
    `중단 뒤 버림 ${stat.skippedAfterStop}`);
}

const input = args.tokens ? createReadStream(args.tokens) : process.stdin;
const rl = createInterface({ input, crlfDelay: Infinity });
rl.on("line", (line) => {
  if (stopping) {
    if (/QTOKEN\s+\S+/.test(line)) stat.skippedAfterStop++;
    else if (!args.tokens) process.stdout.write(line + "\n"); // 중단 뒤의 k6 로그도 남긴다
    return;
  }
  // 표준입력이면 k6 로그가 섞여 들어온다. QTOKEN 줄만 쓴다. 토큰 파일이면 줄 전체가 토큰이다.
  const m = args.tokens ? [null, line.trim()] : line.match(/QTOKEN\s+(\S+)/);
  if (m && m[1]) connect(m[1]);
  else if (!args.tokens) process.stdout.write(line + "\n"); // k6 출력은 그대로 흘려 보낸다
});
rl.on("close", () => {
  inputDone = true;
  maybeExit();
});

// 측정 세션을 끊을 때(Ctrl+C): 새 연결을 더 열지 않고 붙든 연결을 닫은 뒤 요약을 남긴다.
// 운영자가 끊은 것이라 서버 단절로 세지 않는다. 계획서 §3.3의 "즉시 중단"이 실제 대상에 연결을 남기지 않게 한다.
//
// 표준입력은 닫지 않고 끝(EOF)까지 계속 읽는다. 앞단 k6도 같은 Ctrl+C를 받아 graceful stop 중에 요약과 마지막
// 원시 출력을 쓰는데, 여기서 파이프를 먼저 닫으면 k6가 끊긴 파이프에 쓰다 죽어 그 출력을 잃는다. 중단 뒤의 k6 로그는
// 그대로 흘려 보내고, QTOKEN 줄은 연결하지 않고 skippedAfterStop으로만 센다. 토큰 파일 입력이면 바로 닫는다.
process.on("SIGINT", () => {
  if (stopping) process.exit(130); // 두 번째 Ctrl+C
  stopping = true;
  if (args.tokens) {
    inputDone = true;
    rl.close();
  }
  for (const [r, finish] of [...live]) {
    finish("client_stop", "clientStopped");
    r.destroy();
  }
  maybeExit();
});
