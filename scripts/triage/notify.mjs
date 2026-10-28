// 사람 확인이 필요한 run(규칙이 원인을 냈는데 대조를 통과한 AI 판정이 다르다)만 Slack에 알린다.
//   node scripts/triage/notify.mjs <run 디렉터리>          → 보낼 메시지를 출력만 한다
//   node scripts/triage/notify.mjs <run 디렉터리> --send   → TRIAGE_SLACK_WEBHOOK_URL로 보낸다
// triage.mjs가 만든 <run>/triage/summary.json, rule.json, ai.json을 읽는다.
// 메시지는 템플릿으로 조립한다. 수치는 요약 JSON 값을 그대로 넣고, AI 답에서는 의심 원인 이름과 확인 방법만 가져온다.
// 웹훅 URL은 비밀이라 환경변수로만 받고 출력하지 않는다(demo-alarms/lambda/slack_notify.py와 같은 원칙).
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { THRESHOLDS } from "./rules.mjs";
import { pipelineVerdict } from "./triage.mjs";

export function shouldNotify(rule, ai) {
  return pipelineVerdict(rule, ai).disagree;
}

// Slack mrkdwn 제어 문자(&, <, >)를 escape한다.
const esc = (s) => String(s ?? "").replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
const num = (x) => Number(x).toLocaleString("en-US", { maximumFractionDigits: 2 });
const ms = (x) => num(x >= 100 ? Math.round(x) : x); // 100ms 이상은 소수점이 읽는 데 도움이 안 된다
// AI 확인 방법은 여러 문장이라 첫 문장만 쓴다.
export function firstSentence(s) {
  const t = String(s ?? "").trim().split(/(?<=[.;])\s+/)[0].replace(/[.;]$/, "");
  return t.length > 160 ? t.slice(0, 159) + "…" : t;
}

// 규칙이 어느 갈래로 원인을 냈는지 사람 말로 푼다(rules.mjs classify의 갈래 순서와 같다).
function ruleSentence(s, rule, t = THRESHOLDS) {
  if (rule.cause === "redis") return `규칙으로는 Redis 지연으로 나와요. Redis 명령 완료가 평균 ${ms(s.api.redis.completion_ms_mean)}ms였거든요.`;
  if (rule.cause === "db") return `규칙으로는 DB 쪽으로 나와요. PostgreSQL 활성 세션이 평균 ${num(s.db.active_mean)}로 평소보다 많았거든요.`;
  const thr = s.api.cgroup?.throttled_period_ratio;
  if (thr != null && thr > t.app_throttled_ratio) return `규칙으로는 앱 CPU 부족으로 나와요. api가 CPU 할당량에 막힌 주기가 ${Math.round(thr * 100)}%였거든요.`;
  const usage = s.api.hikari.usage_ms_mean;
  if (usage != null) return `규칙으로는 앱 CPU 부족으로 나와요. 커넥션을 한 번 잡으면 평균 ${ms(usage)}ms씩 쥐고 있었는데, Redis랑 DB 수치는 괜찮았거든요.`;
  return `규칙으로는 앱 CPU 부족으로 나와요. api 지표 응답이 최대 ${ms(s.api.scrape_rtt_ms_max)}ms까지 늦었는데, Redis랑 DB 수치는 괜찮았거든요.`;
}

export function slackText(runId, summary, rule, ai, reportPath) {
  const b = summary.booking, acq = summary.api.hikari?.acquire_ms_mean;
  const lines = [];
  // CPU 할당량 갈래는 예매 p95가 이상 기준 아래여도 원인을 낸다(rules.mjs). 그때는 느려졌다고 쓰지 않는다.
  const slow = b.http5xx > 0 || b.p95_median_ms > THRESHOLDS.anomaly_p95_median_ms;
  let first = slow ? `${runId} run에서 예매가 느려졌어요. p95가 ${ms(b.p95_median_ms)}ms까지 올라갔고` : `${runId} run은 예매 p95가 ${ms(b.p95_median_ms)}ms라 아직 느리진 않은데`;
  first += acq != null ? `, 커넥션 하나 받는 데 평균 ${ms(acq)}ms 기다렸어요.` : slow ? "요." : " 눈여겨볼 수치가 있어요.";
  if (b.http5xx > 0) first += ` 5xx도 ${num(b.http5xx)}건 났어요.`;
  lines.push(esc(first), esc(ruleSentence(summary, rule)));
  const r = ai.result, [h1, h2] = r.hypotheses ?? [];
  if (r.verdict === "normal" || !h1) {
    lines.push("그런데 AI는 이상 없는 run으로 봤어요. 규칙 판정이 맞는지 한번 봐주시면 좋겠어요.");
  } else {
    let ask = `그런데 AI는 "${esc(h1.name)}" 쪽을 더 의심하고 있어서 한번 봐주시면 좋겠어요.`;
    if (h1.confidence === "low" || r.conclusion === "inconclusive") ask += " AI도 확정은 못 했대요.";
    lines.push(ask);
    lines.push(`• ${esc(firstSentence(h1.confirm))}`);
    if (h2) lines.push(`• 아니면 "${esc(h2.name)}" 쪽: ${esc(firstSentence(h2.confirm))}`);
  }
  lines.push(`리포트: ${esc(reportPath)}`);
  return lines.join("\n");
}

const WEBHOOK_PREFIX = "https://hooks.slack.com/";

export async function postSlack(text, { url = process.env.TRIAGE_SLACK_WEBHOOK_URL, fetchImpl = fetch } = {}) {
  if (!url) return { sent: false, reason: "TRIAGE_SLACK_WEBHOOK_URL 없음" };
  if (!url.startsWith(WEBHOOK_PREFIX)) return { sent: false, reason: "웹훅 URL 형태가 아니다" }; // 다른 곳으로 보내지 않는다
  const res = await fetchImpl(url, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ text }) });
  if (!res.ok) throw new Error(`Slack 응답 ${res.status}`);
  return { sent: true };
}

export async function notify(runDir, { send = false, fetchImpl } = {}) {
  const dir = path.join(runDir, "triage");
  const read = (f) => JSON.parse(fs.readFileSync(path.join(dir, f), "utf8"));
  const rule = read("rule.json");
  const ai = fs.existsSync(path.join(dir, "ai.json")) ? read("ai.json") : null;
  if (!shouldNotify(rule, ai)) return { sent: false, reason: "사람 확인 표시 없음" };
  const text = slackText(path.basename(path.resolve(runDir)), read("summary.json"), rule, ai, path.join(dir, "report.md").split(path.sep).join("/"));
  if (!send) return { sent: false, reason: "미리 보기", text };
  return { ...(await postSlack(text, { fetchImpl })), text };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  const dir = process.argv[2];
  if (!dir) { console.error("사용: node scripts/triage/notify.mjs <run 디렉터리> [--send]"); process.exit(2); }
  const r = await notify(dir, { send: process.argv.includes("--send") });
  console.log(r.text ?? "");
  console.error(r.sent ? "Slack 전송 완료" : `보내지 않음: ${r.reason}`);
}
