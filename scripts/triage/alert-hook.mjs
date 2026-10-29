// 알림 → 원인 분석 → Slack. Alertmanager 웹훅을 받아, 알림 구간 지표를 Prometheus에서 읽어 run 분석과 같은 순서
// (요약 → 규칙 → AI → 수치 대조 → 최종 판정)로 분석하고 Slack 메시지를 만든다.
//   node scripts/triage/alert-hook.mjs [--port 18099] [--prom http://127.0.0.1:19090] [--send] [--no-ai]
// 결과: artifacts/triage-lab/alerts/<시각>-<알림 이름>/ (alert.json, summary.json, rule.json, ai.json, report.md, slack.txt)
// --send일 때만 TRIAGE_SLACK_WEBHOOK_URL로 보낸다. 키·웹훅 URL은 환경변수로만 받는다.
//
// run 분석과 다른 점: 알림이 이미 이상을 알렸으므로 규칙이 이상 없음으로 봐도 측정이 유효하면 AI를 부른다(ADR-029 결정 11).
// 조치는 하지 않는다. 메시지는 사람에게 확인을 요청할 뿐이다(결정 6).
import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { promClient, promSummary, bookingWindow } from "./prom-summary.mjs";
import { classify } from "./rules.mjs";
import { analyze } from "./analyze.mjs";
import { verify } from "./verify.mjs";
import { finalVerdict, report } from "./triage.mjs";
import { alertInvalidText, alertSlackText, postSlack } from "./notify.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const OUT = path.resolve(HERE, "../../artifacts/triage-lab/alerts");
// 장애가 심하면 응답이 10초 넘게 하나도 끝나지 않는 구간이 있어, 그보다 길게 조용해야 묶음이 끝났다고 본다.
const QUIET_S = 12, WAIT_MAX_S = 150;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const stamp = (t) => new Date(t).toISOString().replace(/[-:]/g, "").replace("T", "-").slice(0, 15);

// 구간 끝을 정할 수 있을 때까지 기다린다: 예매 p95 구간 끝이 지났고(지표가 들어올 여유 3초), 예매 요청이 QUIET_S초 동안 없을 때.
// 최대 WAIT_MAX_S초.
export async function waitQuiet(prom, alertAt, { now = Date.now, wait = sleep } = {}) {
  const until = now() + WAIT_MAX_S * 1000;
  while (now() < until) {
    const w = await bookingWindow(prom, alertAt);
    if (w && now() >= w.bookTo + 3000 && now() - w.last >= QUIET_S * 1000) return;
    await wait(2000);
  }
}

// windows: 이미 분석한 예매 구간(시작 시각). 한 장애에 알림 두 개(느림·멈춤)가 울려도 한 번만 분석한다.
export async function handleAlert(alert, { prom, useAi = true, send = false, client, fetchImpl, out = OUT, now = Date.now, wait, windows = new Set() } = {}) {
  const name = alert.labels?.alertname ?? "alert";
  const at = Date.parse(alert.startsAt);
  await waitQuiet(prom, at, { now, wait });
  const w = await bookingWindow(prom, at);
  if (w && windows.has(w.sa)) return { dir: null, sent: false, reason: "같은 예매 구간을 이미 분석했다" };
  if (w) windows.add(w.sa);
  const summary = await promSummary(prom, at);
  const rule = classify(summary);
  let ai = null;
  if (useAi && summary.validity.valid) {
    try {
      const res = await analyze(summary, { client });
      const verification = verify(res.result, summary);
      ai = { ...res, verification, final: finalVerdict(res, verification) };
    } catch (e) {
      ai = { error: e.message, final: { verdict: "undetermined", cause: null, note: "AI 분석 실패" } };
    }
  }
  const text = summary.validity.valid ? alertSlackText(name, summary, rule, ai) : alertInvalidText(name, summary);
  const dir = path.join(out, `${stamp(at)}-${name}`);
  fs.mkdirSync(dir, { recursive: true });
  const write = (f, x) => fs.writeFileSync(path.join(dir, f), typeof x === "string" ? x : JSON.stringify(x, null, 2) + "\n");
  write("alert.json", alert);
  write("summary.json", summary);
  write("rule.json", rule);
  if (ai) write("ai.json", ai);
  write("report.md", report(summary.booking ? summary : { ...summary, booking: { p95_median_ms: null, http5xx: null } }, rule, ai));
  // 측정이 무효여도(지표가 끊긴 구간 등) 분석하지 못한 이유를 알린다.
  write("slack.txt", text + "\n");
  if (!send) return { dir, sent: false, reason: "미리 보기", text };
  return { dir, ...(await postSlack(text, { fetchImpl })), text };
}

// Alertmanager 웹훅 본문에서 새로 울린 알림만 고른다(같은 알림의 재전송은 한 번만 분석한다).
export function firingAlerts(body, seen) {
  const out = [];
  for (const a of body?.alerts ?? []) {
    const key = `${a.fingerprint ?? a.labels?.alertname}@${a.startsAt}`;
    if (a.status !== "firing" || seen.has(key)) continue;
    seen.add(key);
    out.push(a);
  }
  return out;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  const arg = (k, d) => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : d; };
  const port = Number(arg("--port", 18099));
  const prom = promClient(arg("--prom", "http://127.0.0.1:19090"));
  const opts = { prom, useAi: !process.argv.includes("--no-ai"), send: process.argv.includes("--send"), windows: new Set() };
  const seen = new Set();
  http.createServer((req, res) => {
    if (req.method !== "POST" || req.url !== "/alert") { res.writeHead(404).end(); return; }
    let raw = "";
    req.on("data", (c) => (raw += c));
    req.on("end", () => {
      res.writeHead(200).end(); // Alertmanager는 응답을 기다리지 않게 바로 돌려준다. 분석은 뒤에서 한다
      let body;
      try { body = JSON.parse(raw); } catch { console.error("웹훅 본문이 JSON이 아니다"); return; }
      for (const a of firingAlerts(body, seen)) {
        console.log(`[${new Date().toISOString()}] 알림 ${a.labels?.alertname} (${a.startsAt}) 분석 시작`);
        handleAlert(a, opts)
          .then((r) => console.log(`  → ${r.dir ?? "-"} ${r.sent ? "Slack 전송" : r.reason}`))
          .catch((e) => console.error(`  → 분석 실패: ${e.message}`));
      }
    });
  }).listen(port, () => console.log(`알림 분석 훅 대기: :${port}/alert (Slack ${opts.send ? "전송" : "미리 보기"}, AI ${opts.useAi ? "켬" : "끔"})`));
}
