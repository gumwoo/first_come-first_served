#!/usr/bin/env node
// 대기자 폴링 발생기(최종 시험 — loadtest-100k-plan §8 "대기 상태 전달"). 진입 발생기(k6 queue-entry-rate.js, EMIT_TOKENS=1)가
// 내는 "QTOKEN <token> <retryAfterMs>" 줄을 받아, 토큰마다 프론트 useQueue와 같은 규칙으로 상태를 묻는다:
//   다음 조회 = max(최소 간격, 서버 retryAfterMs) + floor(random × 그 값 × jitter)   (jitter는 더하기만 — ADR-023 §2)
//   일시 오류(연결 실패·5xx·본문 파싱 실패)는 2초부터 두 배씩(상한 30초), ADMITTED·EXPIRED·410(QUEUE_EXPIRED)이면 그 토큰은 끝.
// 대기열 SSE 발생기를 대신한다(#351). 토큰마다 "ADMITTED를 처음 본 시각"을 남겨, 서버 감사 로그의 승격 시각
// (queue.audit kind=admit … at=…)과 토큰 해시(ref — SHA-256 앞 8바이트, QueueAudit.ref와 같다)로 맞춰 입장 인지 지연을 잰다
// (scripts/loadtest/admit-latency.mjs).
//
//   k6 run --log-format=raw -e EMIT_TOKENS=1 ... infra/k6/queue-entry-rate.js 2>&1 \
//     | node infra/loadgen/poll-hold.mjs --base https://flow-ticket.com/api --out <run>/poll-g1 --hold 300
//
// --hold: 첫 토큰을 받은 때부터 이 초가 지나면 남은 토큰의 폴링을 멈추고 결과를 쓴다(대기자가 창을 닫는 것과 같다).
// 토큰이 아닌 줄은 그대로 stdout으로 넘긴다(k6 로그가 run 디렉터리에 그대로 남게).
// 출력: <out>/tokens.jsonl(토큰별 ref·토큰 줄을 받은 시각·첫 retryAfterMs·조회 수·마지막 상태·ADMITTED 처음 본 시각·만료 시각),
//       <out>/summary.json(조회 수·응답 코드·대기열 상태별 수·지연 분위수·초별 조회 수·오류 수). 토큰 원문은 남기지 않는다.
import { createHash } from "node:crypto";
import { mkdirSync, realpathSync, writeFileSync } from "node:fs";
import http from "node:http";
import https from "node:https";
import { createInterface } from "node:readline";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";

const BACKOFF_MAX_MS = 30_000;

/** 감사 로그와 같은 토큰 참조: SHA-256 앞 8바이트 hex(QueueAudit.ref). */
export const ref = (token) => createHash("sha256").update(token, "utf8").digest("hex").slice(0, 16);

/** 다음 조회까지 기다릴 시간 — 프론트 useQueue.nextPollDelay와 같은 규칙. */
export function nextDelay(retryAfterMs, minMs, jitter, random = Math.random) {
  const base = Math.max(minMs, Number.isFinite(retryAfterMs) ? retryAfterMs : 0);
  return base + Math.floor(random() * base * jitter);
}

/** 연속 오류 때 다음 간격: 0 → 최소 간격, 그 뒤 두 배씩(상한 30초). */
export const nextErrorDelay = (prev, minMs) => Math.min(Math.max(prev * 2, minMs), BACKOFF_MAX_MS);

const pct = (sorted, p) => (sorted.length ? sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))] : null);

