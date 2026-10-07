#!/usr/bin/env node
// 로컬 스모크용 목 서버: 발생기 스크립트의 **문법과 동작만** 확인한다. 용량 검증용이 아니다
// (용량은 측정 세션의 단계 2에서 실제 경로로 잰다, loadtest-100k-plan §6).
//
// 흉내 내는 것(실제 API와 같은 모양):
//   POST /events/{id}/queue/token  → { data: { status, token, rank, retryAfterMs } }  (Authorization: Bearer 필요)
//   GET  /queue/status?token=      → { data: { status, rank, retryAfterMs } }. 발급 뒤 ADMIT_AFTER_MS가 지나면 ADMITTED,
//                                    모르는 토큰은 410(QUEUE_EXPIRED). 대기열 SSE는 제거됐다(ADR-023 §2).
// 앞 CAPACITY명은 발급 즉시 ADMITTED, 나머지는 WAITING으로 응답한다.
//
//   node infra/loadgen/mock-queue-server.mjs --port 18080 --capacity 3 --admit-after-ms 500 [--retry-after-ms 2000]
import http from "node:http";
import { randomUUID } from "node:crypto";
import { parseArgs } from "node:util";

const { values: args } = parseArgs({
  options: {
    port: { type: "string", default: "18080" },
    capacity: { type: "string", default: "100" },
    "admit-after-ms": { type: "string", default: "1000" },
    "retry-after-ms": { type: "string", default: "2000" },
  },
});
const CAPACITY = Number(args.capacity);
const ADMIT_AFTER_MS = Number(args["admit-after-ms"]);
const RETRY_AFTER_MS = Number(args["retry-after-ms"]);

let rank = 0;
const stats = { entries: 0, unauthorized: 0, statusPolls: 0 };
const issuedAt = new Map(); // token → 발급 시각(ms)

const json = (res, code, body) => res.writeHead(code, { "Content-Type": "application/json" }).end(JSON.stringify(body));

const server = http.createServer((req, res) => {
  const url = new URL(req.url, "http://mock");
  const entry = req.method === "POST" && url.pathname.match(/^\/events\/(\d+)\/queue\/token$/);
  if (entry) {
    if (!/^Bearer \S+/.test(req.headers.authorization || "")) {
      stats.unauthorized++;
      res.writeHead(401).end();
      return;
    }
    stats.entries++;
    const r = rank++;
    const token = randomUUID();
    issuedAt.set(token, r < CAPACITY ? -Infinity : Date.now());
    json(res, 200, { data: { status: r < CAPACITY ? "ADMITTED" : "WAITING", token, rank: r, retryAfterMs: r < CAPACITY ? 0 : RETRY_AFTER_MS } });
    return;
  }
  if (req.method === "GET" && url.pathname === "/queue/status") {
    stats.statusPolls++;
    const at = issuedAt.get(url.searchParams.get("token") ?? "");
    if (at === undefined) {
      json(res, 410, { error: { code: "QUEUE_EXPIRED", message: "대기시간이 만료되었습니다." } });
      return;
    }
    const admitted = Date.now() - at >= ADMIT_AFTER_MS;
    json(res, 200, { data: { status: admitted ? "ADMITTED" : "WAITING", rank: admitted ? 0 : 1, total: 1, etaSeconds: 0, retryAfterMs: admitted ? 0 : RETRY_AFTER_MS } });
    return;
  }
  if (url.pathname === "/__stats") {
    json(res, 200, stats);
    return;
  }
  res.writeHead(404).end();
});
server.listen(Number(args.port), () => console.error(`[mock] :${args.port} capacity=${CAPACITY}`));
