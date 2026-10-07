#!/usr/bin/env node
// collect-api-logs.test.mjs용 가짜 kubectl. 상태는 FAKE_KUBE_STATE(JSON 파일)에서 매 호출마다 읽는다:
//   { pods: { <파드>: { containerID: "c1" | null, state: "running" | "terminated" | "waiting" } },   — 없는 파드 = 지워짐
//     logs: { <파드>: { lines: [[<RFC3339Nano>, <내용>], ...], mode: "hold" | "close" | "fail" } },
//     getPodFailOnce: [<파드>, ...] }                                                                 — get pod가 한 번 실패
//   get pods -o json: containerStatuses(api)를 상태대로 낸다(waiting이면 containerID 없음 — 실제 ContainerCreating과 같다).
//   get pod <이름> -o json --ignore-not-found: 있으면 JSON, 없으면 빈 출력.
//   logs [-f] <파드> --prefix --timestamps --since-time=<초>: since 이후 줄을 `[pod/<파드>/api] <시각> <내용>`으로 낸다.
//     hold: -f면 붙어 있으면서 새로 추가된 줄을 내고, 파드가 지워지거나·컨테이너가 끝나거나·바뀌면 끝난다(실제 kubectl이 컨테이너 종료로 닫는 것과 같다).
//     close: 낼 줄을 내고 바로 끝난다(살아 있는데 스트림이 끊긴 경우). fail: 아무것도 내지 않고 종료 코드 1.
import { readFileSync, writeFileSync } from "node:fs";

const FILE = process.env.FAKE_KUBE_STATE;
const load = () => JSON.parse(readFileSync(FILE, "utf8"));
const args = process.argv.slice(2);
const state = load();
const podJson = (name, p) => ({
  metadata: { name },
  status: { containerStatuses: [{ name: "api", ...(p.containerID ? { containerID: p.containerID } : {}),
    state: p.state === "terminated" ? { terminated: { exitCode: 0 } } : p.state === "waiting" ? { waiting: {} } : { running: {} } }] },
});

const li = args.indexOf("logs");
if (li >= 0) {
  const follow = args.includes("-f");
  const pod = args[li + (follow ? 2 : 1)];
  const since = Date.parse(args.find((x) => x.startsWith("--since-time=")).slice(13));
  const l = state.logs[pod] ?? { lines: [], mode: "hold" };
  if (l.mode === "fail" || !state.pods[pod]) process.exit(1);
  let sent = 0;
  const emit = (lines) => {
    for (; sent < lines.length; sent++) {
      const [ts, text] = lines[sent];
      if (Date.parse(ts.replace(/\.\d+Z$/, "Z")) >= since) process.stdout.write(`[pod/${pod}/api] ${ts} ${text}\n`);
    }
  };
  emit(l.lines);
  if (!follow || l.mode === "close") process.exit(0);
  const cid = state.pods[pod].containerID;
  setInterval(() => {
    const now = load();
    const p = now.pods[pod];
    emit((now.logs[pod] ?? l).lines);
    if (!p || p.state !== "running" || p.containerID !== cid) process.exit(0);
  }, 50);
} else if (args.includes("pods")) {
  process.stdout.write(JSON.stringify({ items: Object.entries(state.pods).map(([n, p]) => podJson(n, p)) }));
} else if (args.includes("pod")) {
  const name = args[args.indexOf("pod") + 1];
  if ((state.getPodFailOnce ?? []).includes(name)) {
    state.getPodFailOnce = state.getPodFailOnce.filter((x) => x !== name);
    writeFileSync(FILE, JSON.stringify(state));
    process.exit(1);
  }
  const p = state.pods[name];
  process.stdout.write(p ? JSON.stringify(podJson(name, p)) : "");
}
