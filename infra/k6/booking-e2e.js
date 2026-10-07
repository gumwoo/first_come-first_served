// Downstream E2E(입장자) 시험: 대기열 진입 → ADMITTED → 좌석 선점(hold) → 주문 → 결제를 사용자 100명이 동시에 한다.
// 정합성 시험이다(성능 시험이 아니다 — loadtest-plan Phase 3). 판정은 이 스크립트가 아니라 결과를 받은
// scripts/loadtest/booking-expect.mjs(클라이언트 기대값)와 check-booking.sh(DB 기대값)가 한다. 이 스크립트는 기록만 한다.
//
// 역할(VU 순서대로 — 사용자 100명·좌석 84석. 공연 좌석이 100석이라 이 배분으로 맞춘다):
//   A 정상 30      : 자기 좌석 1석 hold → 주문 → 결제
//   B 경합 40      : 좌석 4석을 10명씩 같은 좌석 hold → 이긴 사람만 주문 → 결제
//   C 더블클릭 10  : 같은 좌석 hold 2회 동시 → 같은 hold 주문 2회 동시 → 같은 멱등 키 결제 3회 동시
//   D 실패 후 재시도 10 : 1석 hold → 주문 → FAIL- 키 결제(mock 게이트웨이가 거절) → 새 키로 재결제
//   E 실패 후 포기 5    : 1석 hold → 주문 → FAIL- 키 결제 후 중단(hold·주문은 TTL 뒤 만료 회수로 정리된다)
//   F 1인 한도 5   : 4석 hold → 주문 → 결제 → 다른 1석 hold(한도 4 초과)
//
// 흐름: 각 VU가 T0 − ENTRY_LEAD_SECONDS에 대기열에 들어가 ADMITTED가 될 때까지 상태를 묻고, T0(START_AT)에 함께 예매를
// 시작한다(경합이 실제로 겹치게). 결과는 VU마다 "E2E {json}" 한 줄로 낸다(--log-format=raw). 토큰(JWT·대기열 토큰)은
// 자격증명이라 남기지 않는다 — userId는 JWT의 sub에서 읽는다.
//
//   k6 run --log-format=raw -e K6_BASE_URL=https://flow-ticket.com/api -e EVENT_ID=1826 -e USERS=tokens.json \
//     -e START_AT=2026-10-07T12:00:00Z infra/k6/booking-e2e.js
//   USER_OFFSET: 토큰 파일에서 쓸 첫 사용자 인덱스(기본 0). ENTRY_LEAD_SECONDS: T0 몇 초 전에 진입할지(기본 20).
import http from "k6/http";
import { sleep } from "k6";
import exec from "k6/execution";
import encoding from "k6/encoding";
import crypto from "k6/crypto";

const BASE = __ENV.K6_BASE_URL || "http://localhost:8080";
const EVENT_ID = __ENV.EVENT_ID;
const USERS = JSON.parse(open(__ENV.USERS || "./tokens.json"));
const USER_OFFSET = Number(__ENV.USER_OFFSET || 0);
const ENTRY_LEAD_SECONDS = Number(__ENV.ENTRY_LEAD_SECONDS || 20);
const START_AT = __ENV.START_AT ? Date.parse(__ENV.START_AT) : NaN;

export const ROLES = [["A", 30], ["B", 40], ["C", 10], ["D", 10], ["E", 5], ["F", 5]];
const B_SEATS = 4;
const F_SEATS = 4; // seat.max-per-user 기본값과 같다
const N = ROLES.reduce((s, [, n]) => s + n, 0);

if (!EVENT_ID) throw new Error("EVENT_ID가 필요하다");
if (!Number.isFinite(START_AT)) throw new Error(`START_AT(UTC ISO)이 필요하다: ${__ENV.START_AT}`);
if (!(ENTRY_LEAD_SECONDS >= 5)) throw new Error(`ENTRY_LEAD_SECONDS는 5 이상이다: ${__ENV.ENTRY_LEAD_SECONDS}`);
if (USERS.length < USER_OFFSET + N) throw new Error(`토큰이 ${USERS.length}개다 — USER_OFFSET ${USER_OFFSET} + ${N}명이 필요하다`);
if (START_AT - Date.now() > 3600 * 1000) throw new Error("START_AT이 1시간보다 멀다");
// maxDuration은 시나리오 시작부터 센다 — T0까지 기다리는 시간 + 예매(여유 300초)로 잡아 VU가 T0 전에 끊기지 않게 한다.
const MAX_SECONDS = Math.ceil(Math.max(0, START_AT - Date.now()) / 1000) + 300;

