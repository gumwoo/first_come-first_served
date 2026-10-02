#!/usr/bin/env node
// 대기열 순서 대조의 전제 확인(loadtest-100k-plan §3.3). run 동안 있었던 api 파드의 로그를 지금 전부 읽을 수 있는가.
//
// 사후 검사는 `kubectl logs`로 지금 살아 있는 파드의 현재 컨테이너 로그만 읽는다. run 도중이나 그 뒤에 HPA 축소로
// 지워진 파드, 재시작한 컨테이너의 이전 로그는 읽을 수 없다. 그 파드가 남긴 승격 줄이 빠지면 대기열 순서 대조가
// 일부 로그만 보고도 "위반 없음"을 낸다. 그래서 run 구간의 파드 목록·재시작 횟수(export-prom.mjs가 남긴
// prom/api_pods.json, prom/api_restarts.json)를 지금의 파드 목록과 대조해, 하나라도 빠졌으면 판정 불가로 끝낸다.
//
//   node scripts/loadtest/pod-coverage.mjs --pods <run>/prom/api_pods.json --restarts <run>/prom/api_restarts.json \
//     --existing <"파드이름 재시작횟수" 줄 파일>
//
// 종료 코드: 0 모든 파드의 run 구간 로그를 읽을 수 있다, 2 빠진 파드·재시작이 있거나 입력을 읽지 못했다.
// 한계: 컨테이너 로그 회전(kubelet)으로 지워진 앞부분은 이 검사로 알 수 없다(계획서 §3.3).
import { readFileSync } from "node:fs";
import { parseArgs } from "node:util";

try {
  const { values: a } = parseArgs({
    options: { pods: { type: "string" }, restarts: { type: "string" }, existing: { type: "string" } },
  });
  if (!a.pods || !a.restarts || !a.existing) throw new Error("--pods, --restarts, --existing가 필요하다");

  // Prometheus query_range 응답에서 시계열을 꺼낸다. 성공 응답이 아니면 예외.
  const series = (file) => {
    const body = JSON.parse(readFileSync(file, "utf8"));
    if (body?.status !== "success") throw new Error(`${file}: Prometheus 응답 status=${body?.status}`);
    return body.data?.result ?? [];
  };

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

  const existing = new Map(
    readFileSync(a.existing, "utf8")
      .split(/\r?\n/)
      .map((l) => l.trim().split(/\s+/))
      .filter((p) => p[0])
      .map(([name, r]) => [name.replace(/^pod\//, ""), Number(r)]),
  );

  const missing = seen.filter((p) => !existing.has(p));
  // run이 끝난 뒤 지금까지 재시작했으면 지금 컨테이너에는 run 구간 로그가 없다. 재시작 횟수를 모르면 같은 취급이다.
  const restartedAfterOrUnknown = seen.filter((p) => {
    const now = existing.get(p);
    const last = lastRestarts.get(p);
    if (now === undefined) return false;
    return !Number.isFinite(now) || last === undefined || now > last;
  });

  const ok = missing.length === 0 && restartedInRun.length === 0 && restartedAfterOrUnknown.length === 0;
  console.log(JSON.stringify({ seen, missing, restartedInRun, restartedAfterOrUnknown, complete: ok }, null, 2));
  process.exitCode = ok ? 0 : 2;
} catch (e) {
  console.error(`[pod-coverage] 확인 실패: ${e.message || e}`);
  process.exitCode = 2;
}
