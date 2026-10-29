// 알림 구간 지표(Prometheus) → 분석용 요약 JSON. run 원자료로 만드는 normalize.mjs와 같은 형식이라 규칙·AI·대조를 그대로 쓴다.
//   node scripts/triage/prom-summary.mjs <알림 시각(ISO)> [--prom http://127.0.0.1:19090]
//
// run 요약과 다른 점
// - 구간: 알림 시각 앞 LOOKBACK_S초 안에서 예매 요청이 끝난 첫·마지막 초를 찾아 [첫 초 − 2초, 첫 초 + min(30, max(15, 마지막까지 초 + 2))]로 잡는다.
//   run 요약의 [예매 시작 − 2초, 예매 시작 + min(30, max(15, 가장 늦은 사용자 끝 + 2))]에 맞춘 것이다. 예매 시작 기준 초(s)는 첫 초를 0으로 둔다.
// - 예매 p95는 k6가 잰 클라이언트 값이 아니라 서버 히스토그램(http_server_requests) p95다. 사용자 수(booking.users)는 알 수 없어 null이다.
//   run 요약처럼 마지막 응답까지(최대 90초) 본다. 나머지 지표는 위 구간이다.
// - 표본은 Prometheus 긁기 간격(1초)이라 PostgreSQL 표본 수·대기 이벤트 표본 수가 run 수집(0.2초)보다 적다.
// - 디스크 프로브가 없다(disk: null). 측정 유효성은 지표 표본이 충분한지만 본다.
import path from "node:path";
import { fileURLToPath } from "node:url";
import { SCHEMA, quantile } from "./normalize.mjs";

// BOOK_MAX_S: 예매 p95를 볼 구간의 상한. k6 요청 시간 초과(60초)가 넘는 응답까지 담는다.
const PRE_S = 2, MIN_POST_S = 15, MAX_POST_S = 30, BOOK_MAX_S = 90, LOOKBACK_S = 90;
export const API = 'job="flowticket-api", role="api"';
export const BOOKING_URIS = { hold: "/events/{id}/seats/hold", order: "/orders", pay: "/orders/{id}/payments" };
const BOOKING_RE = "/events/.+/seats/hold|/orders|/orders/.+/payments";

const round = (x, d = 1) => (x == null || !Number.isFinite(x) ? null : Math.round(x * 10 ** d) / 10 ** d);
const scale = (x, k) => (x == null ? null : x * k);

export function promClient(base, fetchImpl = fetch) {
  const get = async (p, params) => {
    const res = await fetchImpl(`${base}/api/v1/${p}?${new URLSearchParams(params)}`);
    const body = await res.json();
    if (body.status !== "success") throw new Error(`Prometheus ${p} 실패: ${body.error ?? res.status}`);
    return body.data.result;
  };
  return {
    // 값 하나(결과가 없거나 NaN이면 null)
    async scalar(query, at) {
      const r = await get("query", { query, time: at / 1000 });
      const v = r.length ? Number(r[0].value[1]) : NaN;
      return Number.isFinite(v) ? v : null;
    },
    // 라벨별 값
    async vector(query, at) {
      return (await get("query", { query, time: at / 1000 })).map((x) => ({ metric: x.metric, v: Number(x.value[1]) }));
    },
    // 초 단위 시계열 [[ms, 값], ...]
    async range(query, from, to) {
      const r = await get("query_range", { query, start: from / 1000, end: to / 1000, step: "1" });
      return r.length ? r[0].values.map(([t, v]) => [t * 1000, Number(v)]).filter(([, v]) => Number.isFinite(v)) : [];
    },
  };
}

// 알림 시각 앞에서 예매 요청이 끝난 초를 찾는다. 없으면 null.
// 누적 요청 수를 1초 간격으로 읽어 늘어난 초를 센다(rate[2s]는 1초 긁기에서 표본 두 개가 창에 안 들어 자주 비었다).
// 구간 끝은 run 요약(normalize.mjs)과 같은 식이다: 시작 + min(30, max(15, 마지막 요청까지 초 + 2)).
export async function bookingWindow(prom, alertAt, lookbackS = LOOKBACK_S) {
  const [from, to] = [alertAt - lookbackS * 1000, alertAt + BOOK_MAX_S * 1000];
  const pts = await prom.range(`sum(http_server_requests_seconds_count{${API}, uri=~"${BOOKING_RE}"})`, from, to);
  // api가 바빠 긁기가 시간 초과로 실패하면 그 사이 지표가 끊긴다(stale). 끊긴 뒤 늘었으면 요청은 끊기기 직전 점 다음부터 끝났다고 본다.
  const busy = [];
  for (let i = 1; i < pts.length; i++) {
    const [t, v] = pts[i], [pt, pv] = pts[i - 1];
    if (v > pv) busy.push({ start: t - pt > 1000 ? pt + 1000 : t, end: t });
  }
  // 요청이 끝나지 않고 멈추면 위 수가 늘지 않는다. 처리 중인 POST(예매 단계)가 있는 초도 묶음으로 센다.
  for (const [t, v] of await prom.range(`sum(http_server_requests_active_seconds_active_count{${API}, method="POST"})`, from, to)) {
    if (v > 0) busy.push({ start: t, end: t });
  }
  busy.sort((a, b) => a.start - b.start);
  // 앞 run의 묶음이 조회 범위에 걸릴 수 있어, 15초 넘게 끊긴 곳에서 묶음을 나누고 알림 전에 시작한 마지막 묶음을 고른다.
  const groups = [];
  for (const b of busy) {
    const g = groups.at(-1);
    if (g && b.start - g.end <= MIN_POST_S * 1000) g.end = Math.max(g.end, b.end);
    else groups.push({ start: b.start, end: b.end });
  }
  const g = groups.filter((x) => x.start <= alertAt).at(-1);
  if (!g) return null;
  const sa = g.start, last = g.end;
  const postS = Math.min(MAX_POST_S, Math.max(MIN_POST_S, Math.ceil((last - sa) / 1000) + 2));
  // 예매 p95·5xx는 run 요약처럼 마지막 응답까지 본다(지표 구간 상한 30초보다 늦게 끝난 요청이 있다).
  const bookTo = Math.max(sa + postS * 1000, Math.min(last + PRE_S * 1000, sa + BOOK_MAX_S * 1000));
  return { sa, last, from: sa - PRE_S * 1000, to: sa + postS * 1000, bookTo, capped: postS === MAX_POST_S };
}

