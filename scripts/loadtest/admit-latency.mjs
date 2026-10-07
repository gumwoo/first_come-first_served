#!/usr/bin/env node
// 입장 인지 지연(loadtest-100k-plan §8 "대기 상태 전달"): 서버가 승격한 시각 → 대기자(폴링 발생기)가 ADMITTED를 처음 본 시각.
//
// 서버 쪽: api 로그의 감사 줄 `queue.audit kind=admit event=… token=<ref> … at=<ms>`(QueueAudit — at은 승격 스크립트가 반환된 시각).
// 클라이언트 쪽: poll-hold.mjs의 tokens.jsonl(ref·admittedSeenAt). 둘을 ref(SHA-256 앞 8바이트)로 맞춘다.
// 대기열 SSE를 없애(#351) 입장은 다음 폴링 때 알게 되므로, 이 지연은 대략 그 사용자의 폴링 간격(retryAfterMs + jitter)
// 안쪽이어야 한다 — 그보다 길면 retryAfterMs 근거(정원씩 빠진다)가 실제 승격 속도보다 느렸다는 뜻이다(사용자 결정: 측정 뒤 조정).
//
// 시계: 서버 at은 파드 시계, admittedSeenAt은 발생기 시계다. 둘 다 시간 동기화를 쓰지만 차이는 그대로 섞인다 — 음수 지연은
// 시계 차이(또는 응답 직후 승격)로만 생기므로 따로 센다.
//
//   node scripts/loadtest/admit-latency.mjs --audit api.log <run>/poll-g1/tokens.jsonl [<run>/poll-g2/tokens.jsonl ...]
//
// 출력(JSON): 승격 수, 클라이언트가 ADMITTED를 본 수, 둘이 맞은 수, 지연 분위수(ms), 음수 지연 수, 한쪽에만 있는 수.
import { createReadStream, readFileSync, realpathSync } from "node:fs";
import { createInterface } from "node:readline";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";

const AUDIT = /queue\.audit kind=admit .*?\btoken=([0-9a-f]{16})\b.*?\bat=(\d+)\b/;

/** 감사 줄에서 ref → 승격 시각(ms). 같은 ref가 여러 번이면 첫 승격을 쓴다. */
export function parseAdmits(lines) {
  const m = new Map();
  for (const line of lines) {
    const x = AUDIT.exec(line);
    if (!x) continue;
    const at = Number(x[2]);
    if (!m.has(x[1]) || at < m.get(x[1])) m.set(x[1], at);
  }
  return m;
}

const pct = (sorted, p) => (sorted.length ? sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))] : null);

/** admits(ref → at), seen(ref → admittedSeenAt)으로 지연 요약. */
export function summarize(admits, seen) {
  const lat = [];
  let negative = 0;
  let seenWithoutAdmit = 0;
  for (const [r, seenAt] of seen) {
    const at = admits.get(r);
    if (at === undefined) {
      seenWithoutAdmit++;
      continue;
    }
    const d = seenAt - at;
    if (d < 0) negative++;
    lat.push(d);
  }
  lat.sort((a, b) => a - b);
  let admitWithoutSeen = 0;
  for (const r of admits.keys()) if (!seen.has(r)) admitWithoutSeen++;
  return {
    admitsInAudit: admits.size, admittedSeenByClient: seen.size, matched: lat.length, negative,
    seenWithoutAdmit, admitWithoutSeen,
    latencyMs: { p50: pct(lat, 0.5), p95: pct(lat, 0.95), p99: pct(lat, 0.99), max: lat.at(-1) ?? null, min: lat[0] ?? null },
  };
}

async function main() {
  const { values: opts, positionals: files } = parseArgs({ allowPositionals: true, options: { audit: { type: "string" } } });
  if (!opts.audit || files.length === 0) {
    console.error("사용: admit-latency.mjs --audit <api 로그> <poll tokens.jsonl> [...]");
    process.exit(2);
  }
  const lines = [];
  const rl = createInterface({ input: createReadStream(opts.audit), crlfDelay: Infinity });
  for await (const line of rl) if (line.includes("queue.audit kind=admit")) lines.push(line);
  const admits = parseAdmits(lines);
  const seen = new Map();
  for (const f of files) {
    for (const line of readFileSync(f, "utf8").split("\n")) {
      if (!line.trim()) continue;
      const t = JSON.parse(line);
      if (t.admittedSeenAt != null) seen.set(t.ref, t.admittedSeenAt);
    }
  }
  console.log(JSON.stringify(summarize(admits, seen), null, 1));
}

const isMain = (() => {
  try {
    return !!process.argv[1] && realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url));
  } catch {
    return false;
  }
})();
if (isMain) await main();
