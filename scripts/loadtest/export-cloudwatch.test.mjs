// CloudWatch 내보내기(export-cloudwatch.mjs)의 질의 구성·결과 요약 테스트. node 내장 테스트라 의존성이 없다.
//   node --test scripts/loadtest/export-cloudwatch.test.mjs
import assert from "node:assert/strict";
import test from "node:test";
import { buildQueries, summarize, toApi, RDS_METRICS, REDIS_METRICS, CREDIT_PERIOD } from "./export-cloudwatch.mjs";

const q = buildQueries({ rdsId: "flowticket", cacheClusterIds: ["flowticket-redis-001", "flowticket-redis-002"], period: 60 });

test("RDS 지표와 노드마다의 ElastiCache 지표를 모두 질의한다", () => {
  assert.equal(q.length, RDS_METRICS.length + 2 * REDIS_METRICS.length);
  assert.ok(q.some((x) => x.Label === "rds/flowticket/CPUCreditBalance/Minimum"));
  assert.ok(q.some((x) => x.Label === "redis/flowticket-redis-002/CPUCreditBalance/Minimum"));
});

test("질의 Id는 GetMetricData 규칙(소문자 시작, 영숫자·밑줄)을 지키고 서로 다르다", () => {
  for (const x of q) assert.match(x.Id, /^[a-z][a-z0-9_]*$/);
  assert.equal(new Set(q.map((x) => x.Id)).size, q.length);
});

test("차원과 통계가 지표마다 맞다", () => {
  const r = q.find((x) => x.Label === "rds/flowticket/CPUUtilization/Maximum");
  assert.deepEqual(r.MetricStat.Metric.Dimensions, [{ Name: "DBInstanceIdentifier", Value: "flowticket" }]);
  assert.equal(r.MetricStat.Stat, "Maximum");
  const c = q.find((x) => x.Label === "redis/flowticket-redis-001/EngineCPUUtilization/Maximum");
  assert.equal(c.MetricStat.Metric.Namespace, "AWS/ElastiCache");
  assert.deepEqual(c.MetricStat.Metric.Dimensions, [{ Name: "CacheClusterId", Value: "flowticket-redis-001" }, { Name: "CacheNodeId", Value: "0001" }]);
});

test("크레딧 지표는 5분, 나머지는 --period로 질의한다", () => {
  for (const x of q) {
    const credit = /^CPU(Credit|Surplus)/.test(x.MetricStat.Metric.MetricName);
    assert.equal(x.MetricStat.Period, credit ? CREDIT_PERIOD : 60, x.Label);
  }
});

test("AWS로 보내는 질의에는 우리 쪽 표시(optional)가 없다", () => {
  for (const x of toApi(q)) assert.equal("optional" in x, false);
});

test("RDS 잉여 크레딧은 선택 질의라 비어도 문제가 아니고, 응답 메시지는 문제다", () => {
  const surplus = q.filter((x) => x.optional && /^CPUSurplus/.test(x.MetricStat.Metric.MetricName));
  assert.deepEqual(surplus.map((x) => x.MetricStat.Metric.MetricName).sort(), ["CPUSurplusCreditBalance", "CPUSurplusCreditsCharged"]);
  const { problems } = summarize(surplus, []);
  assert.equal(problems.length, 0);
  const withMsg = summarize([], [], [{ Code: "MaxQueryTimeRangeExceed" }]);
  assert.equal(withMsg.problems.length, 1);
});

test("빈 질의·Complete가 아닌 질의·응답 없는 질의를 문제로 센다", () => {
  const two = q.slice(0, 3);
  const { problems, perQuery } = summarize(two, [
    { Id: two[0].Id, StatusCode: "Complete", Values: [1, 2] },
    { Id: two[1].Id, StatusCode: "Complete", Values: [] },
  ]);
  assert.equal(perQuery[0].points, 2);
  assert.equal(problems.length, 2); // 빈 질의 1개 + 응답 없는 질의 1개
});

test("페이지가 나뉜 같은 Id의 점을 합치고, 마지막 페이지가 Complete가 아니면 문제다", () => {
  const one = q.slice(0, 1);
  const ok = summarize(one, [{ Id: one[0].Id, StatusCode: "Complete", Values: [1] }, { Id: one[0].Id, StatusCode: "Complete", Values: [2, 3] }]);
  assert.equal(ok.perQuery[0].points, 3);
  assert.equal(ok.problems.length, 0);
  // 앞 페이지의 PartialData는 "이어 받으라"는 정상 신호다.
  const paged = summarize(one, [{ Id: one[0].Id, StatusCode: "PartialData", Values: [1] }, { Id: one[0].Id, StatusCode: "Complete", Values: [2] }]);
  assert.equal(paged.problems.length, 0);
  const unfinished = summarize(one, [{ Id: one[0].Id, StatusCode: "Complete", Values: [1] }, { Id: one[0].Id, StatusCode: "PartialData", Values: [2] }]);
  assert.equal(unfinished.problems.length, 1);
});

test("질의별 메시지도 문제로 센다", () => {
  const one = q.slice(0, 1);
  const r = summarize(one, [{ Id: one[0].Id, StatusCode: "Complete", Values: [1], Messages: [{ Code: "ArithmeticError", Value: "x" }] }]);
  assert.equal(r.problems.length, 1);
});

test("앞 페이지의 InternalError는 마지막 페이지가 Complete여도 문제다", () => {
  const one = q.slice(0, 1);
  const r = summarize(one, [{ Id: one[0].Id, StatusCode: "InternalError", Values: [1] }, { Id: one[0].Id, StatusCode: "Complete", Values: [2] }]);
  assert.equal(r.problems.length, 1);
});

test("버스트 버킷과 Redis 명령 지연을 질의한다(선택 질의)", () => {
  for (const label of ["rds/flowticket/EBSIOBalance%/Minimum", "redis/flowticket-redis-001/NetworkBandwidthOutAllowanceExceeded/Sum",
    "redis/flowticket-redis-002/EvalBasedCmdsLatency/Average"]) {
    const x = q.find((y) => y.Label === label);
    assert.ok(x, label);
    assert.equal(x.optional, true);
  }
});
