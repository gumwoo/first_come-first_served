#!/usr/bin/env node
// Downstream E2E(입장자) 시험 판정 — booking-e2e.js가 VU마다 낸 "E2E {json}" 줄을 읽는다.
//
//   node scripts/loadtest/booking-expect.mjs client <k6 로그 또는 results.jsonl>
//     클라이언트 기대값(역할별 응답)을 판정해 JSON을 낸다. 종료 코드: 통과 0, 위반 1, 무효·판정 불가 2.
//   node scripts/loadtest/booking-expect.mjs sql --event <id> <같은 파일>
//     DB 기대값 SQL을 낸다(check-booking.sh가 클러스터 안 psql로 실행). 줄마다 "<검사>,<위반 수>"를 낸다.
//
// 기대값은 역할 설계(사전 등록)에서 나온다 — 결과에서 거꾸로 만들지 않는다. 예외는 B(경합)의 승자다: 누가 이길지는
// 정해져 있지 않으므로 좌석마다 "정확히 한 명"만 본다.
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

export const EXPECT_COUNTS = { A: 30, B: 40, C: 10, D: 10, E: 5, F: 5 };
const B_SEATS = 4;

/** k6 raw 로그(다른 줄 섞임)나 JSONL에서 결과 줄만 읽는다. */
export function parseResults(text) {
  const out = [];
  let malformed = 0;
  for (const line of text.split(/\r?\n/)) {
    const i = line.indexOf("E2E {");
    if (i < 0) continue;
    try { out.push(JSON.parse(line.slice(i + 4))); } catch { malformed++; }
  }
  return { records: out, malformed };
}

const stepsOf = (r, name) => r.steps.filter((s) => s.step === name);
const is = (s, status, code) => s && s.status === status && (code === undefined || s.code === code);
const paid = (p) => p && p.status === 200 && p.paymentStatus === "APPROVED" && p.orderStatus === "PAID";
const failed = (p) => p && p.status === 200 && p.paymentStatus === "FAILED" && p.orderStatus === "PENDING";

/** 역할 하나의 기대 응답. 어긋난 이유 목록을 낸다(빈 배열이면 통과). B는 승자·패자를 여기서 가르고 좌석 단위는 judgeClient가 본다. */
export function roleViolations(r) {
  const v = [];
  const hold = stepsOf(r, "hold");
  const order = stepsOf(r, "order");
  const pay = r.payments.filter((p) => p.step === "pay");
  const payFail = r.payments.filter((p) => p.step === "pay_fail");
  const single = () => {
    if (!(hold.length === 1 && is(hold[0], 200))) v.push("hold 200 아님");
    if (!(order.length === 1 && is(order[0], 200))) v.push("order 200 아님");
  };
  switch (r.role) {
    case "A":
      single();
      if (!(pay.length === 1 && paid(pay[0]))) v.push("결제 APPROVED·PAID 아님");
      break;
    case "B":
      if (hold.length !== 1) v.push("hold 요청 수 1 아님");
      else if (is(hold[0], 200)) {
        if (!(order.length === 1 && is(order[0], 200))) v.push("승자 order 200 아님");
        if (!(pay.length === 1 && paid(pay[0]))) v.push("승자 결제 APPROVED·PAID 아님");
      } else if (!is(hold[0], 409, "SEAT_CONFLICT")) v.push(`패자 hold가 409 SEAT_CONFLICT 아님(${hold[0].status} ${hold[0].code})`);
      else if (order.length || r.payments.length) v.push("패자가 주문·결제했다");
      break;
    case "C": {
      const ok = hold.filter((s) => is(s, 200)).length;
      const conflict = hold.filter((s) => is(s, 409, "SEAT_CONFLICT")).length;
      if (!(hold.length === 2 && ok === 1 && conflict === 1)) v.push(`동시 hold 2회가 200 1·409 SEAT_CONFLICT 1 아님(${hold.map((s) => s.status + (s.code ? " " + s.code : "")).join(", ")})`);
      if (!(order.length === 2 && order.every((s) => is(s, 200)))) v.push("동시 주문 2회가 모두 200 아님");
      const ids = new Set(r.orderIds || []);
      if (!(ids.size === 1 && !ids.has(null))) v.push(`동시 주문의 orderId가 하나가 아님(${[...ids].join(",")})`);
      const pids = new Set(pay.map((p) => p.paymentId));
      if (!(pay.length === 3 && pay.every(paid))) v.push(`동시 결제 3회가 모두 200 APPROVED·PAID 아님(${pay.map((p) => p.status + " " + p.paymentStatus).join(", ")})`);
      if (!(pids.size === 1 && !pids.has(null))) v.push("동시 결제의 paymentId가 하나가 아님");
      break;
    }
    case "D":
      single();
      if (!(payFail.length === 1 && failed(payFail[0]))) v.push("FAIL 키 결제가 FAILED·PENDING 아님");
      if (!(pay.length === 1 && paid(pay[0]))) v.push("재결제 APPROVED·PAID 아님");
      if (payFail[0] && pay[0] && payFail[0].paymentId === pay[0].paymentId) v.push("재결제가 실패 결제와 같은 paymentId");
      break;
    case "E":
      single();
      if (!(payFail.length === 1 && failed(payFail[0]))) v.push("FAIL 키 결제가 FAILED·PENDING 아님");
      if (pay.length) v.push("포기한 사용자가 재결제했다");
      break;
    case "F": {
      single();
      if (!(pay.length === 1 && paid(pay[0]))) v.push("결제 APPROVED·PAID 아님");
      const over = stepsOf(r, "hold_over_quota");
      if (!(over.length === 1 && is(over[0], 409, "MAX_PER_USER_EXCEEDED"))) v.push(`한도 초과 hold가 409 MAX_PER_USER_EXCEEDED 아님(${over.map((s) => s.status + " " + s.code).join(", ")})`);
      break;
    }
    default:
      v.push(`모르는 역할 ${r.role}`);
  }
  return v;
}

