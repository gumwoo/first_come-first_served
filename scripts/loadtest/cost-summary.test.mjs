// cost-summary.mjs: 합성 query_range 입력으로 적분·그룹·단가 계산을 확인한다.
import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import url from "node:url";

const SCRIPT = path.join(path.dirname(url.fileURLToPath(import.meta.url)), "cost-summary.mjs");
const T0 = Date.parse("2026-10-14T00:00:00Z") / 1000;
const iso = (s) => new Date(s * 1000).toISOString();
const series = (metric, pts) => ({ metric, values: pts.map(([t, v]) => [t, String(v)]) });
const qr = (result) => JSON.stringify({ status: "success", data: { resultType: "matrix", result } });

function run(dir, extra = []) {
  const r = spawnSync(process.execPath, [SCRIPT, "--prom", dir, "--start", iso(T0), "--end", iso(T0 + 3600), "--step", "10", ...extra], { encoding: "utf8" });
  return { code: r.status, out: r.stdout ? JSON.parse(r.stdout) : null, err: r.stderr };
}

function fixture({ nodes, pods }) {
  const dir = mkdtempSync(path.join(tmpdir(), "cost-"));
  writeFileSync(path.join(dir, "nodes_ready.json"), qr([series({}, nodes)]));
  writeFileSync(path.join(dir, "cpu_requests_by_pod.json"), qr(pods));
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
  assert.equal(run(dir).code, 2);
});
