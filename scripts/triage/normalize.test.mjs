// 정규화(누적 지표 차분·유효성)와 규칙 분류 갈래 테스트. 의존성 없는 node 내장 테스트.
import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { normalize, userDurationMs } from "./normalize.mjs";
import { classify, THRESHOLDS } from "./rules.mjs";

const SA = Date.parse("2026-10-21T12:00:00Z");
const iso = (ms) => new Date(ms).toISOString();

// 예매 시작 3초 전부터 20초 뒤까지 1초 표본. 구간 동안 CPU 1코어, 커넥션 100회·점유 합 1초(평균 10ms).
function makeRun({ walMs = 1, diskMs = 2, users = 3, pgActive = 0 } = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "triage-"));
  fs.writeFileSync(path.join(dir, "meta.json"), JSON.stringify({ startAt: iso(SA), eventId: 1, users: 3 }));
  const rows = [];
  for (let s = -3; s <= 20; s++) {
    const k = Math.max(0, s + 2); // 구간 시작(−2초)부터 쌓인다
    rows.push(JSON.stringify({ t: SA + s * 1000, api: {
      rtt: 0, process_cpu_time_ns_total: k * 1e9, tomcat_threads_busy_threads: s >= 0 && s < 3 ? 40 : 1,
      hikaricp_connections_active: 0, hikaricp_connections_pending: s === 1 ? 30 : 0,
      hikaricp_connections_usage_seconds_sum: k * 0.05, hikaricp_connections_usage_seconds_count: k * 5,
      hikaricp_connections_acquire_seconds_sum: 0, hikaricp_connections_acquire_seconds_count: k * 5,
      lettuce_command_completion_seconds_sum: k * 0.01, lettuce_command_completion_seconds_count: k * 20,
    } }));
  }
  fs.writeFileSync(path.join(dir, "sampler.jsonl"), rows.join("\n") + "\n");
  const pg = [];
  for (let ms = -2000; ms <= 15000; ms += 200) pg.push([iso(SA + ms), pgActive, 0, 0, 5, "0.01", pgActive ? "CPU" : ""].join("\t"));
  fs.writeFileSync(path.join(dir, "pg.tsv"), pg.join("\n") + "\n");
  fs.writeFileSync(path.join(dir, "wal.tsv"), `before\tx\t100\t0\nafter\tx\t300\t${200 * walMs}\n`);
  const disk = [];
  for (let ms = -2000; ms <= 15000; ms += 200) disk.push(`${iso(SA + ms)} ${diskMs} 201`);
  fs.writeFileSync(path.join(dir, "disk.txt"), disk.join("\n") + "\n");
  const user = (ms) => `E2E ${JSON.stringify({ steps: [{ step: "queue_entry", ms: 5, status: 200 }, { step: "hold", ms, status: 200 }, { step: "order", ms, status: 200 }, { step: "pay", ms, status: 200 }] })}`;
  fs.writeFileSync(path.join(dir, "book.log"), Array.from({ length: users }, (_, i) => user(100 * (i + 1))).join("\n") + "\nrunning...\n");
  return dir;
}

test("누적 지표 차분으로 구간 평균을 구한다", () => {
  const s = normalize(makeRun());
  assert.equal(s.validity.valid, true);
  assert.equal(s.window.from_s, -2);
  assert.equal(s.api.cpu_cores_mean, 1);
  assert.equal(s.api.hikari.usage_ms_mean, 10);
  assert.equal(s.api.redis.completion_ms_mean, 0.5);
  assert.equal(s.api.hikari.pending_max, 30);
  assert.equal(s.db.wal_sync_ms_per_op, 1);
  assert.deepEqual(s.booking.p95_ms, { hold: 300, order: 300, pay: 300 });
});

test("디스크 프로브가 느리거나 예매 결과가 빠지면 무효다", () => {
  // DB 쪽 장애로 WAL 동기화만 느린 run은 유효하다(측정 환경 판정은 분리된 프로브로 한다).
  assert.equal(normalize(makeRun({ walMs: 50 })).validity.valid, true);
  const slow = normalize(makeRun({ diskMs: 40 }));
  assert.equal(slow.validity.valid, false);
  assert.match(slow.validity.reasons.join(), /fdatasync 중앙값 40ms/);
  const missing = normalize(makeRun({ users: 2 }));
  assert.match(missing.validity.reasons.join(), /2\/3명분/);
});

test("사용자 소요 시간: 동시 요청 묶음은 최댓값, 단계는 차례로 더하고 늦은 출발을 더한다", () => {
  const rec = { t0LagMs: 40, steps: [{ step: "queue_status", ms: 9 }, { step: "hold", ms: 100 }, { step: "hold", ms: 300 },
    { step: "order", ms: 50 }, { step: "pay_fail", ms: 20 }, { step: "pay", ms: 70 }, { step: "pay", ms: 60 }] };
  assert.equal(userDurationMs(rec), 40 + 300 + 50 + 20 + 70);
});

test("구간 끝 뒤 표본이 없으면 무효다", () => {
  const dir = makeRun({ users: 3 });
  const rows = fs.readFileSync(path.join(dir, "sampler.jsonl"), "utf8").trim().split("\n").slice(0, 12); // 예매 시작 +8초까지만
  fs.writeFileSync(path.join(dir, "sampler.jsonl"), rows.join("\n") + "\n");
  assert.match(normalize(dir).validity.reasons.join(), /감싸지 못한다/);
});

test("규칙 분류: 무효·정상·Redis·DB·앱 CPU·판단 불가", () => {
  const base = normalize(makeRun());
  const slow = { ...base, booking: { ...base.booking, p95_median_ms: THRESHOLDS.anomaly_p95_median_ms + 1 } };
  const with_ = (patch) => structuredClone({ ...slow, ...patch });
  assert.equal(classify({ ...base, validity: { valid: false, reasons: ["x"] } }).verdict, "undetermined");
  assert.equal(classify(base).verdict, "normal");
  assert.equal(classify(with_({ api: { ...slow.api, redis: { cmds: 1, completion_ms_mean: THRESHOLDS.redis_completion_ms + 1 } } })).cause, "redis");
  assert.equal(classify(with_({ db: { ...slow.db, active_mean: THRESHOLDS.db_active_mean + 1 } })).cause, "db");
  assert.equal(classify(with_({ api: { ...slow.api, hikari: { ...slow.api.hikari, usage_ms_mean: THRESHOLDS.app_usage_ms + 1 } } })).cause, "app_cpu");
  // 지표 응답 지연은 점유 값이 없을 때만 쓴다.
  assert.equal(classify(with_({ api: { ...slow.api, scrape_rtt_ms_max: THRESHOLDS.app_scrape_rtt_ms + 1 } })).verdict, "undetermined");
  assert.equal(classify(with_({ api: { ...slow.api, hikari: { ...slow.api.hikari, usage_ms_mean: null }, scrape_rtt_ms_max: THRESHOLDS.app_scrape_rtt_ms + 1 } })).cause, "app_cpu");
  assert.equal(classify(slow).verdict, "undetermined");
});