function pct(xs, p) {
  if (!xs.length) return null;
  const s = [...xs].sort((a, b) => a - b);
  return s[Math.min(s.length - 1, Math.ceil((p / 100) * s.length) - 1)];
}

/** 클라이언트 기대값 판정. */
export function judgeClient(records, malformed = 0) {
  const invalid = [];
  const violations = [];
  if (malformed) invalid.push(`깨진 결과 줄 ${malformed}`);
  const total = Object.values(EXPECT_COUNTS).reduce((a, b) => a + b, 0);
  if (records.length !== total) invalid.push(`결과 ${records.length}명(기대 ${total}) — 발생기가 중간에 멈췄거나 덜 회수됐다`);
  const byRole = {};
  for (const r of records) (byRole[r.role] ??= []).push(r);
  for (const [role, n] of Object.entries(EXPECT_COUNTS)) {
    if ((byRole[role] || []).length !== n) invalid.push(`역할 ${role} ${(byRole[role] || []).length}명(기대 ${n})`);
  }
  const users = new Set(records.map((r) => r.userId));
  if (users.size !== records.length || users.has(null)) invalid.push("userId가 겹치거나 없다 — 사용자 하나가 역할 둘을 맡았다");
  const notAdmitted = records.filter((r) => !r.admitted).length;
  if (notAdmitted) invalid.push(`T0까지 ADMITTED가 아닌 사용자 ${notAdmitted}`);

  const allSteps = records.flatMap((r) => r.steps.map((s) => ({ ...s, vu: r.vu })));
  const s5xx = allSteps.filter((s) => s.status >= 500 || s.status === 0);
  if (s5xx.length) violations.push(`5xx·응답 없음 ${s5xx.length}건: ${s5xx.slice(0, 5).map((s) => `vu${s.vu} ${s.step} ${s.status}`).join(", ")}`);
  const s401 = allSteps.filter((s) => s.status === 401).length;
  if (s401) invalid.push(`401 ${s401}건(토큰 만료·무효)`);

  for (const r of records) {
    if (!r.admitted) continue;
    for (const why of roleViolations(r)) violations.push(`vu${r.vu} ${r.role}: ${why}`);
  }
  // B: 좌석마다 hold 200이 정확히 한 명
  const bWinners = {};
  for (const r of byRole.B || []) {
    const k = r.seatIds.join(",");
    bWinners[k] ??= 0;
    if (stepsOf(r, "hold").some((s) => s.status === 200)) bWinners[k]++;
  }
  if (Object.keys(bWinners).length !== B_SEATS) invalid.push(`B 좌석 ${Object.keys(bWinners).length}석(기대 ${B_SEATS})`);
  for (const [seat, w] of Object.entries(bWinners)) if (w !== 1) violations.push(`B 좌석 ${seat}: hold 성공 ${w}명(기대 정확히 1)`);

  const latency = {};
  for (const s of allSteps) (latency[s.step] ??= []).push(s.ms);
  for (const k of Object.keys(latency)) {
    const xs = latency[k];
    latency[k] = { n: xs.length, p50: pct(xs, 50), p95: pct(xs, 95), max: Math.max(...xs) };
  }
  const lags = records.map((r) => r.t0LagMs).filter(Number.isFinite);
  const statusCounts = {};
  for (const s of allSteps) statusCounts[`${s.step} ${s.status}${s.code ? " " + s.code : ""}`] = (statusCounts[`${s.step} ${s.status}${s.code ? " " + s.code : ""}`] || 0) + 1;
  const verdict = invalid.length ? "무효" : violations.length ? "위반" : "통과";
  return { verdict, invalid, violations, users: records.length, bWinners, statusCounts, latencyMs: latency,
    t0LagMs: lags.length ? { min: Math.min(...lags), max: Math.max(...lags) } : null };
}

