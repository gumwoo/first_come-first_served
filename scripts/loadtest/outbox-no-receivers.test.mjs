import { test } from "node:test";
import assert from "node:assert/strict";
import { noReceiverIncrease, restartedPods } from "./outbox-no-receivers.mjs";

// 구간: since=1000, until=2000(+30초 꼬리 → 2030까지 본다)
const S = 1000;
const U = 2000;
const series = (pod, points) => ({ metric: { pod }, values: points.map(([t, v]) => [t, String(v)]) });

test("구간 내내 0이면 증가 0", () => {
  const r = noReceiverIncrease([series("a", [[900, 0], [1500, 0], [2010, 0]])], S, U);
  assert.equal(r.increase, 0);
});

test("구간 안에서 늘면 그만큼 센다", () => {
  const r = noReceiverIncrease([series("a", [[900, 0], [1500, 1], [1900, 3]])], S, U);
  assert.equal(r.increase, 3);
});

test("구간 이전의 증가는 세지 않는다(기준값으로만)", () => {
  const r = noReceiverIncrease([series("a", [[800, 0], [900, 5], [1500, 5]])], S, U);
  assert.equal(r.increase, 0);
});

test("run 끝 뒤 30초 꼬리 안의 증가는 센다, 그 뒤는 안 센다", () => {
  const r = noReceiverIncrease([series("a", [[900, 0], [2020, 1], [2100, 4]])], S, U);
  assert.equal(r.increase, 1);
});

test("값이 줄면 재시작으로 보고 새 값을 더한다", () => {
  const r = noReceiverIncrease([series("a", [[900, 2], [1500, 4], [1600, 1]])], S, U);
  assert.equal(r.increase, 2 + 1);
});

test("구간 안에서 뜬 파드는 첫 값 전체를 센다(기동 때 0으로 등록)", () => {
  const r = noReceiverIncrease([series("new", [[1500, 2], [1600, 2]])], S, U);
  assert.equal(r.increase, 2);
});

test("파드별 합계", () => {
  const r = noReceiverIncrease([
    series("a", [[900, 0], [1500, 1]]),
    series("b", [[900, 0], [1500, 0]]),
  ], S, U);
  assert.equal(r.increase, 1);
  assert.deepEqual(r.perPod.map((p) => [p.pod, p.increase]), [["a", 1], ["b", 0]]);
});

test("값이 줄지 않은 재시작은 재시작 기록으로 잡아, 0이 아니면 판정 불가로 둔다", () => {
  // 구간 전에 1, run 중 재시작 뒤 구독자 0 발행 1건 → 다시 1. 차이로는 0이라 놓치는 경우(G1 리뷰 재현 입력).
  const restarts = [series("a", [[900, 0], [1500, 1]])];
  const r = noReceiverIncrease([series("a", [[900, 1], [1200, 1]])], S, U, restartedPods(restarts, S, U));
  assert.equal(r.increase, 0);
  assert.deepEqual(r.undetermined, ["a"]);
});

test("재시작한 파드라도 구간 안 값이 모두 0이면 0으로 인정한다", () => {
  const restarts = [series("a", [[900, 0], [1500, 1]])];
  const r = noReceiverIncrease([series("a", [[900, 0], [1600, 0]])], S, U, restartedPods(restarts, S, U));
  assert.equal(r.increase, 0);
  assert.deepEqual(r.undetermined, []);
});

test("구간 이전의 재시작은 재시작으로 보지 않는다", () => {
  const restarts = [series("a", [[800, 0], [900, 1], [1500, 1]])];
  assert.deepEqual([...restartedPods(restarts, S, U)], []);
});

test("values가 배열이 아닌 시계열은 점 없음으로 다룬다(예외로 죽지 않는다)", () => {
  const r = noReceiverIncrease([{ metric: { pod: "a" }, values: "broken" }], S, U);
  assert.equal(r.increase, 0);
  assert.deepEqual(r.perPod, []);
});
