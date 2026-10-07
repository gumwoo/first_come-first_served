#!/usr/bin/env node
// 로컬 스모크용 목 서버: booking-e2e.js와 booking-expect.mjs의 **문법과 동작만** 확인한다. 정합성·용량 검증용이 아니다
// (정합성은 실제 경로에서 check-booking.sh가 DB로 판정한다).
//
// 흉내 내는 것(실제 API와 같은 모양·규칙 — 근거는 각 줄):
//   POST /events/{id}/queue/token        → { data: { status: WAITING, token } }, ADMIT_AFTER_MS 뒤 상태가 ADMITTED
//   GET  /queue/status?token=            → { data: { status } }
//   GET  /events/{id}/seats              → { data: { seats: [{ id, status }] } } (좌석 SEATS석)
//   POST /events/{id}/seats/hold         → 입장 확인(403 QUEUE_NOT_ADMITTED) → 1인 한도(409 MAX_PER_USER_EXCEEDED, PAID + HELD 좌석,
//                                          SeatService·SeatQuotaRepository) → 좌석이 하나라도 AVAILABLE이 아니면 409 SEAT_CONFLICT
//                                          (남은 좌석이 0이면 SOLD_OUT). 성공이면 { holdId }
//   POST /orders {holdId}                → 같은 hold의 활성 주문(PENDING)이 있으면 그대로(OrderService 멱등), 남의 hold 403
//   POST /orders/{id}/payments           → 멱등 키가 있으면 기존 결과(PaymentService), 주문이 PENDING 아니면 409 INVALID_STATE_TRANSITION,
//                                          키가 FAIL로 시작하면 FAILED·주문 PENDING(MockPaymentGateway), 아니면 APPROVED·PAID·좌석 SOLD
//   GET  /_state                         → 내부 상태(테스트용)
//
//   node infra/loadgen/mock-booking-server.mjs --port 18081 [--seats 100] [--admit-after-ms 1000] [--max-per-user 4]
import http from "node:http";
import { randomUUID } from "node:crypto";
import { parseArgs } from "node:util";

const { values: args } = parseArgs({
  options: {
    port: { type: "string", default: "18081" },
    seats: { type: "string", default: "100" },
    "admit-after-ms": { type: "string", default: "1000" },
    "max-per-user": { type: "string", default: "4" },
  },
});
const ADMIT_AFTER_MS = Number(args["admit-after-ms"]);
const MAX_PER_USER = Number(args["max-per-user"]);

const seats = new Map(); // id → status
for (let i = 0; i < Number(args.seats); i++) seats.set(5000 + i, "AVAILABLE");
const queue = new Map(); // token → issuedAt
const holds = new Map(); // id → { userId, seatIds, status }
const orders = new Map(); // id → { userId, holdId, status }
const payments = new Map(); // key → { id, orderId, status }
let seq = 1;

const json = (res, code, body) => res.writeHead(code, { "Content-Type": "application/json" }).end(JSON.stringify(body));
const err = (res, code, c) => json(res, code, { error: { code: c, message: c } });
const userOf = (req) => {
  const m = /^Bearer ([^.]+)\.([^.]+)\./.exec(req.headers.authorization || "");
  if (!m) return null;
  try { return Number(JSON.parse(Buffer.from(m[2], "base64url").toString()).sub); } catch { return null; }
};
const activeSeats = (userId) => {
  const s = new Set();
  for (const h of holds.values()) if (h.userId === userId && h.status === "HELD") h.seatIds.forEach((x) => s.add(x));
  for (const o of orders.values()) if (o.userId === userId && o.status === "PAID") holds.get(o.holdId).seatIds.forEach((x) => s.add(x));
  return s.size;
};

