// cost-summary.mjs: 합성 query_range 입력으로 적분·그룹·단가 계산을 확인한다.
import { test, after } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import url from "node:url";

const SCRIPT = path.join(path.dirname(url.fileURLToPath(import.meta.url)), "cost-summary.mjs");
const T0 = Date.parse("2026-10-14T00:00:00Z") / 1000;
const iso = (s) => new Date(s * 1000).toISOString();
const series = (metric, pts) => ({ metric, values: pts.map(([t, v]) => [t, String(v)]) });
const qr = (result) => JSON.stringify({ status: "success", data: { resultType: "matrix", result } });

function run(dir, extra = [], { start = iso(T0), end = iso(T0 + 3600) } = {}) {
  const r = spawnSync(process.execPath, [SCRIPT, "--prom", dir, "--start", start, "--end", end, ...extra], { encoding: "utf8" });
  return { code: r.status, out: r.status === 0 && r.stdout ? JSON.parse(r.stdout) : null, err: r.stderr };
}

const dirs = [];
after(() => dirs.forEach((d) => rmSync(d, { recursive: true, force: true })));

// export-prom.mjs가 남기는 것과 같은 모양: 두 지표 + _meta.json(내보낸 구간·step)
function fixture({ nodes, pods, meta = { startSec: T0 - 600, endSec: T0 + 7200, step: 10 } }) {
  const dir = mkdtempSync(path.join(tmpdir(), "cost-"));
  dirs.push(dir);
  writeFileSync(path.join(dir, "nodes_ready.json"), qr([series({}, nodes)]));
  writeFileSync(path.join(dir, "cpu_requests_by_pod.json"), qr(pods));
  writeFileSync(path.join(dir, "_meta.json"), JSON.stringify({ ...meta, start: iso(meta.startSec), end: iso(meta.endSec) }));
  return dir;
}

// 1시간 동안 10초 간격 샘플 360개
const every10 = (v) => Array.from({ length: 360 }, (_, i) => [T0 + i * 10, typeof v === "function" ? v(i) : v]);

test("노드 4대가 1시간 = 4 노드-시간, 단가를 곱한다", () => {
  const dir = fixture({ nodes: every10(4), pods: [] });
  const { code, out } = run(dir, ["--node-price", "0.118"]);
  assert.equal(code, 0);
  assert.equal(out.nodes.nodeHours, 4);
  assert.equal(out.nodes.avg, 4);
  assert.equal(out.cost.nodeCostUsd, 0.47);
});

test("노드가 30분 4대 → 30분 6대면 5 노드-시간", () => {
  const dir = fixture({ nodes: every10((i) => (i < 180 ? 4 : 6)), pods: [] });
  const { out } = run(dir);
  assert.equal(out.nodes.nodeHours, 5);
  assert.equal(out.nodes.min, 4);
  assert.equal(out.nodes.max, 6);
});

test("요청 CPU는 파드 이름으로 api·api-queue·web을 나누고, 사라진 파드의 구간은 0이다", () => {
  const dir = fixture({
    nodes: every10(5),
    pods: [
      series({ pod: "flowticket-api-6d7f9-abcde" }, every10(1.1)), // 1시간 내내
      series({ pod: "flowticket-api-queue-5c4b-xyz12" }, every10(1.1).slice(0, 180)), // 30분만
      series({ pod: "flowticket-web-7f8d-qwert" }, every10(0.5)),
    ],
  });
  const { out } = run(dir);
  assert.equal(out.cpuRequestCoreHours.api, 1.1);
  assert.equal(out.cpuRequestCoreHours["api-queue"], 0.55);
  assert.equal(out.cpuRequestCoreHours.web, 0.5);
  assert.equal(out.cpuRequestCoreHours.total, 2.15);
});

test("구간 밖 샘플은 세지 않는다", () => {
  const dir = fixture({ nodes: [[T0 - 600, 9], ...every10(3), [T0 + 7200, 9]], pods: [] });
  const { out } = run(dir);
  assert.equal(out.nodes.nodeHours, 3);
  assert.equal(out.nodes.max, 3);
});

test("입력이 없으면 2로 끝난다", () => {
  const dir = mkdtempSync(path.join(tmpdir(), "cost-"));
  dirs.push(dir);
  assert.equal(run(dir).code, 2);
});

// 운영자 호출 형태: 넓게 내보낸 격자(T0 - 123초부터 10초 간격)에 격자와 어긋난 구간 [T0, T0 + 1500]을 잰다.
const grid = (from, to, v) => Array.from({ length: Math.floor((to - from) / 10) + 1 }, (_, i) => [from + i * 10, v]);
const wide = { startSec: T0 - 123, endSec: T0 + 1560, step: 10 };

test("격자와 어긋난 구간도 겹친 길이만큼만 센다(노드 5대 × 1500초)", () => {
  const dir = fixture({ nodes: grid(T0 - 123, T0 + 1560, 5), pods: [series({ pod: "flowticket-api-1-a" }, grid(T0 - 123, T0 + 1560, 1.1))], meta: wide });
  const { code, out } = run(dir, [], { end: iso(T0 + 1500) });
  assert.equal(code, 0);
  assert.equal(out.nodes.nodeHours, 2.0833);
  assert.equal(out.cpuRequestCoreHours.api, 0.4583);
  assert.equal(out.window.step, 10);
});

test("판정 불가는 2로 끝난다 — step 불일치·구간 밖·수집 공백·시간대 없음", () => {
  const nodes = grid(T0 - 123, T0 + 1560, 5);
  const ok = fixture({ nodes, pods: [], meta: wide });
  assert.equal(run(ok, ["--step", "15"], { end: iso(T0 + 1500) }).code, 2, "--step이 내보낸 step과 다름");
  assert.equal(run(ok, ["--step", "10"], { end: iso(T0 + 1500) }).code, 0, "같은 step은 통과");
  assert.equal(run(ok, [], { end: iso(T0 + 1600) }).code, 2, "끝이 내보낸 구간 밖");
  assert.equal(run(ok, [], { start: iso(T0 - 200), end: iso(T0 + 1500) }).code, 2, "시작이 내보낸 구간 밖");
  assert.equal(run(ok, [], { start: "2026-10-14T00:00:00", end: "2026-10-14T00:25:00" }).code, 2, "시간대 없음");
  const gap = fixture({ nodes: nodes.filter(([t]) => t < T0 + 600 || t >= T0 + 660), pods: [], meta: wide });
  const r = run(gap, [], { end: iso(T0 + 1500) });
  assert.equal(r.code, 2, "60초 공백");
  assert.match(r.err, /수집 공백/);
  const late = fixture({ nodes: nodes.filter(([t]) => t >= T0 + 30), pods: [], meta: wide });
  assert.equal(run(late, [], { end: iso(T0 + 1500) }).code, 2, "시작을 덮지 않음");
});
