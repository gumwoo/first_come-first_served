// run 원자료 → 분석용 요약 JSON(AI 없음). 입력은 infra/triage-lab/lab.sh가 만든 run 디렉터리다.
//   node scripts/triage/normalize.mjs <run 디렉터리>        → <run>/triage/summary.json
//
// 원칙
// - 수치는 여기서만 계산한다. 분석(규칙·AI)은 이 요약만 본다.
// - 구간 평균은 누적 지표(sum/count, CPU 시간)의 차분으로 구한다. 비율 평균(점유·획득·Redis)은 지표 응답이 밀려도 맞는다(IMP-043).
// - 표본 값은 응답을 받은 시각(요청 시각 + 응답 시간)의 상태로 본다. 지표 응답이 10초씩 밀리는 run이 있어서다.
// - 측정 자체가 무효인 run은 validity에 사유를 남긴다. 무효 run은 원인 분석에 보내지 않는다.
// - 파일 이름·라벨·메모는 요약에 넣지 않는다. 수치와 예매 시작 기준 상대 시각만 담는다.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const SCHEMA = "triage.summary/v1";
const PRE_S = 2;          // 예매 시작 전 몇 초부터 볼지
const MAX_POST_S = 30;    // 분석 구간 끝의 상한. api 지표 수집기는 예매 시작 뒤 75초까지 돈다(infra/triage-lab/lab.sh)
// 측정 대상과 분리된 디스크 프로브(8KB 쓰기 + fdatasync)의 구간 중앙값이 이보다 느리면 저장소 지연이 낀 run이다.
// 평소 중앙값은 약 2ms다(IMP-043 probe2·4). PostgreSQL WAL 동기화 시간은 DB가 CPU를 못 받을 때도 부풀어 기준으로 쓰지 않는다.
const DISK_PROBE_LIMIT_MS = 10;
// 꼬리(p95)도 본다. eval5 72회의 구간 p95는 65ms 이하(33회)와 108ms 이상(39회)으로 갈렸고, 108ms 이상 run에 AI 오답과
// 정상 오탐이 몰렸다(IMP-052 §5.4). 같은 run의 예매 전 구간에도 꼬리 지연이 있어 예매 부하가 아닌 측정 환경 잡음으로 본다(IMP-053).
const DISK_PROBE_P95_LIMIT_MS = 100;
const QUEUE_STEPS = new Set(["queue_entry", "queue_status"]);

const round = (x, d = 1) => (x == null || !Number.isFinite(x) ? null : Math.round(x * 10 ** d) / 10 ** d);
export function quantile(xs, p) {
  const s = xs.filter(Number.isFinite).sort((a, b) => a - b);
  if (!s.length) return null;
  return s[Math.min(s.length - 1, Math.max(0, Math.ceil((p / 100) * s.length) - 1))];
}

function readLines(file) {
  return fs.existsSync(file) ? fs.readFileSync(file, "utf8").split(/\r?\n/).filter(Boolean) : [];
}

// 예매 시작부터 이 사용자가 끝날 때까지(ms). 같은 단계가 이어진 묶음은 동시 요청(http.batch)이라 가장 긴 것만,
// 서로 다른 단계는 차례로 일어나 더한다. 예매 시작보다 늦게 출발한 만큼(t0LagMs)도 더한다.
export function userDurationMs(rec) {
  let total = Math.max(0, rec.t0LagMs ?? 0), prev = null, groupMax = 0;
  for (const c of rec.steps ?? []) {
    if (QUEUE_STEPS.has(c.step)) continue;
    if (c.step !== prev) { total += groupMax; groupMax = 0; prev = c.step; }
    groupMax = Math.max(groupMax, c.ms ?? 0);
  }
  return total + groupMax;
}

// k6: 사용자마다 "E2E {json}" 한 줄. p95는 예매 세 단계(선점·주문·결제)로 본다.
export function parseBooking(lines) {
  const steps = { hold: [], order: [], pay: [] };
  let users = 0, http5xx = 0, userMaxMs = 0;
  for (const line of lines) {
    if (!line.startsWith("E2E ")) continue;
    users++;
    const rec = JSON.parse(line.slice(4));
    for (const c of rec.steps ?? []) {
      if (c.status >= 500) http5xx++;
      if (steps[c.step]) steps[c.step].push(c.ms);
    }
    userMaxMs = Math.max(userMaxMs, userDurationMs(rec));
  }
  const p95 = Object.fromEntries(Object.entries(steps).map(([k, v]) => [k, quantile(v, 95)]));
  return { users, http5xx, p95_ms: p95, p95_median_ms: quantile(Object.values(p95).filter((x) => x != null), 50), user_max_ms: userMaxMs };
}