function main() {
  const { values: opts } = parseArgs({
    options: {
      base: { type: "string" },
      out: { type: "string" },
      hold: { type: "string", default: "300" },
      "min-ms": { type: "string", default: "2000" },
      jitter: { type: "string", default: "0.2" },
      "max-sockets": { type: "string", default: "256" },
      "timeout-ms": { type: "string", default: "10000" },
    },
  });
  if (!opts.base || !opts.out) {
    console.error("사용: poll-hold.mjs --base <API base> --out <디렉터리> [--hold 초] [--min-ms 2000] [--jitter 0.2] [--max-sockets 256]");
    process.exit(2);
  }
  const num = {};
  for (const k of ["hold", "min-ms", "jitter", "max-sockets", "timeout-ms"]) {
    num[k] = Number(opts[k]);
    if (!Number.isFinite(num[k]) || num[k] < 0) {
      console.error(`--${k}가 0 이상의 수가 아니다: ${opts[k]}`);
      process.exit(2);
    }
  }
  const BASE = new URL(opts.base.replace(/\/$/, "") + "/");
  const HOLD_MS = num.hold * 1000;
  const MIN_MS = num["min-ms"];
  const JITTER = num.jitter;
  const lib = BASE.protocol === "https:" ? https : http;
  const agent = new lib.Agent({ keepAlive: true, maxSockets: num["max-sockets"] });

  const tokens = new Map(); // token → 상태
  const stats = { polls: 0, byCode: {}, byQueueStatus: {}, errors: 0, timeouts: 0, perSecond: {}, latencies: [] };
  let firstTokenAt = null;
  let stopping = false;
  let inputEnded = false;
  let open = 0; // 끝나지 않은 토큰 수

  const get = (token) =>
    new Promise((resolve) => {
      const started = Date.now();
      const req = lib.get(new URL(`queue/status?token=${encodeURIComponent(token)}`, BASE), { agent, timeout: num["timeout-ms"] }, (res) => {
        let body = "";
        res.setEncoding("utf8");
        res.on("data", (c) => (body += c));
        res.on("end", () => resolve({ code: res.statusCode, body, ms: Date.now() - started }));
      });
      req.on("timeout", () => req.destroy(new Error("timeout")));
      req.on("error", (e) => resolve({ code: 0, error: e.message, ms: Date.now() - started }));
    });

  const schedule = (t, delay) => {
    if (stopping || t.done) return;
    t.timer = setTimeout(() => poll(t), delay);
  };

  const finish = (t, status, at) => {
    t.done = true;
    t.last = status;
    if (status === "ADMITTED") t.admittedSeenAt = at;
    if (status === "EXPIRED") t.expiredAt = at;
    open--;
    if (inputEnded && open === 0) end("모든 토큰이 끝났다");
  };

  async function poll(t) {
    t.timer = null;
    if (stopping || t.done) return;
    const r = await get(t.token);
    if (stopping) return;
    const now = Date.now();
    t.polls++;
    stats.polls++;
    stats.byCode[r.code] = (stats.byCode[r.code] ?? 0) + 1;
    const sec = Math.floor(now / 1000);
    stats.perSecond[sec] = (stats.perSecond[sec] ?? 0) + 1;
    stats.latencies.push(r.ms);
    if (r.code === 0) {
      stats.errors++;
      if (r.error === "timeout") stats.timeouts++;
    }
    if (r.code === 410) return finish(t, "EXPIRED", now);
    let data = null;
    if (r.code === 200) {
      try {
        data = JSON.parse(r.body).data;
      } catch {
        data = null;
      }
    }
    if (data && typeof data.status === "string") {
      stats.byQueueStatus[data.status] = (stats.byQueueStatus[data.status] ?? 0) + 1;
      t.errorDelay = 0;
      t.last = data.status;
      if (data.status === "ADMITTED") return finish(t, "ADMITTED", now);
      if (data.status === "EXPIRED") return finish(t, "EXPIRED", now);
      return schedule(t, nextDelay(data.retryAfterMs, MIN_MS, JITTER));
    }
    t.errorDelay = nextErrorDelay(t.errorDelay, MIN_MS);
    schedule(t, nextDelay(t.errorDelay, MIN_MS, JITTER));
  }

  function end(reason) {
    if (stopping) return;
    stopping = true;
    for (const t of tokens.values()) if (t.timer) clearTimeout(t.timer);
    mkdirSync(opts.out, { recursive: true });
    const lines = [...tokens.values()].map((t) =>
      JSON.stringify({
        ref: ref(t.token), issuedSeenAt: t.issuedSeenAt, firstRetryAfterMs: t.firstRetryAfterMs, polls: t.polls,
        last: t.last ?? null, admittedSeenAt: t.admittedSeenAt ?? null, expiredAt: t.expiredAt ?? null,
      }));
    writeFileSync(`${opts.out}/tokens.jsonl`, lines.join("\n") + (lines.length ? "\n" : ""));
    const lat = stats.latencies.sort((a, b) => a - b);
    const finals = {};
    for (const t of tokens.values()) finals[t.last ?? "NONE"] = (finals[t.last ?? "NONE"] ?? 0) + 1;
    const summary = {
      reason, base: BASE.href, holdSeconds: num.hold, minMs: MIN_MS, jitter: JITTER, maxSockets: num["max-sockets"],
      firstTokenAt: firstTokenAt === null ? null : new Date(firstTokenAt).toISOString(), endedAt: new Date().toISOString(),
      tokens: tokens.size, stillWaitingAtEnd: open, finalStatus: finals,
      polls: stats.polls, byCode: stats.byCode, byQueueStatus: stats.byQueueStatus, errors: stats.errors, timeouts: stats.timeouts,
      latencyMs: { p50: pct(lat, 0.5), p95: pct(lat, 0.95), p99: pct(lat, 0.99), max: lat.at(-1) ?? null },
      perSecond: Object.entries(stats.perSecond).sort((a, b) => a[0] - b[0]).map(([s, n]) => ({ second: new Date(s * 1000).toISOString(), polls: n })),
    };
    writeFileSync(`${opts.out}/summary.json`, JSON.stringify(summary, null, 1));
    console.error(`[poll-hold] ${reason}: 토큰 ${tokens.size}, 조회 ${stats.polls}, 오류 ${stats.errors}`);
    agent.destroy();
    process.exit(0);
  }

  process.on("SIGINT", () => end("SIGINT"));
  process.on("SIGTERM", () => end("SIGTERM"));

  const rl = createInterface({ input: process.stdin, crlfDelay: Infinity });
  rl.on("line", (line) => {
    const m = line.match(/^QTOKEN (\S+)(?: (\d+))?$/);
    if (!m) {
      process.stdout.write(line + "\n");
      return;
    }
    if (stopping || tokens.has(m[1])) return;
    const now = Date.now();
    if (firstTokenAt === null) {
      firstTokenAt = now;
      setTimeout(() => end("hold 시간 끝"), HOLD_MS);
    }
    const retry = m[2] === undefined ? undefined : Number(m[2]);
    const t = { token: m[1], issuedSeenAt: now, firstRetryAfterMs: retry ?? null, polls: 0, errorDelay: 0, done: false, timer: null };
    tokens.set(t.token, t);
    open++;
    schedule(t, nextDelay(retry, MIN_MS, JITTER));
  });
  rl.on("close", () => {
    inputEnded = true;
    if (tokens.size === 0) end("토큰 없음");
    else if (open === 0) end("모든 토큰이 끝났다");
  });
}

// 직접 실행할 때만 돈다(테스트가 순수 함수만 가져다 쓸 수 있게).
const isMain = (() => {
  try {
    return !!process.argv[1] && realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url));
  } catch {
    return false;
  }
})();
if (isMain) main();