export const options = {
  scenarios: {
    // VU 하나 = 사용자 하나, 한 번씩. shared-iterations면 한 VU가 두 번 돌아 역할·사용자 대응이 깨진다.
    e2e: { executor: "per-vu-iterations", vus: N, iterations: 1, maxDuration: `${MAX_SECONDS}s` },
  },
  setupTimeout: "60s",
  // 판정은 결과 파일로 한다. 기대 응답(409·410 포함)을 실패로 세지 않게 상태 코드 판단은 하지 않는다.
  summaryTrendStats: ["avg", "p(50)", "p(95)", "max"],
};

/** 역할 배분과 좌석 배정. 좌석은 id 순서로 겹치지 않게 나눈다(B만 4석을 10명씩 공유). */
export function plan(seatIds) {
  const seats = [...seatIds].sort((a, b) => a - b);
  let cur = 0;
  const take = (k) => { const s = seats.slice(cur, cur + k); cur += k; return s; };
  const out = [];
  const bSeats = [];
  for (const [role, n] of ROLES) {
    if (role === "B") bSeats.push(...take(B_SEATS));
    for (let i = 0; i < n; i++) {
      if (role === "B") out.push({ role, seatIds: [bSeats[i % B_SEATS]] });
      else if (role === "F") out.push({ role, seatIds: take(F_SEATS), extraSeatId: null });
      else out.push({ role, seatIds: take(1) });
    }
  }
  for (const p of out) if (p.role === "F") p.extraSeatId = take(1)[0];
  if (out.some((p) => p.seatIds.some((s) => s === undefined) || (p.role === "F" && p.extraSeatId === undefined))) {
    throw new Error(`좌석이 모자란다: ${seats.length}석`);
  }
  return out;
}

export function setup() {
  const res = http.get(`${BASE}/events/${EVENT_ID}/seats`);
  if (res.status !== 200) throw new Error(`좌석맵 조회 실패: ${res.status}`);
  const seats = res.json("data.seats") || [];
  const notAvail = seats.filter((s) => s.status !== "AVAILABLE").length;
  // 사전 상태: 모든 좌석이 AVAILABLE인 새 공연이어야 DB 기대값(좌석 상태 수)이 성립한다.
  if (notAvail > 0) throw new Error(`AVAILABLE이 아닌 좌석이 ${notAvail}석 있다 — 새 공연으로 한다`);
  if (Date.now() > START_AT - ENTRY_LEAD_SECONDS * 1000) throw new Error("START_AT − ENTRY_LEAD_SECONDS가 이미 지났다");
  return { plan: plan(seats.map((s) => s.id)), seatCount: seats.length };
}

function randToken(n) {
  return encoding.b64encode(crypto.randomBytes(n), "rawurl").replace(/[^A-Za-z0-9]/g, "").slice(0, n);
}

function userIdOf(jwt) {
  try { return Number(JSON.parse(encoding.b64decode(jwt.split(".")[1], "rawurl", "s")).sub); } catch { return null; }
}

function codeOf(res) {
  try { return res.json("error.code") || null; } catch { return null; }
}

// 응답 기록 한 줄. 응답이 없으면(status 0) k6 error_code를 남긴다(연결 오류·시간 초과 구분).
function stepOf(step, res) {
  const o = { step, status: res.status, code: codeOf(res), ms: Math.round(res.timings.duration) };
  if (res.status === 0) o.err = res.error_code;
  return o;
}

function dataOf(res) {
  try { return res.json("data") || null; } catch { return null; }
}