// 1초 표본에서 [from, to] 구간의 누적 지표 차분. 구간을 감싸는 표본을 쓴다: 구간 시작 전에 읽힌 마지막 표본(r.at ≤ from)과
// 구간 끝 뒤에 요청한 첫 표본(r.t ≥ to). r.t는 요청 시각, r.at은 응답을 받은 시각이다.
export function windowDelta(rows, from, to, key) {
  const before = rows.filter((r) => r.at <= from).at(-1) ?? rows[0];
  const after = rows.find((r) => r.t >= to) ?? rows.at(-1);
  if (!before || !after || after.t <= before.t) return null;
  return { d: (after.v[key] ?? 0) - (before.v[key] ?? 0), dt: (after.at - before.at) / 1000, bracketed: before.at <= from && after.t >= to };
}

// 표본 사이 CPU(코어). 응답이 늦은 표본 바로 다음은 간격이 0.2초 안팎으로 짧아져 값이 부풀므로,
// 간격이 MIN_CPU_INTERVAL_S 이상이 될 때까지 다음 표본과 합쳐 계산한다. 반환은 구간 끝 표본과 그 구간의 CPU.
const MIN_CPU_INTERVAL_S = 0.9;
export function cpuIntervals(rows) {
  const out = [];
  let anchor = rows[0];
  for (let i = 1; i < rows.length; i++) {
    const r = rows[i], dt = (r.at - anchor.at) / 1000;
    if (dt < MIN_CPU_INTERVAL_S) continue;
    out.push({ row: r, cores: ((r.v.process_cpu_time_ns_total ?? 0) - (anchor.v.process_cpu_time_ns_total ?? 0)) / 1e9 / dt });
    anchor = r;
  }
  return out;
}

