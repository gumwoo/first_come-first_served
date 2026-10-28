// Slack 알림 테스트: 사람 확인 표시가 있는 run만 보내고, 메시지는 요약 수치와 AI 의심 원인으로 조립한다.
// 실제 웹훅은 부르지 않는다(가짜 fetch). 의존성 없는 node 내장 테스트.
import test from "node:test";
import assert from "node:assert/strict";
import { bold, firstSentence, postSlack, shouldNotify, slackText, softEnding } from "./notify.mjs";

const summary = {
  booking: { p95_median_ms: 3011, http5xx: 0 },
  api: { cgroup: { throttled_period_ratio: 0.01 }, redis: { completion_ms_mean: 0.4 }, hikari: { usage_ms_mean: 76.6, acquire_ms_mean: 1017.8 }, scrape_rtt_ms_max: 5714 },
  db: { active_mean: 0.05 },
};
const rule = { verdict: "cause", cause: "app_cpu" };
const hyp = (cause, name, confirm, confidence = "medium") => ({ cause, name, confidence, rationale: "-", confirm });
const ai = (result, ok = true) => ({ result, final: { verdict: result.verdict === "normal" ? "normal" : "cause", cause: result.hypotheses?.[0]?.cause ?? null }, verification: { ok } });
const disagreeing = ai({ verdict: "anomaly", conclusion: "inconclusive", hypotheses: [
  hyp("db", "좌석 행 잠금 경합으로 커넥션 풀(5)이 고갈됨", "pg_locks/pg_stat_activity로 blocking-blocked 쌍을 잡아 좌석 행 대상인지 확인한다. 같은 좌석 수를 줄여 재실행한다."),
  hyp("other", "트랜잭션 내 앱 측 대기", "앱 스레드 덤프로 트랜잭션 안에서 무엇을 기다리는지 확인한다.", "low"),
] });

test("규칙과 대조를 통과한 AI 판정이 다를 때만 보낸다", () => {
  assert.equal(shouldNotify(rule, disagreeing), true);
  assert.equal(shouldNotify(rule, ai({ verdict: "anomaly", hypotheses: [hyp("app_cpu", "CPU", "-")] })), false); // 같은 판정
  assert.equal(shouldNotify(rule, { ...disagreeing, verification: { ok: false } }), false);                    // 대조 실패
  assert.equal(shouldNotify({ verdict: "normal", cause: null }, disagreeing), false);                          // 규칙상 정상
  assert.equal(shouldNotify(rule, null), false);                                                               // AI 없음
});

// 굵게 표시(폭 없는 공백 + *)를 뺀 글자. 문장은 이걸로, 굵게 넣는 위치는 따로 확인한다.
const plain = (s) => s.replace(/​\*|\*​/g, "");
const slackPlain = (...a) => plain(slackText(...a));

test("메시지는 요약 수치, 규칙 갈래, AI 의심 원인과 확인 방법 첫 문장으로 만든다", () => {
  const text = slackText("3e47ee46", summary, rule, disagreeing);
  assert.deepEqual([...text.matchAll(/​\*(.+?)\*​/g)].map((m) => m[1]),
    ["[초기 분석] 3e47ee46", "3,011ms", "1,018ms", "앱 CPU 부족", "76.6ms", "\"좌석 행 잠금 경합으로 커넥션 풀(5)이 고갈됨\""]);
  assert.equal(bold("a*b"), "​*ab*​"); // 굵게 구간 안의 *는 뺀다
  assert.equal(plain(text), [
    "[초기 분석] 3e47ee46 run에서 예매가 느려졌어요. p95가 3,011ms까지 올라갔고, 커넥션 하나 받는 데 평균 1,018ms 기다렸어요.",
    "규칙으로는 앱 CPU 부족으로 나와요. 커넥션을 한 번 잡으면 평균 76.6ms씩 쥐고 있었는데, Redis랑 DB 수치는 괜찮았거든요.",
    "그런데 AI는 \"좌석 행 잠금 경합으로 커넥션 풀(5)이 고갈됨\" 쪽을 더 의심하고 있어서 한번 봐주시면 좋겠어요. AI도 확정은 못 했대요.",
    "• pg_locks/pg_stat_activity로 blocking-blocked 쌍을 잡아 좌석 행 대상인지 확인해 보기",
    "• 아니면 \"트랜잭션 내 앱 측 대기\" 쪽: 앱 스레드 덤프로 트랜잭션 안에서 무엇을 기다리는지 확인해 보기",
  ].join("\n"));
  const throttled = { ...summary, api: { ...summary.api, cgroup: { throttled_period_ratio: 0.42 } }, booking: { p95_median_ms: 2100, http5xx: 3 } };
  assert.match(slackPlain("x", throttled, rule, disagreeing), /5xx도 3건 났어요\.\n규칙으로는 앱 CPU 부족으로 나와요\. api가 CPU 할당량에 막힌 주기가 42%였거든요\./);
  const quiet = { ...throttled, booking: { p95_median_ms: 916, http5xx: 0 } };                                    // CPU 할당량 갈래는 p95가 낮아도 원인을 낸다
  assert.match(slackPlain("x", quiet, rule, disagreeing), /^\[초기 분석\] x run은 예매 p95가 916ms라 아직 느리진 않은데, 커넥션 하나 받는 데 평균 1,018ms 기다렸어요\./);
  assert.match(slackPlain("x", summary, rule, ai({ verdict: "normal", hypotheses: [] })), /AI는 이상 없는 run으로 봤어요/);
  assert.match(slackPlain("x", summary, rule, ai({ verdict: "anomaly", hypotheses: [hyp("db", "a<b & c", "-")] })), /"a&lt;b &amp; c"/); // Slack 제어 문자
});

test("확인 방법은 첫 문장만, 길면 자르고, 끝말은 본문 말투에 맞춘다", () => {
  assert.equal(firstSentence("A를 본다. B를 본다."), "A를 본다");
  assert.equal(firstSentence("A를 본다; B를 본다"), "A를 본다");
  assert.equal(firstSentence("가".repeat(200)).length, 160);
  assert.equal(softEnding("겹치는지 확인한다"), "겹치는지 확인해 보기");
  assert.equal(softEnding("일치하는지 본다"), "일치하는지 보기");
  assert.equal(softEnding("시간대에 비교한다"), "시간대에 비교하기");
  assert.equal(softEnding("대기 기록을 수집"), "대기 기록을 수집"); // 패턴 밖은 그대로
});

test("웹훅 URL이 없거나 Slack 주소가 아니면 보내지 않는다", async () => {
  let calls = 0;
  const fetchImpl = async () => { calls++; return { ok: true, status: 200 }; };
  assert.deepEqual(await postSlack("t", { url: undefined, fetchImpl }), { sent: false, reason: "TRIAGE_SLACK_WEBHOOK_URL 없음" });
  assert.equal((await postSlack("t", { url: "https://example.com/x", fetchImpl })).sent, false);
  assert.equal(calls, 0);
  assert.deepEqual(await postSlack("t", { url: "https://hooks.slack.com/services/T/B/x", fetchImpl }), { sent: true });
  await assert.rejects(postSlack("t", { url: "https://hooks.slack.com/services/T/B/x", fetchImpl: async () => ({ ok: false, status: 404 }) }), /404/);
});
