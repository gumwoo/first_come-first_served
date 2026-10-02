#!/usr/bin/env node
// 로컬 스모크용 목 서버: 발생기 스크립트의 **문법과 동작만** 확인한다. 용량 검증용이 아니다
// (용량은 측정 세션의 단계 2에서 실제 경로로 잰다, loadtest-100k-plan §6).
//
// 흉내 내는 것(실제 API와 같은 모양):
//   POST /events/{id}/queue/token  → { data: { status, token, rank } }  (Authorization: Bearer 필요)
//   GET  /sse/queue/{token}        → text/event-stream. 연결 직후 코멘트 프레임, ADMIT_AFTER_MS 뒤 queue.admitted
// 앞 CAPACITY명은 ADMITTED, 나머지는 WAITING으로 응답한다.
//
//   node infra/loadgen/mock-queue-server.mjs --port 18080 --capacity 3 --admit-after-ms 500
import http from "node:http";
import { randomUUID } from "node:crypto";
import { parseArgs } from "node:util";

const { values: args } = parseArgs({
  options: {
    port: { type: "string", default: "18080" },
    capacity: { type: "string", default: "100" },
    "admit-after-ms": { type: "string", default: "1000" },
  },
});
const CAPACITY = Number(args.capacity);
const ADMIT_AFTER_MS = Number(args["admit-after-ms"]);

let rank = 0;
const stats = { entries: 0, unauthorized: 0, sseOpened: 0, sseClosed: 0 };

const server = http.createServer((req, res) => {
  const entry = req.method === "POST" && req.url.match(/^\/events\/(\d+)\/queue\/token$/);
  if (entry) {
    if (!/^Bearer \S+/.test(req.headers.authorization || "")) {
      stats.unauthorized++;
      res.writeHead(401).end();
      return;
    }
    stats.entries++;
    const r = rank++;
    const body = { data: { status: r < CAPACITY ? "ADMITTED" : "WAITING", token: randomUUID(), rank: r } };
    res.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify(body));
    return;
  }
  const sse = req.method === "GET" && req.url.match(/^\/sse\/queue\/([^/?]+)$/);
  if (sse) {
    stats.sseOpened++;
    res.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-cache" });
    res.write(": open\n\n");
    const t = setTimeout(() => res.write(`event: queue.admitted\ndata: {"redirect":"/events/1/seats"}\n\n`), ADMIT_AFTER_MS);
    req.on("close", () => {
      clearTimeout(t);
      stats.sseClosed++;
    });
    return;
  }
  if (req.url === "/__stats") {
    res.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify(stats));
    return;
  }
  res.writeHead(404).end();
});
server.listen(Number(args.port), () => console.error(`[mock] :${args.port} capacity=${CAPACITY}`));
