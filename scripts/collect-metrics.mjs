// 측정 결과(JSON) → MD 자동 전사.
// MD 안의 <!-- AUTO:<key>:START --> ~ <!-- AUTO:<key>:END --> 사이만 교체하고,
// 사람이 쓴 서사 부분은 건드리지 않는다.
//
// 사용:
//   node scripts/collect-metrics.mjs
// 동작:
//   1) benchmarks/*-before.json, *-after.json 을 수집
//   2) k6 summary 포맷에서 핵심 지표 추출(p95/실패율/처리량 등)
//   3) docs/improvements/METRICS.md 의 AUTO 블록을 갱신
//
// 지금은 benchmarks/에 실제 결과가 없으므로 "대상 없음"으로 통과한다(틀만 동작).

import fs from "node:fs";
import path from "node:path";
import url from "node:url";

const ROOT = path.resolve(path.dirname(url.fileURLToPath(import.meta.url)), "..");
const BENCH = path.join(ROOT, "benchmarks");

/** MD에서 AUTO 마커 블록을 새 내용으로 교체. 마커 없으면 변경 없음. */
export function replaceAutoBlock(md, key, content) {
  const start = `<!-- AUTO:${key}:START -->`;
  const end = `<!-- AUTO:${key}:END -->`;
  const re = new RegExp(`${escapeRe(start)}[\\s\\S]*?${escapeRe(end)}`);
  const block = `${start}\n${content}\n${end}`;
  if (!re.test(md)) return md; // 마커 없으면 그대로
  return md.replace(re, block);
}
function escapeRe(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/** k6 --summary-export JSON에서 핵심 지표 추출(없으면 null). */
export function parseK6(json) {
  const m = json.metrics ?? {};
  const pick = (metric, field) => m[metric]?.[field] ?? m[metric]?.values?.[field] ?? null;
  return {
    p95: pick("http_req_duration", "p(95)"),
    avg: pick("http_req_duration", "avg"),
    failRate: pick("http_req_failed", "rate"),
    reqs: pick("http_reqs", "count"),
    rps: pick("http_reqs", "rate"),
  };
}

function fmt(v, unit = "") {
  if (v === null || v === undefined) return "—";
  if (typeof v === "number") return `${Math.round(v * 100) / 100}${unit}`;
  return String(v);
}

function main() {
  if (!fs.existsSync(BENCH)) {
    console.log("benchmarks/ 없음 — 수집 대상 없음(틀만 동작).");
    return;
  }
  const files = fs.readdirSync(BENCH).filter((f) => f.endsWith(".json"));
  if (files.length === 0) {
    console.log("벤치 결과 JSON 없음 — 측정 후 다시 실행하세요(틀만 동작).");
    return;
  }

  // topic 별로 before/after 묶기
  const topics = {};
  for (const f of files) {
    const m = f.match(/^(.*)-(before|after)\.json$/);
    if (!m) continue;
    (topics[m[1]] ??= {})[m[2]] = JSON.parse(fs.readFileSync(path.join(BENCH, f), "utf8"));
  }

  const rows = [];
  for (const [topic, ba] of Object.entries(topics)) {
    const b = ba.before ? parseK6(ba.before) : null;
    const a = ba.after ? parseK6(ba.after) : null;
    rows.push(
      `| ${topic} | p95 | ${fmt(b?.p95, "ms")} | ${fmt(a?.p95, "ms")} |`,
      `| ${topic} | 실패율 | ${fmt(b?.failRate)} | ${fmt(a?.failRate)} |`,
      `| ${topic} | RPS | ${fmt(b?.rps)} | ${fmt(a?.rps)} |`
    );
  }

  const table = ["| topic | 지표 | before | after |", "|---|---|---|---|", ...rows].join("\n");
  const metricsPath = path.join(ROOT, "docs", "improvements", "METRICS.md");
  let md = fs.readFileSync(metricsPath, "utf8");
  const updated = replaceAutoBlock(md, "PERF", table);
  if (updated !== md) {
    fs.writeFileSync(metricsPath, updated);
    console.log(`✓ METRICS.md PERF 블록 갱신 (${rows.length / 3} topic)`);
  } else {
    console.log("METRICS.md에 <!-- AUTO:PERF:START/END --> 마커 없음 — 갱신 생략.");
  }
}

// 직접 실행 시에만 동작(테스트에서 import 가능하도록)
const entry = process.argv[1] ? url.pathToFileURL(process.argv[1]).href : null;
if (entry && import.meta.url === entry) {
  main();
}
