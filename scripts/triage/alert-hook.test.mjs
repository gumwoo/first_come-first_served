// 알림 → 분석 테스트: Prometheus 응답을 흉내 내 요약을 만들고, 규칙·AI·메시지까지 이어지는지 본다.
// 실제 Prometheus·API·웹훅은 부르지 않는다. 의존성 없는 node 내장 테스트.
import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { bookingWindow, promClient, promSummary } from "./prom-summary.mjs";
import { firingAlerts, handleAlert } from "./alert-hook.mjs";

const T0 = Date.parse("2026-10-29T10:00:00Z");

// 질의 문자열로 값을 고르는 가짜 Prometheus. 예매 요청은 T0+10초 ~ T0+25초에 끝나고(누적 수가 초마다 5씩 는다),
// 앞 run의 묶음이 T0−60초 ~ T0−50초에 있다.
const bursts = [[T0 - 60_000, T0 - 50_000], [T0 + 10_000, T0 + 25_000]];
// gap: 이 구간에는 api 긁기가 실패해 점이 없다. stall: 이 구간에는 끝나지 않은 POST 요청이 있다. upGap: 이 구간의 up은 0이다.
function fakeProm(values = {}, gap = null, stall = null, upGap = null) {
  const pick = (query) => Object.entries(values).find(([k]) => query.includes(k))?.[1];
  const count = (t) => bursts.reduce((a, [s, e]) => a + 5 * Math.max(0, Math.min(t, e) - s + 1000) / 1000, 0);
  return {
    async scalar(query) { const v = pick(query); return v === undefined ? 1 : v; },
    async vector(query) { return query.includes("flowticket_pg_wait_sessions") ? [{ metric: { event: "CPU" }, v: 12 }, { metric: { event: "Lock:transactionid" }, v: 0 }] : []; },
    async range(query, from, to) {
      const out = [];
      for (let t = Math.ceil(from / 1000) * 1000; t <= to; t += 1000) {
        if (gap && t >= gap[0] && t <= gap[1]) continue;
        const active = stall && t >= stall[0] && t <= stall[1] ? 3 : 0;
        const up = upGap && t >= upGap[0] && t <= upGap[1] ? 0 : 1;
        out.push([t, query.includes("uri=~") ? count(t) : query.includes("active_count") ? active : query.startsWith("up{") ? up : 2]);
      }
      return out;
    },
  };
}

const base = {
  "count_over_time(flowticket_pg_active": 32,
  'uri="/events/{id}/seats/hold"': 2.4, 'uri="/orders"}': 0.3, 'uri="/orders/{id}/payments"': 0.5,
  'outcome="SERVER_ERROR"': 0, "lettuce_command_completion_seconds_sum": 0.0004, "hikaricp_connections_usage_seconds_sum": 0.0766,
  "hikaricp_connections_acquire_seconds_sum": 1.0178, "container_cpu_cfs_throttled_periods_total{name=\"api\"}[": 0.01,
  "avg_over_time(flowticket_pg_active": 0.05, "flowticket_pg_wal_syncs": 100, "flowticket_pg_wal_sync_time_ms": 150,
};

test("Prometheus 지표로 run 요약과 같은 형식을 만든다(구간은 예매 요청이 들어온 초 기준)", async () => {
  const s = await promSummary(fakeProm(base), T0 + 20_000);
  assert.equal(s.schema, "triage.summary/v1");
  assert.deepEqual(s.window, { from_s: -2, to_s: 17 });           // 첫 요청 초를 0으로, 마지막 요청 + 2초
  assert.deepEqual(s.validity, { valid: true, reasons: [] });
  assert.deepEqual(s.booking, { users: null, http5xx: 0, p95_ms: { hold: 2400, order: 300, pay: 500 }, p95_median_ms: 500 });
  assert.equal(s.api.hikari.acquire_ms_mean, 1017.8);
  assert.equal(s.api.redis.completion_ms_mean, 0.4);
  assert.equal(s.api.cgroup.throttled_period_ratio, 0.01);
  assert.deepEqual(s.db.active_wait_events, { CPU: 12 });            // 0인 대기 이벤트는 뺀다
  assert.equal(s.db.wal_sync_ms_per_op, 1.5);
  assert.deepEqual(s.db.wal_window_s, [-2, 17]);
  assert.equal(s.disk, null);
  assert.equal(s.series.length, 20);
  assert.deepEqual(s.series[0], { s: -2, scrape_rtt_ms: 2, cpu_cores: 2, tomcat_busy: 2, hikari_active: 2, hikari_pending: 2 });
});

test("긁기가 끊긴 뒤 요청 수가 늘었으면 끊기기 직전 점 다음을 예매 시작으로 본다", async () => {
  const w = await bookingWindow(fakeProm(base, [T0 + 9_000, T0 + 14_000]), T0 + 20_000);   // 9~14초 점이 없다
  assert.equal(w.sa, T0 + 9_000);                                    // 8초 점 다음
  assert.equal(w.from, T0 + 7_000);                                  // 늘기 전 점(8초)이 구간 안에 든다
  assert.equal(w.last, T0 + 25_000);
});

test("요청이 끝나지 않고 멈춰 있으면 처리 중인 요청이 있는 초로 구간을 잡는다", async () => {
  const w = await bookingWindow(fakeProm(base, null, [T0 - 5_000, T0 + 25_000]), T0 + 3_000);  // T0−5초부터 멈춤, 끝난 요청은 T0+10초부터
  assert.equal(w.sa, T0 - 5_000);
  assert.equal(w.to, T0 + 25_000);                                    // 지표 구간 상한 30초
  assert.equal(w.capped, true);
  assert.equal(w.bookTo, T0 + 27_000);                                // 예매 p95는 마지막 응답 + 2초까지
});

