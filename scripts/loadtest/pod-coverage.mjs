#!/usr/bin/env node
// 대기열 순서 대조의 전제 확인(loadtest-100k-plan §3.3). run 동안 있었던 api 파드의 로그를 지금 전부 읽을 수 있는가.
//
// 사후 검사는 `kubectl logs`로 지금 살아 있는 파드의 현재 컨테이너, 그것도 현재 로그 파일만 읽는다. 그래서 세 경우에
// run 구간의 승격 줄이 빠지고, 대기열 순서 대조가 일부 로그만 보고도 "위반 없음"을 낸다.
//   1) run 도중이나 그 뒤에 HPA 축소로 지워진 파드
//   2) 재시작한 컨테이너의 이전 로그
//   3) kubelet이 로그를 회전해 지운 앞부분
// 1·2는 run 구간의 파드 목록·재시작 횟수(export-prom.mjs가 남긴 prom/api_pods.json, prom/api_restarts.json)를 지금의
// 파드 목록과 대조해 찾는다. 3은 파드별로 현재 로그 파일의 첫 줄 시각을 본다. 회전이 없었다면 첫 줄은 컨테이너가
// 막 떠서 찍은 줄이라 컨테이너 시작 시각 근처다. 첫 줄이 max(run 시작, 컨테이너 시작 + 여유)보다 늦으면 run 구간의
// 앞부분이 회전으로 지워진 것이다. run 시작 전에 일어난 회전은 run 구간을 지우지 않으므로 문제 삼지 않는다.
// 셋 중 하나라도 있거나 확인할 수 없으면 판정 불가로 끝낸다. 파드 목록·재시작 횟수가 run 시작부터 덮여 있어야
// 하므로, 내보낸 구간(prom/_meta.json의 startSec)이 run 시작보다 늦게 시작하면 그것도 판정 불가다 — 그 사이에
// 생겼다 지워진 파드를 놓친다.
//
//   node scripts/loadtest/pod-coverage.mjs --pods <run>/prom/api_pods.json --restarts <run>/prom/api_restarts.json \
//     --existing <"파드이름 재시작횟수 컨테이너시작시각" 줄 파일> --first-lines <"파드이름 첫줄시각" 줄 파일> \
//     --since <run 시작 UTC ISO> --meta <run>/prom/_meta.json [--startup-slack-sec 120]
//
// --startup-slack-sec(기본 120초, 잠정값)는 컨테이너가 뜬 뒤 첫 로그 줄을 찍기까지 허용하는 시간이다. 짧게 잡으면
// 회전이 없는데도 판정 불가가 나고(보수 쪽 오류), 길게 잡으면 그만큼 이른 회전을 놓친다.
//
// 종료 코드: 0 지워진 파드·재시작·run 구간 회전이 없다(이 세 가지를 확인했다), 2 그중 하나가 있거나 확인하지 못했다.
import { readFileSync } from "node:fs";
import { parseArgs } from "node:util";