const int = (x) => {
  if (!Number.isSafeInteger(x)) throw new Error(`정수가 아니다: ${x}`);
  return String(x);
};

/** DB 기대값 SQL. 좌석·역할 기대값은 역할 설계에서 나온다(B 승자만 결과를 보지 않고 "좌석마다 정확히 한 명"). */
export function buildSql(records, eventId) {
  const ev = int(Number(eventId));
  const soldRoles = new Set(["A", "B", "C", "D", "F"]);
  const sold = [...new Set(records.filter((r) => soldRoles.has(r.role)).flatMap((r) => r.seatIds))];
  const bSeats = [...new Set(records.filter((r) => r.role === "B").flatMap((r) => r.seatIds))];
  const expUsers = records.map((r) => `(${int(r.userId)}, '${r.role.replace(/[^A-F]/g, "")}')`);
  if (!sold.length || !bSeats.length || !expUsers.length) throw new Error("결과가 비었다");
  const SOLD = `(VALUES ${sold.map((s) => `(${int(s)}::bigint)`).join(", ")}) AS sold(seat_id)`;
  const BSEAT = `(VALUES ${bSeats.map((s) => `(${int(s)}::bigint)`).join(", ")}) AS bseat(seat_id)`;
  const EXP = `(VALUES ${expUsers.join(", ")}) AS exp(user_id, role)`;
  return `-- booking-expect.mjs sql: event ${ev}, 사용자 ${records.length}명. 줄마다 "<검사>,<위반 수>".
-- 0) 사전 조건: 이 공연에 이번 사용자 밖의 주문이 없어야 기대값이 성립한다(새 공연).
SELECT 'orders_by_other_users', count(*) FROM orders o WHERE o.event_id = ${ev}
  AND o.user_id NOT IN (SELECT user_id FROM ${EXP});
-- 1) 좌석 최종 상태: 판매 대상(A·B·C·D·F)은 SOLD, 나머지(E 회수·F 초과분·미사용)는 AVAILABLE.
SELECT 'seat_status_mismatch', count(*) FROM seats s WHERE s.event_id = ${ev}
  AND ((s.id IN (SELECT seat_id FROM ${SOLD}) AND s.status <> 'SOLD')
    OR (s.id NOT IN (SELECT seat_id FROM ${SOLD}) AND s.status <> 'AVAILABLE'));
-- 2) 만료 회수 뒤 HELD hold가 남지 않았다.
SELECT 'holds_still_held', count(*) FROM seat_holds h WHERE h.event_id = ${ev} AND h.status = 'HELD';
-- 3) B 경합 좌석마다 hold 항목이 정확히 하나(패자 hold는 롤백돼 행이 없어야 한다).
SELECT 'b_seat_hold_items_not_1', count(*) FROM ${BSEAT}
  WHERE (SELECT count(*) FROM seat_hold_items i JOIN seat_holds h ON h.id = i.hold_id
         WHERE h.event_id = ${ev} AND i.seat_id = bseat.seat_id) <> 1;
-- 4) PAID 주문 수 = A 30 + B 4 + C 10 + D 10 + F 5 = 59
SELECT 'paid_orders_not_59', abs((SELECT count(*) FROM orders o WHERE o.event_id = ${ev} AND o.status = 'PAID') - 59);
-- 5) 역할별 주문: A·C·D·F는 주문 정확히 1(PAID), E는 정확히 1(EXPIRED). B는 사용자 합계 4(전부 PAID).
SELECT 'orders_per_user_role', count(*) FROM ${EXP}
  WHERE exp.role IN ('A','C','D','E','F') AND NOT (
    (SELECT count(*) FROM orders o WHERE o.event_id = ${ev} AND o.user_id = exp.user_id) = 1
    AND (SELECT count(*) FROM orders o WHERE o.event_id = ${ev} AND o.user_id = exp.user_id
         AND o.status = CASE WHEN exp.role = 'E' THEN 'EXPIRED' ELSE 'PAID' END) = 1);
SELECT 'b_orders_not_4_paid', CASE WHEN
    (SELECT count(*) FROM orders o JOIN ${EXP} ON exp.user_id = o.user_id AND exp.role = 'B' WHERE o.event_id = ${ev}) = 4
    AND (SELECT count(*) FROM orders o JOIN ${EXP} ON exp.user_id = o.user_id AND exp.role = 'B' WHERE o.event_id = ${ev} AND o.status = 'PAID') = 4
  THEN 0 ELSE 1 END;
-- 6) hold 하나에 주문 하나(더블클릭·재결제가 주문을 늘리지 않았다).
SELECT 'orders_per_hold_gt_1', count(*) FROM (SELECT o.hold_id FROM orders o WHERE o.event_id = ${ev}
  GROUP BY o.hold_id HAVING count(*) > 1) x;
-- 7) 역할별 결제 행: A·B·C·F 주문은 결제 1(APPROVED), D는 2(FAIL- 키 FAILED 1 + APPROVED 1), E는 1(FAILED).
SELECT 'payments_per_order_role', count(*) FROM orders o JOIN ${EXP} ON exp.user_id = o.user_id
  WHERE o.event_id = ${ev} AND NOT (
    CASE exp.role
      WHEN 'D' THEN (SELECT count(*) FROM payments p WHERE p.order_id = o.id) = 2
        AND (SELECT count(*) FROM payments p WHERE p.order_id = o.id AND p.status = 'FAILED' AND p.idempotency_key LIKE 'FAIL-%') = 1
        AND (SELECT count(*) FROM payments p WHERE p.order_id = o.id AND p.status = 'APPROVED') = 1
      WHEN 'E' THEN (SELECT count(*) FROM payments p WHERE p.order_id = o.id) = 1
        AND (SELECT count(*) FROM payments p WHERE p.order_id = o.id AND p.status = 'FAILED') = 1
      ELSE (SELECT count(*) FROM payments p WHERE p.order_id = o.id) = 1
        AND (SELECT count(*) FROM payments p WHERE p.order_id = o.id AND p.status = 'APPROVED') = 1
    END);
-- 8) 승인 결제가 mock 게이트웨이에서 나왔다(아니면 무효 — 세션 오버레이가 안 걸렸다).
SELECT 'approved_not_mock', count(*) FROM payments p JOIN orders o ON o.id = p.order_id
  WHERE o.event_id = ${ev} AND p.status = 'APPROVED' AND (p.pg_tid IS NULL OR p.pg_tid NOT LIKE 'MOCK-%');
-- 9) 1인 한도: 사용자별 활성 좌석(PAID 주문 좌석 + HELD hold 좌석) ≤ 4
SELECT 'quota_exceeded', count(*) FROM (
  SELECT u.user_id FROM (
    SELECT o.user_id, oi.seat_id FROM orders o JOIN order_items oi ON oi.order_id = o.id WHERE o.event_id = ${ev} AND o.status = 'PAID'
    UNION
    SELECT h.user_id, i.seat_id FROM seat_holds h JOIN seat_hold_items i ON i.hold_id = h.id WHERE h.event_id = ${ev} AND h.status = 'HELD'
  ) u GROUP BY u.user_id HAVING count(DISTINCT u.seat_id) > 4) x;
-- 10) 아웃박스: PAID 주문마다 order.paid 정확히 1, 전부 PUBLISHED
SELECT 'outbox_order_paid_not_1', count(*) FROM orders o WHERE o.event_id = ${ev} AND o.status = 'PAID'
  AND (SELECT count(*) FROM outbox_events e WHERE e.aggregate_type = 'order' AND e.aggregate_id = o.id AND e.type = 'order.paid') <> 1;
SELECT 'outbox_not_published', count(*) FROM outbox_events e JOIN orders o ON o.id = e.aggregate_id
  WHERE e.aggregate_type = 'order' AND o.event_id = ${ev} AND e.status <> 'PUBLISHED';
`;
}

function main() {
  const [cmd, ...rest] = process.argv.slice(2);
  if (cmd === "client" && rest.length === 1) {
    const { records, malformed } = parseResults(readFileSync(rest[0], "utf8"));
    const j = judgeClient(records, malformed);
    console.log(JSON.stringify(j, null, 2));
    process.exit(j.verdict === "통과" ? 0 : j.verdict === "위반" ? 1 : 2);
  }
  if (cmd === "sql" && rest[0] === "--event" && rest.length === 3) {
    const { records, malformed } = parseResults(readFileSync(rest[2], "utf8"));
    if (malformed || !records.length) { console.error(`결과를 읽지 못했다(깨진 줄 ${malformed}, 결과 ${records.length})`); process.exit(2); }
    process.stdout.write(buildSql(records, rest[1]));
    return;
  }
  console.error("사용: booking-expect.mjs client <결과> | booking-expect.mjs sql --event <id> <결과>");
  process.exit(2);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main();
