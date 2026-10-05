// CloudWatch 내보내기(export-cloudwatch.mjs)의 질의 구성·결과 요약 테스트. node 내장 테스트라 의존성이 없다.
//   node --test scripts/loadtest/export-cloudwatch.test.mjs
import assert from "node:assert/strict";
import test from "node:test";
import { buildQueries, summarize, RDS_METRICS, REDIS_METRICS } from "./export-cloudwatch.mjs";

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
  assert.deepEqual(c.MetricStat.Metric.Dimensions, [{ Name: "CacheClusterId", Value: "flowticket-redis-001" }]);
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

test("페이지가 나뉜 같은 Id의 점을 합치고, 한 페이지라도 PartialData면 문제다", () => {
  const one = q.slice(0, 1);
  const ok = summarize(one, [{ Id: one[0].Id, StatusCode: "Complete", Values: [1] }, { Id: one[0].Id, StatusCode: "Complete", Values: [2, 3] }]);
  assert.equal(ok.perQuery[0].points, 3);
  assert.equal(ok.problems.length, 0);
  const partial = summarize(one, [{ Id: one[0].Id, StatusCode: "PartialData", Values: [1] }, { Id: one[0].Id, StatusCode: "Complete", Values: [2] }]);
  assert.equal(partial.problems.length, 1);
});
