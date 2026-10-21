// 장애 초기 분석 진입점: 정규화 → 규칙 분류 → (이상이 있는 유효 run만) AI 분석 → 수치 대조 → 보고서.
//   node scripts/triage/triage.mjs <run 디렉터리> [--no-ai]
// 결과: <run>/triage/summary.json, rule.json, ai.json(AI를 부른 경우), report.md
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { normalize } from "./normalize.mjs";
import { classify } from "./rules.mjs";
import { analyze } from "./analyze.mjs";
import { verify } from "./verify.mjs";

// AI를 부를지: 측정이 무효이거나 규칙이 이상 없음으로 본 run은 부르지 않는다(비용과 오탐을 줄인다).
export function shouldCallAi(summary, rule) {
  if (!summary.validity.valid) return { call: false, reason: "측정 무효" };
  if (rule.verdict === "normal") return { call: false, reason: "규칙상 이상 없음" };
  return { call: true };
}

// 대조에 실패한 답은 원인 판정으로 쓰지 않는다.
export function finalVerdict(ai, check) {
  if (!check.ok) return { verdict: "undetermined", cause: null, note: "근거 수치 대조 실패" };
  const top = ai.result.candidates?.[0];
  return ai.result.verdict === "cause" && top ? { verdict: "cause", cause: top.cause, note: null } : { verdict: ai.result.verdict, cause: null, note: null };
}

export function report(summary, rule, ai) {
  const lines = ["# 초기 원인 분석", ""];
  lines.push(`- 측정 유효: ${summary.validity.valid ? "예" : "아니오(" + summary.validity.reasons.join(", ") + ")"}`);
  lines.push(`- 예매 p95(세 단계 중앙값): ${summary.booking.p95_median_ms}ms, 5xx ${summary.booking.http5xx}건`);
  lines.push(`- 규칙 분류: ${rule.verdict}${rule.cause ? " / " + rule.cause : ""} (${rule.reasons.join("; ")})`);
  if (!ai) return lines.join("\n") + "\n";
  if (ai.error) return [...lines, `- AI 분석 실패: ${ai.error}`].join("\n") + "\n";
  const f = ai.final;
  lines.push(`- AI 분류: ${f.verdict}${f.cause ? " / " + f.cause : ""}${f.note ? " (" + f.note + ")" : ""}`, "");
  if (!ai.verification.ok) {
    lines.push("## 대조 실패", ...ai.verification.failures.map((x) => `- ${x.field != null ? x.field + ": " : ""}${x.reason}${x.actual != null ? ` (답 ${x.value}, 실제 ${x.actual})` : ""}`), "");
    return lines.join("\n") + "\n";
  }
  const r = ai.result;
  const section = (title, xs, fmt) => { if ((xs ?? []).length) lines.push(title, ...xs.map(fmt), ""); };
  section("## 원인 후보", r.candidates, (c, i) => `${i + 1}. ${c.cause} (${c.confidence}): ${c.rationale}`);
  section("## 근거(요약 JSON과 대조 완료)", r.evidence, (e) => `- ${e.claim} \`${e.field}\` = ${e.value}`);
  section("## 반대 지표", r.counter_evidence, (e) => `- ${e.claim} \`${e.field}\` = ${e.value}`);
  section("## 확인하지 못한 데이터", r.unchecked, (x) => `- ${x}`);
  section("## 다음에 확인할 것", r.next_checks, (x) => `- ${x}`);
  lines.push(`_${ai.model} · ${ai.prompt_version} · 입력 ${ai.usage.input_tokens} / 출력 ${ai.usage.output_tokens} 토큰_`);
  return lines.join("\n") + "\n";
}

export async function triage(runDir, { useAi = true, client } = {}) {
  const out = path.join(runDir, "triage");
  fs.mkdirSync(out, { recursive: true });
  fs.rmSync(path.join(out, "ai.json"), { force: true }); // 이전 실행의 AI 결과가 남지 않게
  const summary = normalize(runDir);
  const rule = classify(summary);
  fs.writeFileSync(path.join(out, "summary.json"), JSON.stringify(summary, null, 2) + "\n");
  fs.writeFileSync(path.join(out, "rule.json"), JSON.stringify(rule, null, 2) + "\n");
  let ai = null;
  const gate = shouldCallAi(summary, rule);
  if (useAi && gate.call) {
    try {
      const res = await analyze(summary, { client });
      const verification = verify(res.result, summary);
      ai = { ...res, verification, final: finalVerdict(res, verification) };
    } catch (e) {
      ai = { error: e.message, final: { verdict: "undetermined", cause: null, note: "AI 분석 실패" } };
    }
    fs.writeFileSync(path.join(out, "ai.json"), JSON.stringify(ai, null, 2) + "\n");
  }
  fs.writeFileSync(path.join(out, "report.md"), report(summary, rule, ai));
  return { summary, rule, ai, gate };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  const dir = process.argv[2];
  if (!dir) { console.error("사용: node scripts/triage/triage.mjs <run 디렉터리> [--no-ai]"); process.exit(2); }
  const { rule, ai, gate } = await triage(dir, { useAi: !process.argv.includes("--no-ai") });
  console.log(JSON.stringify({ rule: { verdict: rule.verdict, cause: rule.cause }, ai: ai ? ai.final : { skipped: gate.reason ?? "--no-ai" } }));
}