try {
  const { values: a } = parseArgs({
    options: {
      pods: { type: "string" },
      restarts: { type: "string" },
      existing: { type: "string" },
      "first-lines": { type: "string" },
      since: { type: "string" },
      meta: { type: "string" },
      "startup-slack-sec": { type: "string", default: "120" },
    },
  });
  if (!a.pods || !a.restarts || !a.existing || !a["first-lines"] || !a.since || !a.meta) {
    throw new Error("--pods, --restarts, --existing, --first-lines, --since, --meta가 필요하다");
  }
  const since = Date.parse(a.since);
  if (!Number.isFinite(since)) throw new Error(`--since를 읽지 못했다: ${a.since}`);
  if (!/^\d+$/.test(a["startup-slack-sec"])) throw new Error("--startup-slack-sec는 0 이상의 정수다");
  const slackMs = Number(a["startup-slack-sec"]) * 1000;
  // 내보낸 구간이 run 시작을 덮어야 run 구간의 파드를 모두 안다.
  const exportStartSec = Number(JSON.parse(readFileSync(a.meta, "utf8"))?.startSec);
  if (!Number.isFinite(exportStartSec)) throw new Error(`${a.meta}: startSec를 읽지 못했다(export-prom.mjs로 다시 내보낸다)`);
  if (exportStartSec * 1000 > since) {
    throw new Error(`내보낸 구간이 run 시작보다 늦게 시작한다(${new Date(exportStartSec * 1000).toISOString()} > ${a.since}) — --start를 run 시작 이전으로 다시 내보낸다`);
  }

  // Prometheus query_range 응답에서 시계열을 꺼낸다. 성공 응답이 아니면 예외.
  const series = (file) => {
    const body = JSON.parse(readFileSync(file, "utf8"));
    if (body?.status !== "success") throw new Error(`${file}: Prometheus 응답 status=${body?.status}`);
    return body.data?.result ?? [];
  };
  // 공백으로 나뉜 줄 파일. 첫 칸이 파드 이름("pod/" 접두는 뗀다).
  const rows = (file) =>
    readFileSync(file, "utf8")
      .split(/\r?\n/)
      .map((l) => l.trim().split(/\s+/))
      .filter((p) => p[0])
      .map(([name, ...rest]) => [name.replace(/^pod\//, ""), rest]);

  const seen = series(a.pods).map((s) => s.metric?.pod).filter(Boolean);
  if (seen.length === 0) throw new Error(`${a.pods}: run 구간의 api 파드가 하나도 없다(kube-state-metrics 수집 실패?)`);

  // run 구간 안에서 재시작 횟수가 늘었으면 그 전 컨테이너의 로그는 읽을 수 없다. 마지막 값은 아래 대조에 쓴다.
  const restartedInRun = [];
  const lastRestarts = new Map();
  for (const s of series(a.restarts)) {
    const vs = (s.values ?? []).map((v) => Number(v[1]));
    if (vs.length === 0 || vs.some((v) => !Number.isFinite(v))) continue;
    if (Math.max(...vs) > Math.min(...vs)) restartedInRun.push(s.metric?.pod);
    lastRestarts.set(s.metric?.pod, vs[vs.length - 1]);
  }

  // 지금 파드: 이름 → { restarts, startedAt(ms) }
  const existing = new Map(rows(a.existing).map(([name, [r, st]]) => [name, { restarts: Number(r), startedAt: Date.parse(st ?? "") }]));
  // 파드별 현재 로그 파일의 첫 줄 시각(ms)
  const firstLine = new Map(rows(a["first-lines"]).map(([name, [ts]]) => [name, Date.parse(ts ?? "")]));

  const missing = seen.filter((p) => !existing.has(p));
  // run이 끝난 뒤 지금까지 재시작했으면 지금 컨테이너에는 run 구간 로그가 없다. 재시작 횟수를 모르면 같은 취급이다.
  const restartedAfterOrUnknown = seen.filter((p) => {
    const now = existing.get(p);
    const last = lastRestarts.get(p);
    if (now === undefined) return false;
    return !Number.isFinite(now.restarts) || last === undefined || now.restarts > last;
  });
  // run 구간 앞부분이 회전으로 지워졌거나, 첫 줄·컨테이너 시작 시각을 읽지 못한 파드
  const rotatedOrUnknown = [];
  for (const p of seen) {
    const now = existing.get(p);
    if (now === undefined) continue; // 이미 missing으로 셌다
    const first = firstLine.get(p);
    if (!Number.isFinite(first) || !Number.isFinite(now.startedAt)) {
      rotatedOrUnknown.push({ pod: p, reason: "첫 줄 또는 컨테이너 시작 시각을 읽지 못했다" });
    } else if (first > Math.max(since, now.startedAt + slackMs)) {
      rotatedOrUnknown.push({
        pod: p,
        reason: "현재 로그 파일의 첫 줄이 run 시작·컨테이너 시작보다 늦다(회전)",
        firstLine: new Date(first).toISOString(),
        containerStartedAt: new Date(now.startedAt).toISOString(),
      });
    }
  }

  const ok = missing.length === 0 && restartedInRun.length === 0 && restartedAfterOrUnknown.length === 0 &&
    rotatedOrUnknown.length === 0;
  console.log(JSON.stringify({
    seen, missing, restartedInRun, restartedAfterOrUnknown, rotatedOrUnknown,
    startupSlackSec: slackMs / 1000, complete: ok,
  }, null, 2));
  process.exitCode = ok ? 0 : 2;
} catch (e) {
  console.error(`[pod-coverage] 확인 실패: ${e.message || e}`);
  process.exitCode = 2;
}
