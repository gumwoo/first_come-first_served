// AI 분석 경계 테스트: 요청 형식, 수치 대조, 대조 실패 시 판정 보류, AI 호출 조건.
// 실제 API는 부르지 않는다(가짜 client). 의존성 없는 node 내장 테스트.
import test from "node:test";
import assert from "node:assert/strict";
import { analyze, buildRequest, TOOL } from "./analyze.mjs";
import { resolve, verify } from "./verify.mjs";
import { finalVerdict, pipelineVerdict, shouldCallAi } from "./triage.mjs";

const summary = {
  validity: { valid: true, reasons: [] },
  booking: { p95_median_ms: 4200, http5xx: 0 },
  api: { redis: { completion_ms_mean: 6.3 }, hikari: { usage_ms_mean: 31.2 } },
  db: { active_mean: 0.04, active_wait_events: { "IO:WALSync": 2 } },
  series: [{ s: 0, tomcat_busy: 20 }, { s: 1, tomcat_busy: 140 }],
};
const answer = {
  verdict: "anomaly", conclusion: "supported", summary_line: "Redis 명령 완료가 느려 예매가 늦었다",
  hypotheses: [{ cause: "redis", name: "Redis 응답 지연", confidence: "high", rationale: "Redis 명령 완료가 느리다", confirm: "Redis 서버 측 지연이 같이 늘었는지" }],
  evidence: [{ claim: "Redis 명령 완료 평균", field: "api.redis.completion_ms_mean", value: 6.3 }, { claim: "1초 시점 바쁜 스레드", field: "series[1].tomcat_busy", value: 140 }],
  counter_evidence: [{ claim: "DB 활성 세션은 적다", field: "db.active_mean", value: 0.04 }],
  unchecked: [], next_checks: ["Redis 서버 측 지연 확인"],
};
const fakeClient = (input) => ({ messages: { create: async (req) => ({ model: req.model, stop_reason: "tool_use", usage: { input_tokens: 900, output_tokens: 200 }, content: [{ type: "tool_use", name: TOOL.name, input }] }) } });

test("strict 도구 하나로 받고(강제 호출·temperature 없이), 입력은 요약 JSON뿐이다", () => {
  const req = buildRequest(summary, "m");
  assert.deepEqual(req.tool_choice, { type: "auto" });
  assert.equal(req.tools[0].strict, true);
  assert.equal("temperature" in req, false);
  assert.match(req.system, /정상 기준/); // AI도 정상 범위를 받는다(규칙의 원인별 기준값은 받지 않는다)
  assert.equal(req.messages.length, 1);
  assert.ok(req.messages[0].content.endsWith(JSON.stringify(summary)));
});

test("근거 경로와 값을 요약 JSON과 대조한다", async () => {
  assert.deepEqual(resolve(summary, "db.active_wait_events.IO:WALSync"), { found: true, value: 2 });
  assert.deepEqual(resolve(summary, "validity.valid"), { found: true, value: 1 }); // 참·거짓 필드는 1·0
  const ok = await analyze(summary, { client: fakeClient(answer) });
  assert.equal(verify(ok.result, summary).ok, true);
  assert.deepEqual(finalVerdict(ok, verify(ok.result, summary)), { verdict: "cause", cause: "redis", name: "Redis 응답 지연", conclusion: "supported", note: null });

  const wrong = structuredClone(answer);
  wrong.evidence[0].value = 9.9;                          // 지어낸 값
  wrong.evidence.push({ claim: "없는 지표", field: "api.gc.pause_ms", value: 1 }); // 없는 경로
  const check = verify(wrong, summary);
  assert.deepEqual(check.failures.map((f) => f.reason), ["값 불일치", "경로 없음"]);
  assert.equal(finalVerdict({ result: wrong }, check).verdict, "undetermined");
  assert.equal(verify({ ...answer, evidence: [{ ...answer.evidence[0], value: "6.3" }] }, summary).ok, false); // 숫자가 아닌 값
  assert.equal(verify({ ...answer, hypotheses: [] }, summary).ok, false);                                       // 의심 원인 없는 이상 판정
});

test("이상이면 확정 못 해도 1순위 의심 원인을 판정으로 쓰고, 범주 밖 원인은 이름이 있어야 한다", () => {
  const other = { ...answer, conclusion: "inconclusive",
    hypotheses: [{ cause: "other", name: "api와 PostgreSQL 사이 왕복 지연", confidence: "low", rationale: "DB는 한가한데 트랜잭션 안 대기가 길다", confirm: "api 컨테이너에서 DB까지 왕복 시간" }] };
  const check = verify(other, summary);
  assert.equal(check.ok, true);
  assert.deepEqual(finalVerdict({ result: other }, check), { verdict: "cause", cause: "other", name: "api와 PostgreSQL 사이 왕복 지연", conclusion: "inconclusive", note: null });
  assert.equal(verify({ ...other, hypotheses: [{ ...other.hypotheses[0], name: " " }] }, summary).ok, false);
  const normal = { ...answer, verdict: "normal", hypotheses: [], evidence: [] };
  assert.equal(verify(normal, summary).ok, true);
  assert.equal(finalVerdict({ result: normal }, verify(normal, summary)).verdict, "normal");
  assert.equal(pipelineVerdict({ verdict: "cause", cause: "app_cpu" }, { final: finalVerdict({ result: other }, check), verification: check }).disagree, true);
});

test("최종 판정: 규칙 원인을 쓰고 AI가 다르면 표시, 규칙이 판단 불가면 대조를 통과한 AI 판정", () => {
  const ai = (verdict, cause, ok = true) => ({ final: { verdict, cause }, verification: { ok } });
  const rCause = { verdict: "cause", cause: "app_cpu" }, rUnd = { verdict: "undetermined", cause: null };
  assert.deepEqual(pipelineVerdict({ verdict: "normal", cause: null }, ai("cause", "redis")), { verdict: "normal", cause: null, source: "rule", disagree: false });
  assert.deepEqual(pipelineVerdict(rCause, ai("cause", "app_cpu")), { verdict: "cause", cause: "app_cpu", source: "rule", disagree: false });
  assert.equal(pipelineVerdict(rCause, ai("cause", "db")).disagree, true);
  assert.equal(pipelineVerdict(rCause, ai("undetermined", null)).disagree, true);        // AI가 근거 부족으로 본 것도 표시
  assert.equal(pipelineVerdict(rCause, ai("undetermined", null, false)).disagree, false); // 대조 실패한 답은 표시에 쓰지 않는다
  assert.equal(pipelineVerdict(rCause, null).cause, "app_cpu");
  assert.deepEqual(pipelineVerdict(rUnd, ai("cause", "db")), { verdict: "cause", cause: "db", source: "ai", disagree: false });
  assert.equal(pipelineVerdict(rUnd, ai("cause", "db", false)).verdict, "undetermined");
  assert.equal(pipelineVerdict(rUnd, { error: "x", final: { verdict: "undetermined", cause: null } }).verdict, "undetermined");
});

test("측정 무효이거나 규칙상 이상이 없으면 AI를 부르지 않는다", () => {
  assert.equal(shouldCallAi({ validity: { valid: false } }, { verdict: "undetermined" }).call, false);
  assert.equal(shouldCallAi(summary, { verdict: "normal" }).call, false);
  assert.equal(shouldCallAi(summary, { verdict: "cause" }).call, true);
});
