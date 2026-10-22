// 평가: 원인을 알고 주입한 run 묶음에서 규칙 분류기와 AI 분석을 같은 요약으로 판정하고, 라벨과 맞춰 채점한다.
//   node scripts/triage/eval.mjs --runs <ids.txt> --labels <labels.jsonl> --runs-dir <runs 디렉터리> --out <결과 디렉터리> [--no-ai]
//   ids.txt: 한 줄에 "id fault level try 판정 시각"(평가 측정 기록). 판정이 valid인 줄만 쓴다(invalid·excluded 줄은 뺀다).
//
// - 판정 순서를 섞고, AI 입력은 요약 JSON뿐이다(run id·라벨은 채점 때만 쓴다).
// - AI는 유효 run 전부에 부른다(AI 단독). 파이프라인(규칙이 정상이면 AI를 부르지 않음, triage.mjs) 결과도 따로 계산한다.
// - 결과는 --out에만 쓴다. run 디렉터리의 triage/ 결과는 건드리지 않는다.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { normalize } from "./normalize.mjs";
import { classify } from "./rules.mjs";
import { analyze } from "./analyze.mjs";
import { verify } from "./verify.mjs";
import { finalVerdict } from "./triage.mjs";

const EXPECTED = { none: "normal", redis: "redis", db: "db", cpu: "app_cpu" };
// 공개 단가(100만 토큰당, 10만 토큰 이하 프롬프트). 모델이 바뀌면 함께 고친다.
const PRICE = { "claude-haiku-5-5": { in: 0.10, out: 0.50 } };

// 판정 하나를 범주 코드로: 원인이면 원인, 정상이면 normal, 그 밖(판단 불가)은 undetermined
export const label = (v) => (v.verdict === "cause" ? v.cause : v.verdict === "normal" ? "normal" : "undetermined");

export function score(rows) {
  const out = {};
  for (const who of ["rule", "ai", "pipeline"]) {
    const faults = rows.filter((r) => r.expected !== "normal"), normals = rows.filter((r) => r.expected === "normal");
    out[who] = {
      fault_correct: faults.filter((r) => r[who] === r.expected).length, fault_total: faults.length,
      fault_undetermined: faults.filter((r) => r[who] === "undetermined").length,
      normal_false_positive: normals.filter((r) => r[who] !== "normal" && r[who] !== "undetermined").length,
      normal_undetermined: normals.filter((r) => r[who] === "undetermined").length, normal_total: normals.length,
    };
  }
  return out;
}

function shuffle(xs) {
  const a = [...xs];
  for (let i = a.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [a[i], a[j]] = [a[j], a[i]]; }
  return a;
}

export async function evaluate({ ids, labels, runsDir, outDir, useAi = true, client }) {
  fs.mkdirSync(outDir, { recursive: true });
  const rows = [];
  for (const id of shuffle(ids)) {
    const summary = normalize(path.join(runsDir, id));
    if (!summary.validity.valid) { rows.push({ id, skipped: summary.validity.reasons }); continue; }
    const rule = classify(summary);
    let ai = null;
    if (useAi) {
      try {
        const res = await analyze(summary, { client });
        const verification = verify(res.result, summary);
        ai = { ...res, verification, final: finalVerdict(res, verification) };
      } catch (e) {
        ai = { error: e.message, final: { verdict: "undetermined", cause: null, note: "AI 분석 실패" } };
      }
    }
    const lab = labels[id];
    const r = {
      id, fault: lab.fault, level: lab.level, expected: EXPECTED[lab.fault],
      rule: label(rule), ai: ai ? label(ai.final) : null,
      pipeline: rule.verdict === "normal" ? "normal" : ai ? label(ai.final) : null,
      ai_verify_ok: ai?.verification?.ok ?? null, ai_error: ai?.error ?? null,
      model: ai?.model ?? null, usage: ai?.usage ?? null,
    };
    rows.push(r);
    fs.writeFileSync(path.join(outDir, `${id}.json`), JSON.stringify({ ...r, summary, rule_detail: rule, ai_detail: ai }, null, 2) + "\n");
  }
  const scored = rows.filter((r) => !r.skipped);
  const tokens = scored.filter((r) => r.usage).map((r) => r.usage);
  const price = PRICE[scored.find((r) => r.model)?.model];
  const cost = price ? tokens.reduce((a, u) => a + (u.input_tokens * price.in + u.output_tokens * price.out) / 1e6, 0) : null;
  const summary = {
    runs: rows.length, skipped: rows.filter((r) => r.skipped).map((r) => r.id),
    score: useAi ? score(scored) : { rule: score(scored.map((r) => ({ ...r, ai: r.rule, pipeline: r.rule }))).rule, ai: null, pipeline: null },
    ai_verify_failures: scored.filter((r) => r.ai_verify_ok === false).length,
    ai_errors: scored.filter((r) => r.ai_error).length,
    tokens: tokens.length ? {
      calls: tokens.length,
      input_mean: Math.round(tokens.reduce((a, u) => a + u.input_tokens, 0) / tokens.length),
      output_mean: Math.round(tokens.reduce((a, u) => a + u.output_tokens, 0) / tokens.length),
      cost_usd_total: cost != null ? Math.round(cost * 10000) / 10000 : null, // 계산(공개 단가)
    } : null,
  };
  fs.writeFileSync(path.join(outDir, "rows.json"), JSON.stringify(rows, null, 2) + "\n");
  fs.writeFileSync(path.join(outDir, "summary.json"), JSON.stringify(summary, null, 2) + "\n");
  return { rows, summary };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  const arg = (k) => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : null; };
  const [runsFile, labelsFile, runsDir, outDir] = ["--runs", "--labels", "--runs-dir", "--out"].map(arg);
  if (!runsFile || !labelsFile || !runsDir || !outDir) {
    console.error("사용: node scripts/triage/eval.mjs --runs <ids.txt> --labels <labels.jsonl> --runs-dir <runs> --out <dir> [--no-ai]");
    process.exit(2);
  }
  const ids = fs.readFileSync(runsFile, "utf8").split(/\r?\n/).filter((l) => /\svalid\b/.test(l) && !/invalid/.test(l)).map((l) => l.split(/\s+/)[0]);
  const labels = Object.fromEntries(fs.readFileSync(labelsFile, "utf8").trim().split(/\r?\n/).map((l) => JSON.parse(l)).map((l) => [l.id, l]));
  const { summary } = await evaluate({ ids, labels, runsDir, outDir, useAi: !process.argv.includes("--no-ai") });
  console.log(JSON.stringify(summary, null, 2));
}