export function summarizeApi(rows, from, to) {
  const cpu = windowDelta(rows, from, to, "process_cpu_time_ns_total");
  const use = [windowDelta(rows, from, to, "hikaricp_connections_usage_seconds_sum"), windowDelta(rows, from, to, "hikaricp_connections_usage_seconds_count")];
  const acq = [windowDelta(rows, from, to, "hikaricp_connections_acquire_seconds_sum"), windowDelta(rows, from, to, "hikaricp_connections_acquire_seconds_count")];
  const red = [windowDelta(rows, from, to, "lettuce_command_completion_seconds_sum"), windowDelta(rows, from, to, "lettuce_command_completion_seconds_count")];
  const mean = ([s, c]) => (s && c && c.d > 0 ? (s.d / c.d) * 1000 : null);
  const inWin = rows.filter((r) => r.at >= from && r.at <= to);
  const max = (k) => (inWin.length ? Math.max(...inWin.map((r) => r.v[k] ?? 0)) : null);
  // 구간 평균 CPU는 유휴 구간에 희석된다. 표본 사이 CPU의 최댓값을 함께 둔다.
  const perSample = cpuIntervals(rows).filter((x) => x.row.at >= from && x.row.at <= to).map((x) => x.cores);
  return {
    bracketed: Boolean(cpu?.bracketed),
    cpu_cores_mean: cpu ? round(cpu.d / 1e9 / cpu.dt, 2) : null,
    cpu_cores_max: perSample.length ? round(Math.max(...perSample), 2) : null,
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

export function summarizeDb(pgLines, walLines, from, to, sa = null) {
  const rows = pgLines.map((l) => l.split("\t")).filter((c) => /^\d{4}-/.test(c[0]))
    .map((c) => ({ t: Date.parse(c[0]), active: +c[1], lock: +c[2], idleTx: +c[3], maxXact: c[5] === "" ? 0 : +c[5], waits: (c[6] || "").split(" ").filter(Boolean) }))
    .filter((r) => r.t >= from && r.t <= to);
  const waits = {};
  for (const r of rows) for (const w of r.waits) waits[w] = (waits[w] ?? 0) + 1;
  const wal = Object.fromEntries(walLines.map((l) => l.split("\t")).map((c) => [c[0], { at: Date.parse(String(c[1]).replace(" ", "T").replace(/\+00$/, "Z")), syncs: +c[2], syncMs: +c[3] }]));
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
    // WAL 지표는 예매 시작 3초 전·15초 뒤 두 번 찍는다. 분석 구간과 다를 수 있어 실제 측정 구간을 남긴다.
    wal_window_s: wal.before && wal.after && sa != null ? [round((wal.before.at - sa) / 1000), round((wal.after.at - sa) / 1000)] : null,
    wal_sync_ms_per_op: syncs > 0 ? round((wal.after.syncMs - wal.before.syncMs) / syncs, 2) : null,
  };
}

// 초 단위 시계열(예매 시작 기준). AI가 시간 흐름을 볼 수 있게 짧게 담는다.
function series(rows, sa, from, to) {
  const out = [];
  for (const { row: r, cores } of cpuIntervals(rows)) {
    if (r.at < from || r.at > to) continue;
    out.push({
      s: round((r.at - sa) / 1000, 0),
      scrape_rtt_ms: r.v.rtt ?? null,
      cpu_cores: round(cores, 2),
      tomcat_busy: r.v.tomcat_threads_busy_threads ?? null,
      hikari_active: r.v.hikaricp_connections_active ?? null,
      hikari_pending: r.v.hikaricp_connections_pending ?? null,
    });
  }
  return out;
}

// api 컨테이너 cgroup CPU 통계(cgroup-api.txt). 구간 경계는 windowDelta와 같은 방식(직전·직후 표본)이다.
// throttled_period_ratio: 구간 동안 CPU 할당량에 막힌 주기 비율(0~1). 파일이 없거나(이전 run) 통계를 못 읽은 줄뿐이면 null.
export function summarizeCgroup(lines, from, to) {
  const rows = lines.map((l) => l.trim().split(/\s+/)).filter((c) => /^\d{4}-/.test(c[0]))
    .map((c) => ({ t: Date.parse(c[0]), v: Object.fromEntries(c.slice(1).map((kv) => kv.split("=")).map(([k, x]) => [k, Number(x)])) }))
    .filter((r) => Number.isFinite(r.v.nr_periods)); // cpu.stat 경로를 못 찾으면 시각만 남는다. 0으로 읽히지 않게 뺀다
  if (rows.length < 2) return null;
  const before = rows.filter((r) => r.t <= from).at(-1) ?? rows[0];
  const after = rows.find((r) => r.t >= to) ?? rows.at(-1);
  const d = (k) => (after.v[k] ?? 0) - (before.v[k] ?? 0);
  const dt = (after.t - before.t) / 1000;
  const perSec = [];
  for (let i = 1; i < rows.length; i++) {
    const r = rows[i], p = rows[i - 1];
    if (r.t < from || r.t > to) continue;
    const periods = r.v.nr_periods - p.v.nr_periods;
    if (periods > 0) perSec.push((r.v.nr_throttled - p.v.nr_throttled) / periods);
  }
  return {
    throttled_period_ratio: d("nr_periods") > 0 ? round(d("nr_throttled") / d("nr_periods"), 2) : 0,
    throttled_period_ratio_max: perSec.length ? round(Math.max(...perSec), 2) : 0,
    cpu_cores_mean: dt > 0 ? round(d("usage_usec") / 1e6 / dt, 2) : null,
  };
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
  const rows = readLines(path.join(runDir, "sampler.jsonl")).map((l) => JSON.parse(l)).filter((r) => !r.error && r.api)
    .map((r) => ({ t: r.t, at: r.t + (r.api.rtt ?? 0), v: { ...r.api } }));
  const api = summarizeApi(rows, from, to);
  const db = summarizeDb(readLines(path.join(runDir, "pg.tsv")), readLines(path.join(runDir, "wal.tsv")), from, to, sa);
  const disk = summarizeDisk(readLines(path.join(runDir, "disk.txt")), from, to);
  api.cgroup = summarizeCgroup(readLines(path.join(runDir, "cgroup-api.txt")), from, to);

  const reasons = [];
  if (booking.users < meta.users) reasons.push(`예매 결과가 ${booking.users}/${meta.users}명분이다`);
  if (!api.bracketed) reasons.push("api 누적 지표가 분석 구간을 감싸지 못한다");
  if (db.samples < postS * 2) reasons.push(`PostgreSQL 표본이 ${db.samples}개뿐이다`);
  if (disk.samples < postS) reasons.push(`디스크 프로브 표본이 ${disk.samples}개뿐이다`);
  else if (disk.fdatasync_ms_p50 > DISK_PROBE_LIMIT_MS) reasons.push(`디스크 프로브 fdatasync 중앙값 ${disk.fdatasync_ms_p50}ms(저장소 지연, 기준 ${DISK_PROBE_LIMIT_MS}ms)`);
  else if (disk.fdatasync_ms_p95 > DISK_PROBE_P95_LIMIT_MS) reasons.push(`디스크 프로브 fdatasync p95 ${disk.fdatasync_ms_p95}ms(저장소 꼬리 지연, 기준 ${DISK_PROBE_P95_LIMIT_MS}ms)`);

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