export default function (data) {
  const vu = exec.vu.idInTest; // 1..N
  const me = data.plan[vu - 1];
  const user = USERS[USER_OFFSET + vu - 1];
  const auth = { "Content-Type": "application/json", Authorization: `Bearer ${user.t}` };
  const steps = [];
  const rec = { vu, userId: userIdOf(user.t), role: me.role, seatIds: me.seatIds, extraSeatId: me.extraSeatId ?? null,
    admitted: false, admittedAt: null, holdId: null, orderId: null, payments: [], steps };
  const call = (step, method, url, body) => {
    const res = http.request(method, url, body === undefined ? null : JSON.stringify(body), { headers: auth, tags: { step } });
    steps.push(stepOf(step, res));
    return res;
  };
  const batch = (step, reqs) => {
    const rs = http.batch(reqs.map(([method, url, body]) => ({ method, url, body: JSON.stringify(body),
      params: { headers: auth, tags: { step } } })));
    for (const res of rs) steps.push(stepOf(step, res));
    return rs;
  };
  const pay = (step, key) => {
    const res = call(step, "POST", `${BASE}/orders/${rec.orderId}/payments`, { method: "card", idempotencyKey: key });
    const d = dataOf(res) || {};
    rec.payments.push({ step, key, status: res.status, paymentId: d.paymentId ?? null, paymentStatus: d.paymentStatus ?? null, orderStatus: d.orderStatus ?? null });
    return res;
  };
  const key = (prefix) => `${prefix}e2e-${EVENT_ID}-${vu}-${randToken(12)}`;
  const holdUrl = `${BASE}/events/${EVENT_ID}/seats/hold`;

  // 1) 진입 → ADMITTED. 공연을 리셋한 뒤 100명이면 정원 100이라 승격 주기(1.5초) 한두 번 안에 모두 입장한다.
  sleep(Math.max(0, (START_AT - ENTRY_LEAD_SECONDS * 1000 - Date.now()) / 1000));
  const entry = call("queue_entry", "POST", `${BASE}/events/${EVENT_ID}/queue/token`);
  const qd = dataOf(entry) || {};
  const queueToken = qd.token;
  let status = qd.status;
  while (queueToken && status !== "ADMITTED" && Date.now() < START_AT - 1000) {
    sleep(1);
    const s = call("queue_status", "GET", `${BASE}/queue/status?token=${encodeURIComponent(queueToken)}`);
    status = (dataOf(s) || {}).status;
  }
  rec.admitted = status === "ADMITTED";
  rec.admittedAt = rec.admitted ? new Date().toISOString() : null;
  sleep(Math.max(0, (START_AT - Date.now()) / 1000));
  rec.t0LagMs = Date.now() - START_AT;
  if (!rec.admitted) { console.log(`E2E ${JSON.stringify(rec)}`); return; }

  // 2) 역할별 예매
  if (me.role === "C") {
    const hs = batch("hold", [["POST", holdUrl, { seatIds: me.seatIds, queueToken }], ["POST", holdUrl, { seatIds: me.seatIds, queueToken }]]);
    const ok = hs.find((r) => r.status === 200);
    rec.holdId = ok ? (dataOf(ok) || {}).holdId ?? null : null;
    if (rec.holdId == null) { console.log(`E2E ${JSON.stringify(rec)}`); return; }
    const os = batch("order", [["POST", `${BASE}/orders`, { holdId: rec.holdId }], ["POST", `${BASE}/orders`, { holdId: rec.holdId }]]);
    rec.orderIds = os.map((r) => (dataOf(r) || {}).orderId ?? null);
    rec.orderId = rec.orderIds.find((x) => x != null) ?? null;
    if (rec.orderId == null) { console.log(`E2E ${JSON.stringify(rec)}`); return; }
    const k = key("");
    const ps = batch("pay", [0, 1, 2].map(() => ["POST", `${BASE}/orders/${rec.orderId}/payments`, { method: "card", idempotencyKey: k }]));
    for (const r of ps) {
      const d = dataOf(r) || {};
      rec.payments.push({ step: "pay", key: k, status: r.status, paymentId: d.paymentId ?? null, paymentStatus: d.paymentStatus ?? null, orderStatus: d.orderStatus ?? null });
    }
    console.log(`E2E ${JSON.stringify(rec)}`);
    return;
  }

  const h = call("hold", "POST", holdUrl, { seatIds: me.seatIds, queueToken });
  rec.holdId = h.status === 200 ? (dataOf(h) || {}).holdId ?? null : null;
  if (rec.holdId == null) { console.log(`E2E ${JSON.stringify(rec)}`); return; } // B 패자 등
  const o = call("order", "POST", `${BASE}/orders`, { holdId: rec.holdId });
  rec.orderId = o.status === 200 ? (dataOf(o) || {}).orderId ?? null : null;
  if (rec.orderId == null) { console.log(`E2E ${JSON.stringify(rec)}`); return; }

  if (me.role === "D" || me.role === "E") {
    pay("pay_fail", key("FAIL-"));
    if (me.role === "D") pay("pay", key(""));
  } else {
    pay("pay", key(""));
  }
  if (me.role === "F") call("hold_over_quota", "POST", holdUrl, { seatIds: [me.extraSeatId], queueToken });
  console.log(`E2E ${JSON.stringify(rec)}`);
}