test("값이 없으면 0이 아니라 null로 두고, 표본이 모자라면 무효로 둔다", async () => {
  const s = await promSummary(fakeProm({ ...base, "container_cpu_cfs_throttled_periods_total{name=\"api\"}[": null, "lettuce_command_completion_seconds_sum": null }, null, null, [T0 + 12_000, T0 + 40_000]), T0 + 20_000); // 12초부터 긁기 실패
  assert.equal(s.api.cgroup, null);
  const noQuota = await promSummary(fakeProm({ ...base, "increase(container_cpu_cfs_periods_total": 0 }), T0 + 20_000);
  assert.deepEqual([noQuota.api.cgroup.throttled_period_ratio, noQuota.api.cgroup.throttled_period_ratio_max], [0, 0]); // 할당량 없음
  assert.equal(s.api.redis.completion_ms_mean, null);
  assert.equal(s.validity.valid, false);
  assert.match(s.validity.reasons[0], /api 지표가 분석 구간 앞뒤를 감싸지 못한다\(긁기 성공 4\/20회\)/);
  const none = await promSummary(fakeProm(base), T0 - 200_000);     // 알림 앞에 예매 요청이 없다
  assert.deepEqual(none.validity.reasons, ["알림 앞 90초 안에 예매 요청이 없다"]);
  const cut = await promSummary(fakeProm(base, null, null, [T0 - 300_000, T0]), T0 - 200_000);   // 긁기가 계속 실패해 못 봤다
  assert.match(cut.validity.reasons[0], /^api 지표 긁기가 끊겨 예매 구간을 찾지 못했다\(알림 앞 90초 긁기 성공 0\/91회\)$/);
});

test("promClient는 Prometheus HTTP API 응답을 값·시계열로 바꾼다", async () => {
  const urls = [];
  const fetchImpl = async (u) => {
    urls.push(u);
    const data = u.includes("query_range")
      ? { result: [{ metric: {}, values: [[1, "2"], [2, "NaN"]] }] }
      : { result: [{ metric: { event: "CPU" }, value: [1, "3.5"] }] };
    return { status: 200, json: async () => ({ status: "success", data }) };
  };
  const p = promClient("http://prom", fetchImpl);
  assert.equal(await p.scalar("up", 1000), 3.5);
  assert.deepEqual(await p.range("up", 1000, 2000), [[1000, 2]]);    // NaN은 뺀다
  assert.match(urls[0], /^http:\/\/prom\/api\/v1\/query\?query=up&time=1$/);
  const bad = promClient("http://prom", async () => ({ status: 400, json: async () => ({ status: "error", error: "bad" }) }));
  await assert.rejects(bad.scalar("x", 0), /bad/);
});

test("새로 울린 알림만 한 번씩 분석한다", () => {
  const seen = new Set();
  const body = { alerts: [{ status: "firing", fingerprint: "a", startsAt: "t1" }, { status: "resolved", fingerprint: "b", startsAt: "t1" }] };
  assert.equal(firingAlerts(body, seen).length, 1);
  assert.equal(firingAlerts(body, seen).length, 0);                  // 재전송
  assert.equal(firingAlerts({ alerts: [{ status: "firing", fingerprint: "a", startsAt: "t2" }] }, seen).length, 1);
});

test("알림이면 규칙이 이상 없음으로 봐도 AI를 부르고, 결과 파일과 메시지를 남긴다", async () => {
  const out = fs.mkdtempSync(path.join(os.tmpdir(), "alert-hook-"));
  let calls = 0;
  const client = { messages: { create: async () => { calls++; return { model: "m", usage: { input_tokens: 1, output_tokens: 1 }, content: [{ type: "tool_use", name: "report_diagnosis", input: {
    verdict: "anomaly", conclusion: "inconclusive", summary_line: "s",
    hypotheses: [{ cause: "db", name: "좌석 행 잠금", confidence: "low", rationale: "-", confirm: "pg_locks를 본다." }],
    evidence: [{ claim: "c", field: "api.hikari.acquire_ms_mean", value: 1017.8 }], counter_evidence: [], unchecked: [], next_checks: [] } }] }; } } };
  const r = await handleAlert({ labels: { alertname: "FlowticketBookingSlow" }, startsAt: new Date(T0 + 20_000).toISOString() },
    { prom: fakeProm(base), client, out, now: () => T0 + 60_000, wait: async () => {} });
  assert.equal(calls, 1);                                            // p95 중앙값 500ms라 규칙은 이상 없음이지만 AI를 불렀다
  assert.equal(JSON.parse(fs.readFileSync(path.join(r.dir, "rule.json"), "utf8")).verdict, "normal");
  assert.deepEqual(fs.readdirSync(r.dir).sort(), ["ai.json", "alert.json", "report.md", "rule.json", "slack.txt", "summary.json"]);
  assert.equal(r.sent, false);
  assert.match(r.text, /AI는 .+좌석 행 잠금.+ 쪽을 가장 의심하고 있어요/);
  // 같은 묶음에 다른 알림(멈춤)이 또 울리면 다시 분석하지 않는다
  const windows = new Set();
  const opts = { prom: fakeProm(base), client, out, now: () => T0 + 60_000, wait: async () => {}, windows };
  await handleAlert({ labels: { alertname: "FlowticketBookingSlow" }, startsAt: new Date(T0 + 20_000).toISOString() }, opts);
  const again = await handleAlert({ labels: { alertname: "FlowticketBookingStalled" }, startsAt: new Date(T0 + 22_000).toISOString() }, opts);
  assert.equal(again.reason, "같은 예매 구간을 이미 분석했다");
  assert.equal(calls, 2);
  fs.rmSync(out, { recursive: true, force: true });
});
