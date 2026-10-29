// 사람 확인이 필요한 run(규칙이 원인을 냈는데 대조를 통과한 AI 판정이 다르다)만 Slack에 알린다.
//   node scripts/triage/notify.mjs <run 디렉터리>          → 보낼 메시지를 출력만 한다
//   node scripts/triage/notify.mjs <run 디렉터리> --send   → TRIAGE_SLACK_WEBHOOK_URL로 보낸다
// triage.mjs가 만든 <run>/triage/summary.json, rule.json, ai.json을 읽는다.
// 메시지는 템플릿으로 조립한다. 수치는 요약 JSON 값을 그대로 넣고, AI 답에서는 의심 원인 이름과 확인 방법만 가져온다.
// 웹훅 URL은 비밀이라 환경변수로만 받고 출력하지 않는다(demo-alarms/lambda/slack_notify.py와 같은 원칙).
// 장애 알림에 붙이는 메시지(alertSlackText)는 alert-hook.mjs가 쓴다. 이쪽은 규칙과 AI가 같아도 보낸다(ADR-029 결정 11).
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
// Slack 굵게(*…*). 한글이 바로 붙으면 굵게 처리되지 않을 수 있어 앞뒤에 폭 없는 공백(U+200B)을 둔다.
export const bold = (s) => `​*${String(s).replace(/\*/g, "")}*​`;
// AI 확인 방법은 여러 문장이라 첫 문장만 쓴다.
export function firstSentence(s) {
  const t = String(s ?? "").trim().split(/(?<=[.;])\s+/)[0].replace(/[.;]$/, "");
  return t.length > 160 ? t.slice(0, 159) + "…" : t;
}
// AI 원문은 "확인한다"체·명사형이 섞여 메시지 본문(~요체)과 맞게 끝말만 바꾼다. 패턴에 없는 끝말은 그대로 둔다.
export function softEnding(s) {
  return s.replace(/해야 한다$/, "해야 해요").replace(/(확인|수집|대조|비교)(한다)?$/, "$1해 보기").replace(/본다$/, "보기").replace(/한다$/, "하기");
}

// 규칙이 어느 갈래로 원인을 냈는지 사람 말로 푼다(rules.mjs classify의 갈래 순서와 같다).
function ruleSentence(s, rule, t = THRESHOLDS) {
  if (rule.cause === "redis") return `규칙으로는 ${bold("Redis 지연")}으로 나와요. Redis 명령 완료가 평균 ${bold(ms(s.api.redis.completion_ms_mean) + "ms")}였거든요.`;
  if (rule.cause === "db") return `규칙으로는 ${bold("DB 쪽")}으로 나와요. PostgreSQL 활성 세션이 평균 ${bold(num(s.db.active_mean))}로 평소보다 많았거든요.`;
  const cpu = `규칙으로는 ${bold("앱 CPU 부족")}으로 나와요.`;
  const thr = s.api.cgroup?.throttled_period_ratio;
  if (thr != null && thr > t.app_throttled_ratio) return `${cpu} api가 CPU 할당량에 막힌 주기가 ${bold(Math.round(thr * 100) + "%")}였거든요.`;
  const usage = s.api.hikari.usage_ms_mean;
  if (usage != null) return `${cpu} 커넥션을 한 번 잡으면 평균 ${bold(ms(usage) + "ms")}씩 쥐고 있었는데, Redis랑 DB 수치는 괜찮았거든요.`;
  return `${cpu} api 지표 응답이 최대 ${bold(ms(s.api.scrape_rtt_ms_max) + "ms")}까지 늦었는데, Redis랑 DB 수치는 괜찮았거든요.`;
}

// 기존 Alertmanager 웹훅을 같이 쓰므로 머리말로 자동 분석 메시지임을 구분한다.
export const PREFIX = "[초기 분석]";

// 첫 줄: 예매 p95·커넥션 대기·5xx. head는 "<머리말> <id>"(굵게), unit은 "run"·"구간" 같은 대상 이름이다.
function openingLine(head, unit, summary) {
  const b = summary.booking, acq = summary.api.hikari?.acquire_ms_mean;
  // CPU 할당량 갈래는 예매 p95가 이상 기준 아래여도 원인을 낸다(rules.mjs). 그때는 느려졌다고 쓰지 않는다.
  const slow = b.http5xx > 0 || b.p95_median_ms > THRESHOLDS.anomaly_p95_median_ms;
  const p95 = bold(ms(b.p95_median_ms) + "ms");
  let first = slow ? `${bold(head)} ${unit}에서 예매가 느려졌어요. p95가 ${p95}까지 올라갔고` : `${bold(head)} ${unit}은 예매 p95가 ${p95}라 아직 느리진 않은데`;
  first += acq != null ? `, 커넥션 하나 받는 데 평균 ${bold(ms(acq) + "ms")} 기다렸어요.` : slow ? "요." : " 눈여겨볼 수치가 있어요.";
  if (b.http5xx > 0) first += ` 5xx도 ${bold(num(b.http5xx) + "건")} 났어요.`;
  return first;
}

