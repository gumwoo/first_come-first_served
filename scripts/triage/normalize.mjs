// run 원자료 → 분석용 요약 JSON(AI 없음). 입력은 infra/triage-lab/lab.sh가 만든 run 디렉터리다.
//   node scripts/triage/normalize.mjs <run 디렉터리>        → <run>/triage/summary.json
//
// 원칙
// - 수치는 여기서만 계산한다. 분석(규칙·AI)은 이 요약만 본다.
// - 구간 평균은 누적 지표(sum/count, CPU 시간)의 차분으로 구한다. 지표 응답이 밀려도 표본 시각과 무관하게 맞는다(IMP-043).
// - 측정 자체가 무효인 run은 validity에 사유를 남긴다. 무효 run은 원인 분석에 보내지 않는다.
// - 파일 이름·라벨·메모는 요약에 넣지 않는다. 수치와 예매 시작 기준 상대 시각만 담는다.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const SCHEMA = "triage.summary/v1";
const PRE_S = 2;          // 예매 시작 전 몇 초부터 볼지
const MAX_POST_S = 30;    // 수집기가 예매 시작 뒤 30초까지 돈다
// 측정 대상과 분리된 디스크 프로브(8KB 쓰기 + fdatasync)의 구간 중앙값이 이보다 느리면 저장소 지연이 낀 run이다.
// 평소 중앙값은 약 2ms다(IMP-043 probe2·4). PostgreSQL WAL 동기화 시간은 DB가 CPU를 못 받을 때도 부풀어 기준으로 쓰지 않는다.
const DISK_PROBE_LIMIT_MS = 10;
const BOOKING_STEPS = new Set(["hold", "order", "pay"]);

const round = (x, d = 1) => (x == null || !Number.isFinite(x) ? null : Math.round(x * 10 ** d) / 10 ** d);
export function quantile(xs, p) {
  const s = xs.filter(Number.isFinite).sort((a, b) => a - b);
  if (!s.length) return null;
  return s[Math.min(s.length - 1, Math.max(0, Math.ceil((p / 100) * s.length) - 1))];
}

function readLines(file) {
  return fs.existsSync(file) ? fs.readFileSync(file, "utf8").split(/\r?\n/).filter(Boolean) : [];
}

// k6: 사용자마다 "E2E {json}" 한 줄. 예매 단계(선점·주문·결제)만 본다.
export function parseBooking(lines) {
  const steps = { hold: [], order: [], pay: [] };
  let users = 0, http5xx = 0, userMaxMs = 0;
  for (const line of lines) {
    if (!line.startsWith("E2E ")) continue;
    users++;
    let total = 0;
    for (const c of JSON.parse(line.slice(4)).steps ?? []) {
      if (c.status >= 500) http5xx++;
      if (!BOOKING_STEPS.has(c.step) && !String(c.step).startsWith("hold")) continue;
      if (steps[c.step]) steps[c.step].push(c.ms);
      total += c.ms ?? 0;
    }
    userMaxMs = Math.max(userMaxMs, total);
  }
  const p95 = Object.fromEntries(Object.entries(steps).map(([k, v]) => [k, quantile(v, 95)]));
  return { users, http5xx, p95_ms: p95, p95_median_ms: quantile(Object.values(p95).filter((x) => x != null), 50), user_max_ms: userMaxMs };
}

// 1초 표본에서 [from, to] 구간의 누적 지표 차분. 구간을 감싸는 표본(직전·직후)을 쓴다.
export function windowDelta(rows, from, to, key) {
  const before = rows.filter((r) => r.t <= from).at(-1) ?? rows[0];
  const after = rows.find((r) => r.t >= to) ?? rows.at(-1);
  if (!before || !after || after.t <= before.t) return null;
  return { d: (after.v[key] ?? 0) - (before.v[key] ?? 0), dt: (after.t - before.t) / 1000, bracketed: before.t <= from && after.t >= to };
}

export function summarizeApi(rows, from, to) {
  const cpu = windowDelta(rows, from, to, "process_cpu_time_ns_total");
  const use = [windowDelta(rows, from, to, "hikaricp_connections_usage_seconds_sum"), windowDelta(rows, from, to, "hikaricp_connections_usage_seconds_count")];
  const acq = [windowDelta(rows, from, to, "hikaricp_connections_acquire_seconds_sum"), windowDelta(rows, from, to, "hikaricp_connections_acquire_seconds_count")];
  const red = [windowDelta(rows, from, to, "lettuce_command_completion_seconds_sum"), windowDelta(rows, from, to, "lettuce_command_completion_seconds_count")];
  const mean = ([s, c]) => (s && c && c.d > 0 ? (s.d / c.d) * 1000 : null);
  const inWin = rows.filter((r) => r.t >= from && r.t <= to);
  const max = (k) => (inWin.length ? Math.max(...inWin.map((r) => r.v[k] ?? 0)) : null);
  return {
    bracketed: Boolean(cpu?.bracketed),
    cpu_cores_mean: cpu ? round(cpu.d / 1e9 / cpu.dt, 2) : null,
    tomcat_busy_max: max("tomcat_threads_busy_threads"),
    hikari: {
      active_max: max("hikaricp_connections_active"),
      pending_max: max("hikaricp_connections_pending"),
      checkouts: use[1] ? use[1].d : null,
      usage_ms_mean: round(mean(use)),
      acquire_ms_mean: round(mean(acq)),
    },
    redis: { cmds: red[1] ? red[1].d : null, completion_ms_mean: round(mean(red), 2) },
    scrape_rtt_ms_max: inWin.length ? Math.max(...inWin.map((r) => r.v.rtt ?? 0)) : null,
    samples_in_window: inWin.length,
  };
}

