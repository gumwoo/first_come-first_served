// AI 분석 경계 테스트: 요청 형식, 수치 대조, 대조 실패 시 판정 보류, AI 호출 조건.
// 실제 API는 부르지 않는다(가짜 client). 의존성 없는 node 내장 테스트.
import test from "node:test";
import assert from "node:assert/strict";
import { analyze, buildRequest, TOOL } from "./analyze.mjs";
import { resolve, verify } from "./verify.mjs";
import { finalVerdict, shouldCallAi } from "./triage.mjs";

const summary = {
  validity: { valid: true, reasons: [] },
  booking: { p95_median_ms: 4200, http5xx: 0 },
  api: { redis: { completion_ms_mean: 6.3 }, hikari: { usage_ms_mean: 31.2 } },
  db: { active_mean: 0.04, active_wait_events: { "IO:WALSync": 2 } },
  series: [{ s: 0, tomcat_busy: 20 }, { s: 1, tomcat_busy: 140 }],
};
const answer = {
  verdict: "cause",
  candidates: [{ cause: "redis", confidence: "high", rationale: "Redis 명령 완료가 느리다" }],
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
  assert.equal(req.messages.length, 1);
  assert.ok(req.messages[0].content.endsWith(JSON.stringify(summary)));
});

test("근거 경로와 값을 요약 JSON과 대조한다", async () => {
  assert.deepEqual(resolve(summary, "db.active_wait_events.IO:WALSync"), { found: true, value: 2 });
  const ok = await analyze(summary, { client: fakeClient(answer) });
  assert.equal(verify(ok.result, summary).ok, true);
  assert.deepEqual(finalVerdict(ok, verify(ok.result, summary)), { verdict: "cause", cause: "redis", note: null });

  const wrong = structuredClone(answer);
  wrong.evidence[0].value = 9.9;                          // 지어낸 값
  wrong.evidence.push({ claim: "없는 지표", field: "api.gc.pause_ms", value: 1 }); // 없는 경로
  const check = verify(wrong, summary);
  assert.deepEqual(check.failures.map((f) => f.reason), ["값 불일치", "경로 없음"]);
  assert.equal(finalVerdict({ result: wrong }, check).verdict, "undetermined");
  assert.equal(verify({ ...answer, evidence: [{ ...answer.evidence[0], value: "6.3" }] }, summary).ok, false); // 숫자가 아닌 값
  assert.equal(verify({ ...answer, candidates: [] }, summary).ok, false);                                       // 후보 없는 원인 판정
});

test("측정 무효이거나 규칙상 이상이 없으면 AI를 부르지 않는다", () => {
  assert.equal(shouldCallAi({ validity: { valid: false } }, { verdict: "undetermined" }).call, false);
  assert.equal(shouldCallAi(summary, { verdict: "normal" }).call, false);
  assert.equal(shouldCallAi(summary, { verdict: "cause" }).call, true);
});