// AI 1·2순위 의심 원인의 확인 방법(첫 문장)
function confirmLines(h1, h2) {
  const lines = [`• ${esc(softEnding(firstSentence(h1.confirm)))}`];
  if (h2) lines.push(`• 아니면 "${esc(String(h2.name).replace(/\*/g, ""))}" 쪽: ${esc(softEnding(firstSentence(h2.confirm)))}`);
  return lines;
}

export function slackText(runId, summary, rule, ai) {
  const lines = [esc(openingLine(`${PREFIX} ${runId}`, "run", summary)), esc(ruleSentence(summary, rule))];
  const r = ai.result, [h1, h2] = r.hypotheses ?? [];
  if (r.verdict === "normal" || !h1) {
    lines.push(`그런데 AI는 ${bold("이상 없는 run")}으로 봤어요. 규칙 판정이 맞는지 한번 봐주시면 좋겠어요.`);
  } else {
    let ask = `그런데 AI는 ${bold(`"${esc(h1.name)}"`)} 쪽을 더 의심하고 있어서 한번 봐주시면 좋겠어요.`;
    if (h1.confidence === "low" || r.conclusion === "inconclusive") ask += " AI도 확정은 못 했대요.";
    lines.push(ask, ...confirmLines(h1, h2));
  }
  return lines.join("\n");
}

// 알림에 붙이는 분석은 규칙과 AI가 같은 답이어도 보낸다. 장애가 난 것은 이미 아는 상태라 "어느 쪽이 원인으로 보이는지"가 정보다.
// 갈릴 때만 사람 확인을 부탁한다(slackText와 같은 문장).
export const ALERT_PREFIX = "[장애 알림 분석]";

// 알림 구간 지표가 모자라 분석하지 못했을 때도 알린다. api가 지표 요청에도 응답하지 못한 것 자체가 단서다.
export function alertInvalidText(alertName, summary) {
  const head = bold(`${ALERT_PREFIX} ${alertName}`);
  const why = summary.validity.reasons.join(", ");
  const lines = [esc(`${head} 알림 구간은 지표가 모자라 원인 분석을 못 했어요(${why}).`)];
  // 지표 요청은 아웃박스 행 수 등을 DB에서 세므로(OperationalMetrics) DB가 느려도 늦어진다.
  if (/api 지표/.test(why)) lines.push("api가 지표 요청에도 제때 응답하지 못한 구간이에요. 지표 요청은 DB 쿼리도 하니까 api 요청 스레드와 DB 상태를 같이 봐 주시면 좋겠어요.");
  return lines.join("\n");
}

export function alertSlackText(alertName, summary, rule, ai) {
  const lines = [esc(openingLine(`${ALERT_PREFIX} ${alertName}`, "알림 구간", summary))];
  if (rule.verdict === "cause") lines.push(esc(ruleSentence(summary, rule)));
  else if (rule.verdict === "normal") lines.push("규칙으로는 이 구간 수치가 이상 기준 아래로 나와요.");
  else lines.push("규칙으로는 어느 원인 갈래에도 해당하지 않아 원인을 정하지 못했어요.");
  const checked = !!ai && !ai.error && ai.verification?.ok === true;
  if (!checked) {
    lines.push(ai?.error ? "AI 분석은 실패해서 이번엔 빠졌어요." : ai ? "AI 답은 근거 수치가 요약과 맞지 않아 빼 뒀어요." : "AI 분석은 부르지 않았어요.");
    return lines.join("\n");
  }
  const r = ai.result, [h1, h2] = r.hypotheses ?? [];
  const unsure = h1 && (h1.confidence === "low" || r.conclusion === "inconclusive") ? " AI도 확정은 못 했대요." : "";
  if (r.verdict === "normal" || !h1) {
    lines.push(rule.verdict === "cause" ? `그런데 AI는 ${bold("이상 없는 구간")}으로 봤어요. 규칙 판정이 맞는지 한번 봐주시면 좋겠어요.` : `AI도 ${bold("이상 없는 구간")}으로 봤어요.`);
  } else if (rule.verdict !== "cause") {
    lines.push(`AI는 ${bold(`"${esc(h1.name)}"`)} 쪽을 가장 의심하고 있어요.${unsure}`, ...confirmLines(h1, h2));
  } else if (pipelineVerdict(rule, ai).disagree) {
    lines.push(`그런데 AI는 ${bold(`"${esc(h1.name)}"`)} 쪽을 더 의심하고 있어서 한번 봐주시면 좋겠어요.${unsure}`, ...confirmLines(h1, h2));
  } else {
    lines.push(`AI도 같은 쪽(${bold(`"${esc(h1.name)}"`)})으로 봤어요.${unsure}`, ...confirmLines(h1));
  }
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
  const text = slackText(path.basename(path.resolve(runDir)), read("summary.json"), rule, ai);
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