export function summarizeDb(pgLines, walLines, from, to) {
  const rows = pgLines.map((l) => l.split("\t")).filter((c) => /^\d{4}-/.test(c[0]))
    .map((c) => ({ t: Date.parse(c[0]), active: +c[1], lock: +c[2], idleTx: +c[3], maxXact: c[5] === "" ? 0 : +c[5], waits: (c[6] || "").split(" ").filter(Boolean) }))
    .filter((r) => r.t >= from && r.t <= to);
  const waits = {};
  for (const r of rows) for (const w of r.waits) waits[w] = (waits[w] ?? 0) + 1;
  const wal = Object.fromEntries(walLines.map((l) => l.split("\t")).map((c) => [c[0], { syncs: +c[2], syncMs: +c[3] }]));
  const syncs = wal.before && wal.after ? wal.after.syncs - wal.before.syncs : null;
  return {
    samples: rows.length,
    active_mean: rows.length ? round(rows.reduce((a, r) => a + r.active, 0) / rows.length, 2) : null,
    active_max: rows.length ? Math.max(...rows.map((r) => r.active)) : null,
    idle_in_tx_mean: rows.length ? round(rows.reduce((a, r) => a + r.idleTx, 0) / rows.length, 2) : null,
    lock_wait_samples: rows.filter((r) => r.lock > 0).length,
    max_xact_s: rows.length ? Math.max(...rows.map((r) => r.maxXact)) : null,
    active_wait_events: waits,
    wal_syncs: syncs,
    wal_sync_ms_per_op: syncs > 0 ? round((wal.after.syncMs - wal.before.syncMs) / syncs, 2) : null,
  };
}

// 초 단위 시계열(예매 시작 기준). AI가 시간 흐름을 볼 수 있게 짧게 담는다.
function series(rows, sa, from, to) {
  const out = [];
  for (let i = 1; i < rows.length; i++) {
    const r = rows[i], p = rows[i - 1];
    if (r.t < from || r.t > to) continue;
    const dt = (r.t - p.t) / 1000;
    out.push({
      s: round((r.t - sa) / 1000, 0),
      cpu_cores: dt > 0 ? round(((r.v.process_cpu_time_ns_total ?? 0) - (p.v.process_cpu_time_ns_total ?? 0)) / 1e9 / dt, 2) : null,
      tomcat_busy: r.v.tomcat_threads_busy_threads ?? null,
      hikari_active: r.v.hikaricp_connections_active ?? null,
      hikari_pending: r.v.hikaricp_connections_pending ?? null,
    });
  }
  return out;
}

export function summarizeDisk(lines, from, to) {
  const ms = lines.map((l) => l.split(" ")).filter((c) => /^\d{4}-/.test(c[0]))
    .map((c) => ({ t: Date.parse(c[0]), ms: +c[1] })).filter((r) => r.t >= from && r.t <= to).map((r) => r.ms);
  return { samples: ms.length, fdatasync_ms_p50: quantile(ms, 50), fdatasync_ms_p95: quantile(ms, 95) };
}

export function normalize(runDir) {
  const meta = JSON.parse(fs.readFileSync(path.join(runDir, "meta.json"), "utf8"));
  const sa = Date.parse(meta.startAt);
  const booking = parseBooking(readLines(path.join(runDir, "book.log")));
  const postS = Math.min(MAX_POST_S, Math.max(15, Math.ceil(booking.user_max_ms / 1000) + 2));
  const from = sa - PRE_S * 1000, to = sa + postS * 1000;
  const rows = readLines(path.join(runDir, "sampler.jsonl")).map((l) => JSON.parse(l)).filter((r) => !r.error && r.api).map((r) => ({ t: r.t, v: { ...r.api } }));
  const api = summarizeApi(rows, from, to);
  const db = summarizeDb(readLines(path.join(runDir, "pg.tsv")), readLines(path.join(runDir, "wal.tsv")), from, to);
  const disk = summarizeDisk(readLines(path.join(runDir, "disk.txt")), from, to);

  const reasons = [];
  if (booking.users < meta.users) reasons.push(`예매 결과가 ${booking.users}/${meta.users}명분이다`);
  if (!api.bracketed) reasons.push("api 누적 지표가 분석 구간을 감싸지 못한다");
  if (db.samples < postS * 2) reasons.push(`PostgreSQL 표본이 ${db.samples}개뿐이다`);
  if (disk.samples < postS) reasons.push(`디스크 프로브 표본이 ${disk.samples}개뿐이다`);
  else if (disk.fdatasync_ms_p50 > DISK_PROBE_LIMIT_MS) reasons.push(`디스크 프로브 fdatasync 중앙값 ${disk.fdatasync_ms_p50}ms(저장소 지연, 기준 ${DISK_PROBE_LIMIT_MS}ms)`);

  return {
    schema: SCHEMA,
    window: { from_s: -PRE_S, to_s: postS },
    validity: { valid: reasons.length === 0, reasons },
    booking: { users: booking.users, http5xx: booking.http5xx, p95_ms: booking.p95_ms, p95_median_ms: booking.p95_median_ms },
    api,
    db,
    disk,
    series: series(rows, sa, from, to),
  };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  const dir = process.argv[2];
  if (!dir) { console.error("사용: node scripts/triage/normalize.mjs <run 디렉터리>"); process.exit(2); }
  const s = normalize(dir);
  fs.mkdirSync(path.join(dir, "triage"), { recursive: true });
  fs.writeFileSync(path.join(dir, "triage", "summary.json"), JSON.stringify(s, null, 2) + "\n");
  console.log(JSON.stringify({ valid: s.validity.valid, reasons: s.validity.reasons, p95_median_ms: s.booking.p95_median_ms }));
}