function route(req, res, body) {
  const url = new URL(req.url, "http://mock");
  const p = url.pathname;
  let m;
  if (req.method === "GET" && p === "/_state") {
    return json(res, 200, { seats: Object.fromEntries(seats), holds: Object.fromEntries(holds), orders: Object.fromEntries(orders), payments: Object.fromEntries(payments) });
  }
  if (req.method === "GET" && (m = p.match(/^\/events\/(\d+)\/seats$/))) {
    return json(res, 200, { data: { eventId: Number(m[1]), seats: [...seats].map(([id, status]) => ({ id, status })) } });
  }
  if (req.method === "GET" && p === "/queue/status") {
    const at = queue.get(url.searchParams.get("token") ?? "");
    if (at === undefined) return err(res, 410, "QUEUE_EXPIRED");
    return json(res, 200, { data: { status: Date.now() - at >= ADMIT_AFTER_MS ? "ADMITTED" : "WAITING" } });
  }
  const userId = userOf(req);
  if (userId == null) return res.writeHead(401).end();
  if (req.method === "POST" && p.match(/^\/events\/\d+\/queue\/token$/)) {
    const token = randomUUID();
    queue.set(token, Date.now());
    return json(res, 200, { data: { status: "WAITING", token, rank: queue.size } });
  }
  if (req.method === "POST" && p.match(/^\/events\/\d+\/seats\/hold$/)) {
    const at = queue.get(body.queueToken);
    if (at === undefined || Date.now() - at < ADMIT_AFTER_MS) return err(res, 403, "QUEUE_NOT_ADMITTED");
    const ids = body.seatIds || [];
    if (activeSeats(userId) + ids.length > MAX_PER_USER) return err(res, 409, "MAX_PER_USER_EXCEEDED");
    if (ids.some((id) => seats.get(id) !== "AVAILABLE")) {
      const left = [...seats.values()].filter((s) => s === "AVAILABLE").length;
      return err(res, 409, left === 0 ? "SOLD_OUT" : "SEAT_CONFLICT");
    }
    ids.forEach((id) => seats.set(id, "HELD"));
    const holdId = seq++;
    holds.set(holdId, { userId, seatIds: ids, status: "HELD" });
    return json(res, 200, { data: { holdId, seatIds: ids } });
  }
  if (req.method === "POST" && p === "/orders") {
    const h = holds.get(body.holdId);
    if (!h) return err(res, 404, "NOT_FOUND");
    if (h.userId !== userId) return err(res, 403, "FORBIDDEN");
    for (const [id, o] of orders) if (o.holdId === body.holdId && o.status === "PENDING") return json(res, 200, { data: { orderId: id, status: o.status } });
    if (h.status !== "HELD") return err(res, 409, "INVALID_STATE_TRANSITION");
    const orderId = seq++;
    orders.set(orderId, { userId, holdId: body.holdId, status: "PENDING" });
    return json(res, 200, { data: { orderId, status: "PENDING" } });
  }
  if (req.method === "POST" && (m = p.match(/^\/orders\/(\d+)\/payments$/))) {
    const orderId = Number(m[1]);
    const o = orders.get(orderId);
    if (!o) return err(res, 404, "NOT_FOUND");
    if (o.userId !== userId) return err(res, 403, "FORBIDDEN");
    const k = body.idempotencyKey;
    if (!k) return err(res, 400, "VALIDATION_ERROR");
    const prev = payments.get(k);
    if (prev) return json(res, 200, { data: { paymentId: prev.id, paymentStatus: prev.status, orderStatus: orders.get(prev.orderId).status } });
    if (o.status !== "PENDING") return err(res, 409, "INVALID_STATE_TRANSITION");
    const pay = { id: seq++, orderId, status: k.startsWith("FAIL") ? "FAILED" : "APPROVED" };
    payments.set(k, pay);
    if (pay.status === "APPROVED") {
      o.status = "PAID";
      const h = holds.get(o.holdId);
      h.status = "CONVERTED";
      h.seatIds.forEach((id) => seats.set(id, "SOLD"));
    }
    return json(res, 200, { data: { paymentId: pay.id, paymentStatus: pay.status, orderStatus: o.status } });
  }
  return err(res, 404, "NOT_FOUND");
}

http.createServer((req, res) => {
  let raw = "";
  req.on("data", (c) => (raw += c));
  req.on("end", () => {
    let body = {};
    try { body = raw ? JSON.parse(raw) : {}; } catch { return err(res, 400, "VALIDATION_ERROR"); }
    route(req, res, body);
  });
}).listen(Number(args.port), "127.0.0.1", () => console.error(`mock-booking http://127.0.0.1:${args.port} seats ${args.seats}`));
