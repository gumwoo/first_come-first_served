// booking-expect.mjs: 역할별 클라이언트 기대값 판정과 DB 기대값 SQL 생성.
//   node --test scripts/loadtest/booking-expect.test.mjs
// fixture는 booking-e2e.js를 mock-booking-server.mjs 상대로 돌린 결과 줄(토큰 없음 — 결과 줄은 토큰을 남기지 않는다).
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { buildSql, judgeClient, parseResults, roleViolations } from "./booking-expect.mjs";

const FIXTURE = readFileSync(new URL("./fixtures/booking-e2e-mock.jsonl", import.meta.url), "utf8");
const fresh = () => parseResults(FIXTURE).records.map((r) => structuredClone(r));
const first = (rs, role, pred = () => true) => rs.find((r) => r.role === role && pred(r));

test("결과 줄만 읽고 다른 줄·깨진 줄은 따로 센다", () => {
  const { records, malformed } = parseResults(`k6 로그\nE2E {"vu":1}\nE2E {깨짐\n`);
  assert.equal(records.length, 1);
  assert.equal(malformed, 1);
});

test("mock 실행 결과는 통과다(역할 6개·B 좌석마다 승자 1)", () => {
  const j = judgeClient(fresh());
  assert.equal(j.verdict, "통과", JSON.stringify(j.violations.concat(j.invalid)));
  assert.equal(j.users, 100);
  assert.deepEqual(Object.values(j.bWinners), [1, 1, 1, 1]);
});

test("B 좌석에 승자가 둘이면 위반이다", () => {
  const rs = fresh();
  const loser = first(rs, "B", (r) => r.steps.some((s) => s.step === "hold" && s.status === 409));
  loser.steps.find((s) => s.step === "hold").status = 200;
  loser.steps.find((s) => s.step === "hold").code = null;
  const j = judgeClient(rs);
  assert.equal(j.verdict, "위반");
  assert.ok(j.violations.some((v) => /hold 성공 2명/.test(v)), j.violations.join("\n"));
});

test("C: 동시 결제의 paymentId가 둘이면 위반, 동시 hold가 둘 다 200이면 위반", () => {
  const rs = fresh();
  const c = first(rs, "C");
  c.payments[1].paymentId = 999999;
  assert.ok(roleViolations(c).some((v) => /paymentId가 하나가 아님/.test(v)));
  const c2 = first(rs, "C", (r) => r !== c);
  for (const s of c2.steps.filter((s) => s.step === "hold")) { s.status = 200; s.code = null; }
  assert.ok(roleViolations(c2).some((v) => /동시 hold 2회/.test(v)));
});

test("D: 실패 결제가 FAILED가 아니면 위반, E가 재결제하면 위반, F 한도 초과가 200이면 위반", () => {
  const rs = fresh();
  const d = first(rs, "D");
  d.payments.find((p) => p.step === "pay_fail").paymentStatus = "APPROVED";
  assert.ok(roleViolations(d).some((v) => /FAIL 키 결제/.test(v)));
  const e = first(rs, "E");
  e.payments.push({ step: "pay", status: 200, paymentStatus: "APPROVED", orderStatus: "PAID" });
  assert.ok(roleViolations(e).some((v) => /재결제/.test(v)));
  const f = first(rs, "F");
  const over = f.steps.find((s) => s.step === "hold_over_quota");
  over.status = 200; over.code = null;
  assert.ok(roleViolations(f).some((v) => /MAX_PER_USER_EXCEEDED/.test(v)));
});

test("5xx는 위반, 401·미입장·인원 부족은 무효", () => {
  const rs = fresh();
  first(rs, "A").steps.find((s) => s.step === "order").status = 503;
  assert.equal(judgeClient(rs).verdict, "위반");
  const rs2 = fresh();
  first(rs2, "A").admitted = false;
  assert.equal(judgeClient(rs2).verdict, "무효");
  const rs3 = fresh();
  first(rs3, "A").steps[0].status = 401;
  assert.equal(judgeClient(rs3).verdict, "무효");
  assert.equal(judgeClient(fresh().slice(1)).verdict, "무효");
});

test("사용자 하나가 두 역할을 맡으면 무효", () => {
  const rs = fresh();
  rs[1].userId = rs[0].userId;
  assert.ok(judgeClient(rs).invalid.some((v) => /userId/.test(v)));
});

test("SQL: 판매 좌석은 설계에서(A·B·C·D·F 74석), B 좌석 4석, 검사 줄이 모두 있다", () => {
  const rs = fresh();
  const sql = buildSql(rs, 1826);
  const soldValues = sql.match(/AS sold\(seat_id\)/g).length;
  assert.ok(soldValues >= 2);
  const sold = new Set(rs.filter((r) => "ABCDF".includes(r.role)).flatMap((r) => r.seatIds));
  assert.equal(sold.size, 74);
  for (const name of ["orders_by_other_users", "seat_status_mismatch", "holds_still_held", "b_seat_hold_items_not_1", "paid_orders_not_59",
    "orders_per_user_role", "b_orders_not_4_paid", "orders_per_hold_gt_1", "payments_per_order_role", "approved_not_mock",
    "quota_exceeded", "outbox_order_paid_not_1", "outbox_not_published"]) {
    assert.match(sql, new RegExp(`SELECT '${name}'`));
  }
  assert.ok(!/event_id = 1826'/.test(sql));
});

test("SQL: 정수가 아닌 값(좌석·사용자·공연)은 넣지 않고 실패한다", () => {
  const rs = fresh();
  rs[0].seatIds = ["1); DROP TABLE seats; --"];
  assert.throws(() => buildSql(rs, 1826), /정수가 아니다/);
  assert.throws(() => buildSql(fresh(), "1826 OR 1=1"), /정수가 아니다/);
});
