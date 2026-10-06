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
// 대상 파드는 내보낸 구간 전체가 아니라 **run 구간과 겹친 파드**다. 내보낸 구간은 run 시작 2분 전부터라, 그 사이 리셋·HPA
// 축소로 지워진 파드까지 세면 run과 무관한 파드가 "빠짐"으로 잡혀 판정 불가가 난다(측정 세션 20261005-1440에서 6 run·16개 파드).
//   대상: 마지막 표본 > run 시작 − 내보내기 간격(_meta.step)  그리고  첫 표본 ≤ run 종료 + 여유(--grace-sec)
// 아래쪽에 내보내기 간격만큼 여유를 두는 것은 평가 격자가 run 시작과 어긋나도 run 시작 직후 지워진 파드를 놓치지 않기 위해서다.
// 표본은 내보낸 query_range의 평가 시각이다. kube-state-metrics에서 파드가 사라지면 다음 수집 때 시계열이 끊기므로 마지막
// 표본은 실제 삭제보다 최대 "수집 주기 + 내보내기 간격"만큼 늦다 — 그래서 run 직전에 지워진 파드는 대상에 남을 수 있다
// (판정 불가 쪽으로만 틀린다). run 시작 뒤까지 있던 파드는 격자 정렬과 무관하게 대상이다(거짓 통과를 만들지 않는다).
// 재시작 비교의 기준값은 run 종료 + 여유 시각까지의 마지막 값이다 — 그 뒤 재시작은 "지금 재시작 횟수 > 기준값"으로 잡힌다.
// 내보낸 구간은 run 종료 + 여유까지 덮어야 한다(_meta.endSec). 덮지 않으면 판정 불가다.
// run 도중 지워진 파드는 대상이고 지금 없으므로 "빠짐"이다. run 도중 새로 뜬 파드(HPA 확장)도 대상이지만, 지금 있으면
// 생긴 때부터의 로그가 남아 있다 — 앞부분 회전은 아래 첫 줄 검사가 본다. 그래서 run 중 생성만으로는 판정 불가가 아니다.
//
//   node scripts/loadtest/pod-coverage.mjs --pods <run>/prom/api_pods.json --restarts <run>/prom/api_restarts.json \
//     --existing <"파드이름 재시작횟수 컨테이너시작시각" 줄 파일> --first-lines <"파드이름 첫줄시각" 줄 파일> \
//     --since <run 시작 UTC ISO> --until <run 종료 UTC ISO> --meta <run>/prom/_meta.json \
//     [--startup-slack-sec 120] [--grace-sec 45]
//
// --grace-sec(기본 45초, 잠정값)는 run 종료 직전에 뜬 파드의 첫 표본이 수집 지연으로 종료 뒤에 찍히는 것을 덮는다.
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
      until: { type: "string" },
      meta: { type: "string" },
      "grace-sec": { type: "string", default: "45" },
      "startup-slack-sec": { type: "string", default: "120" },
    },
  });
  if (!a.pods || !a.restarts || !a.existing || !a["first-lines"] || !a.since || !a.until || !a.meta) {
    throw new Error("--pods, --restarts, --existing, --first-lines, --since, --until, --meta가 필요하다");
  }
  const since = Date.parse(a.since);
  if (!Number.isFinite(since)) throw new Error(`--since를 읽지 못했다: ${a.since}`);
  const until = Date.parse(a.until);
  if (!Number.isFinite(until) || !(until > since)) throw new Error(`--until을 읽지 못했거나 --since보다 이르다: ${a.until}`);
  if (!/^\d+$/.test(a["grace-sec"])) throw new Error("--grace-sec는 0 이상의 정수다");
  const graceMs = Number(a["grace-sec"]) * 1000;
  if (!/^\d+$/.test(a["startup-slack-sec"])) throw new Error("--startup-slack-sec는 0 이상의 정수다");
  const slackMs = Number(a["startup-slack-sec"]) * 1000;
  // 내보낸 구간이 run 시작을 덮어야 run 구간의 파드를 모두 안다.
  const meta = JSON.parse(readFileSync(a.meta, "utf8"));
  const exportStartSec = Number(meta?.startSec);
  const exportEndSec = Number(meta?.endSec);
  const stepMs = Number(meta?.step) * 1000;
  if (!Number.isFinite(exportEndSec) || !(stepMs > 0)) throw new Error(`${a.meta}: endSec·step을 읽지 못했다(export-prom.mjs로 다시 내보낸다)`);
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

  // 파드별 관측 구간(값이 있는 표본의 첫·마지막 평가 시각, ms)으로 run 구간과 겹친 파드만 대상으로 한다.
  const iso = (ms) => new Date(ms).toISOString();
  const observed = [];
  for (const s of series(a.pods)) {
    const pod = s.metric?.pod;
    const ts = (s.values ?? []).filter((v) => Number.isFinite(Number(v?.[1]))).map((v) => Number(v[0]) * 1000);
    if (!pod || ts.length === 0) continue;
    observed.push({ pod, first: Math.min(...ts), last: Math.max(...ts) });
  }
  if (exportEndSec * 1000 < until + graceMs) {
    throw new Error(`내보낸 구간이 run 종료 + 여유(${new Date(until + graceMs).toISOString()})보다 일찍 끝난다 — --end를 늘려 다시 내보낸다`);
  }
  const inRun = (o) => o.last > since - stepMs && o.first <= until + graceMs;
  const seen = observed.filter(inRun).map((o) => o.pod);
  const outsideRun = observed.filter((o) => !inRun(o)).map((o) => ({
    pod: o.pod, firstSample: iso(o.first), lastSample: iso(o.last),
    reason: o.last <= since - stepMs ? "run 시작 전에 사라짐" : "run 종료 뒤에 생김",
  }));
  if (seen.length === 0) throw new Error(`${a.pods}: run 구간의 api 파드가 하나도 없다(kube-state-metrics 수집 실패?)`);

  // run 구간 안에서 재시작 횟수가 늘었으면 그 전 컨테이너의 로그는 읽을 수 없다. 마지막 값은 아래 대조에 쓴다.
  const restartedInRun = [];
  const lastRestarts = new Map();
  for (const s of series(a.restarts)) {
    const pod = s.metric?.pod;
    if (!seen.includes(pod)) continue; // run과 겹치지 않은 파드의 재시작은 run 로그와 무관하다
    const pts = (s.values ?? []).map((v) => [Number(v[0]) * 1000, Number(v[1])]);
    if (pts.length === 0 || pts.some(([t, v]) => !Number.isFinite(t) || !Number.isFinite(v))) continue;
    // run 구간의 증가만 본다: run 시작 직전 마지막 값(없으면 구간 첫 값)부터 run 종료 + 여유까지.
    const before = pts.filter(([t]) => t <= since);
    const from = before.length ? before[before.length - 1][0] : -Infinity;
    const inWin = pts.filter(([t]) => t >= from && t <= until + graceMs).map(([, v]) => v);
    if (inWin.length && Math.max(...inWin) > Math.min(...inWin)) restartedInRun.push(pod);
    // 기준값: run 종료 + 여유까지의 마지막 값. 그 뒤 재시작은 아래 '지금 > 기준값' 비교가 잡는다.
    const upToEnd = pts.filter(([t]) => t <= until + graceMs);
    lastRestarts.set(pod, (upToEnd.length ? upToEnd : pts)[upToEnd.length ? upToEnd.length - 1 : 0][1]);
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
    seen, missing, restartedInRun, restartedAfterOrUnknown, rotatedOrUnknown, outsideRun,
    startupSlackSec: slackMs / 1000, graceSec: graceMs / 1000, complete: ok,
  }, null, 2));
  process.exitCode = ok ? 0 : 2;
} catch (e) {
  console.error(`[pod-coverage] 확인 실패: ${e.message || e}`);
  process.exitCode = 2;
}
