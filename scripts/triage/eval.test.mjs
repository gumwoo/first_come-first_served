// 평가 채점 테스트: 판정 → 범주 코드, 정답·판단 불가·정상 오탐 집계. 의존성 없는 node 내장 테스트.
import test from "node:test";
import assert from "node:assert/strict";
import { flagStats, label, score, scoreOutOfRules } from "./eval.mjs";
import { classify } from "./rules.mjs";

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

test("규칙에 없던 장애는 가까운 범주·판단 불가·오판으로 나눠 세고, 표시는 규칙 정오답별로 센다", () => {
  const rows = [
    { expected: "db", rule: "app_cpu", ai: "db", pipeline: "app_cpu", flag: true },
    { expected: "db", rule: "app_cpu", ai: "undetermined", pipeline: "app_cpu", flag: true },
    { expected: "db", rule: "undetermined", ai: "redis", pipeline: "redis", flag: false },
  ];
  assert.deepEqual(scoreOutOfRules(rows).rule, { expected: 0, undetermined: 1, wrong_cause: 2, total: 3 });
  assert.deepEqual(scoreOutOfRules(rows).ai, { expected: 1, undetermined: 1, wrong_cause: 1, total: 3 });
  const f = flagStats([...rows, { expected: "redis", rule: "redis", flag: true }, { expected: "normal", rule: "normal", flag: false }]);
  assert.deepEqual(f, { rule_cause_runs: 3, flagged_rule_wrong: 2, rule_wrong: 2, flagged_rule_right: 1, rule_right: 1 });
});

test("CPU 할당량 갈래는 예매 p95로 이상을 가리기 전에 본다", () => {
  const s = (thr, p95) => ({ validity: { valid: true }, booking: { p95_median_ms: p95, http5xx: 0 },
    api: { cgroup: { throttled_period_ratio: thr }, redis: { completion_ms_mean: 0.4 }, hikari: { usage_ms_mean: 6 }, scrape_rtt_ms_max: 200 },
    db: { active_mean: 0.05 } });
  assert.equal(classify(s(0.21, 999)).cause, "app_cpu"); // 91981ef0과 같은 모양(IMP-045 §8.4)
  assert.equal(classify(s(0.05, 999)).verdict, "normal");
  assert.equal(classify(s(null, 999)).verdict, "normal"); // cgroup 통계가 없는 run
});
