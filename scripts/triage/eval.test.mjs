// 평가 채점 테스트: 판정 → 범주 코드, 정답·판단 불가·정상 오탐 집계. 의존성 없는 node 내장 테스트.
import test from "node:test";
import assert from "node:assert/strict";
import { label, score } from "./eval.mjs";

test("판정을 범주 코드로 바꾼다", () => {
  assert.equal(label({ verdict: "cause", cause: "db" }), "db");
  assert.equal(label({ verdict: "normal", cause: null }), "normal");
  assert.equal(label({ verdict: "undetermined", cause: null }), "undetermined");
});

test("장애 정답·판단 불가, 정상 오탐을 따로 센다", () => {
  const rows = [
    { expected: "redis", rule: "redis", ai: "redis", pipeline: "redis" },
    { expected: "db", rule: "db", ai: "undetermined", pipeline: "undetermined" },
    { expected: "app_cpu", rule: "undetermined", ai: "app_cpu", pipeline: "app_cpu" },
    { expected: "normal", rule: "normal", ai: "redis", pipeline: "normal" },
    { expected: "normal", rule: "normal", ai: "undetermined", pipeline: "normal" },
  ];
  const s = score(rows);
  assert.deepEqual(s.rule, { fault_correct: 2, fault_total: 3, fault_undetermined: 1, normal_false_positive: 0, normal_undetermined: 0, normal_total: 2 });
  assert.deepEqual(s.ai, { fault_correct: 2, fault_total: 3, fault_undetermined: 1, normal_false_positive: 1, normal_undetermined: 1, normal_total: 2 });
  assert.equal(s.pipeline.normal_false_positive, 0); // 규칙이 정상으로 본 run은 AI를 부르지 않는다
});