export async function promSummary(prom, alertAt) {
  const w = await bookingWindow(prom, alertAt);
  if (!w) {
    // 예매 요청이 정말 없었는지, api 지표가 끊겨 못 본 것인지 가른다(DB가 CPU를 못 받으면 지표 요청도 막혀 끊긴다, IMP-054).
    const ups = await prom.range(`up{${API}}`, alertAt - LOOKBACK_S * 1000, alertAt);
    const ok = ups.filter(([, v]) => v > 0).length;
    const why = ok < ups.length * 0.8 ? `api 지표 긁기가 끊겨 예매 구간을 찾지 못했다(알림 앞 ${LOOKBACK_S}초 긁기 성공 ${ok}/${ups.length}회)` : `알림 앞 ${LOOKBACK_S}초 안에 예매 요청이 없다`;
    return { schema: SCHEMA, window: null, validity: { valid: false, reasons: [why] }, booking: null, api: null, db: null, disk: null, series: [] };
  }
  const { sa, from, to, bookTo } = w;
  const W = `${Math.round((to - from) / 1000)}s`, WB = `${Math.round((bookTo - from) / 1000)}s`;
  const q = (expr) => prom.scalar(expr, to);
  const inc = (m, sel = API) => `sum(increase(${m}{${sel}}[${W}]))`;
  const ratioMs = async (m, sel) => scale(await q(`${inc(m + "_sum", sel)} / ${inc(m + "_count", sel)}`), 1000);

  const p95 = {};
  for (const [k, uri] of Object.entries(BOOKING_URIS)) {
    const v = await prom.scalar(`histogram_quantile(0.95, sum by(le) (increase(http_server_requests_seconds_bucket{${API}, uri="${uri}"}[${WB}])))`, bookTo);
    p95[k] = v == null ? null : Math.round(v * 1000);
  }
  const http5xx = await prom.scalar(`sum(increase(http_server_requests_seconds_count{job="flowticket-api", outcome="SERVER_ERROR"}[${WB}]))`, bookTo);
  // 긁기에 성공한 표본(up=1). 구간 평균은 누적값 차분이라, run 요약처럼 구간 앞뒤에 표본이 있는지(감싸는지)를 본다.
  const ok = (await prom.range(`up{${API}}`, from, to)).filter(([, v]) => v > 0).map(([t]) => t);
  const apiSamples = ok.length;
  const bracketed = ok.some((t) => t <= sa + 1000) && ok.some((t) => t >= to - 5000);
  // 할당량이 없으면 주기 수가 늘지 않는다. run 요약(summarizeCgroup)처럼 그때는 0으로 둔다.
  const periods = await q(inc("container_cpu_cfs_periods_total", 'name="api"'));
  const throttled = await q(inc("container_cpu_cfs_throttled_periods_total", 'name="api"'));
  const thr = periods == null || throttled == null ? null : periods > 0 ? throttled / periods : 0;
  const waits = await prom.vector(`sum by(event) (sum_over_time(flowticket_pg_wait_sessions[${W}]))`, to);
  const walSyncs = await q(inc("flowticket_pg_wal_syncs", ""));
  const walMs = await q(inc("flowticket_pg_wal_sync_time_ms", ""));
  const dbSamples = await q(`count_over_time(flowticket_pg_active[${W}])`);

  const api = {
    bracketed,
    cpu_cores_mean: round(scale(await q(`sum(rate(process_cpu_time_ns_total{${API}}[${W}]))`), 1e-9), 2),
    cpu_cores_max: round(await q(`max_over_time((sum(rate(process_cpu_time_ns_total{${API}}[2s])) / 1e9)[${W}:1s])`), 2),
    tomcat_busy_max: await q(`max_over_time(sum(tomcat_threads_busy_threads{${API}})[${W}:1s])`),
    hikari: {
      active_max: await q(`max_over_time(sum(hikaricp_connections_active{${API}})[${W}:1s])`),
      pending_max: await q(`max_over_time(sum(hikaricp_connections_pending{${API}})[${W}:1s])`),
      checkouts: round(await q(inc("hikaricp_connections_usage_seconds_count")), 0),
      usage_ms_mean: round(await ratioMs("hikaricp_connections_usage_seconds", API)),
      acquire_ms_mean: round(await ratioMs("hikaricp_connections_acquire_seconds", API)),
    },
    redis: {
      cmds: round(await q(inc("lettuce_command_completion_seconds_count")), 0),
      completion_ms_mean: round(await ratioMs("lettuce_command_completion_seconds", API), 2),
    },
    scrape_rtt_ms_max: round(scale(await q(`max_over_time(scrape_duration_seconds{${API}}[${W}])`), 1000), 0),
    samples_in_window: apiSamples,
    cgroup: thr == null ? null : {
      throttled_period_ratio: round(thr, 2),
      throttled_period_ratio_max: periods > 0 ? round(await q(`max_over_time((sum(rate(container_cpu_cfs_throttled_periods_total{name="api"}[2s])) / sum(rate(container_cpu_cfs_periods_total{name="api"}[2s])))[${W}:1s])`), 2) : 0,
      cpu_cores_mean: round(await q(`sum(rate(container_cpu_usage_seconds_total{name="api"}[${W}]))`), 2),
    },
  };
  const db = {
    samples: dbSamples,
    active_mean: round(await q(`avg_over_time(flowticket_pg_active[${W}])`), 2),
    active_max: await q(`max_over_time(flowticket_pg_active[${W}])`),
    idle_in_tx_mean: round(await q(`avg_over_time(flowticket_pg_idle_in_tx[${W}])`), 2),
    lock_wait_samples: await q(`count_over_time((flowticket_pg_lock_wait > 0)[${W}:1s])`) ?? 0,
    max_xact_s: round(await q(`max_over_time(flowticket_pg_max_xact_seconds[${W}])`), 3),
    active_wait_events: Object.fromEntries(waits.filter((x) => x.v > 0).map((x) => [x.metric.event, Math.round(x.v)])),
    wal_syncs: round(walSyncs, 0),
    wal_window_s: [round((from - sa) / 1000), round((to - sa) / 1000)],
    wal_sync_ms_per_op: walSyncs > 0 && walMs != null ? round(walMs / walSyncs, 2) : null,
  };

  // 초 단위 시계열(run 요약의 series와 같은 열)
  const cols = {
    scrape_rtt_ms: `scrape_duration_seconds{${API}} * 1000`,
    cpu_cores: `sum(rate(process_cpu_time_ns_total{${API}}[2s])) / 1e9`,
    tomcat_busy: `sum(tomcat_threads_busy_threads{${API}})`,
    hikari_active: `sum(hikaricp_connections_active{${API}})`,
    hikari_pending: `sum(hikaricp_connections_pending{${API}})`,
  };
  const byT = new Map();
  for (const [k, expr] of Object.entries(cols)) {
    for (const [t, v] of await prom.range(expr, from, to)) {
      if (!byT.has(t)) byT.set(t, { s: round((t - sa) / 1000, 0) });
      byT.get(t)[k] = k === "cpu_cores" ? round(v, 2) : Math.round(v);
    }
  }
  const series = [...byT.keys()].sort((a, b) => a - b).map((t) => ({ scrape_rtt_ms: null, cpu_cores: null, tomcat_busy: null, hikari_active: null, hikari_pending: null, ...byT.get(t) }));

  const lenS = (to - from) / 1000, reasons = [];
  if (!bracketed) reasons.push(`api 지표가 분석 구간 앞뒤를 감싸지 못한다(긁기 성공 ${apiSamples}/${Math.round(lenS) + 1}회)`);
  if ((dbSamples ?? 0) < lenS / 2) reasons.push(`PostgreSQL 표본이 ${dbSamples ?? 0}개뿐이다`);
  if (Object.values(p95).every((x) => x == null)) reasons.push("예매 단계 응답 시간이 없다");

  return {
    schema: SCHEMA,
    window: { from_s: -PRE_S, to_s: round((to - sa) / 1000, 0) },
    validity: { valid: reasons.length === 0, reasons },
    booking: { users: null, http5xx: Math.round(http5xx ?? 0), p95_ms: p95, p95_median_ms: quantile(Object.values(p95).filter((x) => x != null), 50) },
    api,
    db,
    disk: null,
    series,
  };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  const at = Date.parse(process.argv[2] ?? "");
  if (!Number.isFinite(at)) { console.error("사용: node scripts/triage/prom-summary.mjs <알림 시각(ISO)> [--prom URL]"); process.exit(2); }
  const i = process.argv.indexOf("--prom");
  const prom = promClient(i > 0 ? process.argv[i + 1] : "http://127.0.0.1:19090");
  console.log(JSON.stringify(await promSummary(prom, at), null, 2));
}
