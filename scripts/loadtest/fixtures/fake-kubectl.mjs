#!/usr/bin/env node
// collect-api-logs.test.mjs용 가짜 kubectl. 상태는 FAKE_KUBE_STATE(JSON 파일)에서 매 호출마다 읽는다(테스트는 임시 파일 + rename으로 바꾼다):
//   { pods: { <파드>: { containerID: "c1" | null, state: "running" | "terminated" | "waiting" } },   — 없는 파드 = 지워짐
//     logs: { <파드>: { lines: [[<RFC3339Nano>, <내용>], ...], mode: "hold" | "close" | "fail" | "hang" | "partial" } },
//     getPodFailOnce: [<파드>, ...], getPodsFail: true | false }                                                                 — get pod가 한 번 실패
//   get pods -o json: containerStatuses(api)를 상태대로 낸다(waiting이면 containerID 없음 — 실제 ContainerCreating과 같다).
//   get pod <이름> -o json --ignore-not-found: 있으면 JSON, 없으면 빈 출력.
//   logs [-f] <파드> --prefix --timestamps --since-time=<초>: since 이후 줄을 `[pod/<파드>/api] <시각> <내용>`으로 낸다.
//     hold: -f면 붙어 있으면서 새로 추가된 줄을 내고, 파드가 지워지거나·컨테이너가 끝나거나·바뀌면 0으로 끝난다(실제 kubectl과 같다 — 임시 파드로 확인).
//     close: 낼 줄을 내고 바로 0으로 끝난다(살아 있는데 스트림이 끊긴 경우). fail: 아무것도 내지 않고 1.
//     hang: -f면 아무것도 내지 않고 붙어 있다(무응답), follow 없이면 1. partial: 마지막 줄을 반쯤만 내고(줄바꿈 없이) 0으로 끝난다.
import { existsSync, readFileSync, writeFileSync } from "node:fs";

const FILE = process.env.FAKE_KUBE_STATE;
const load = () => { try { return JSON.parse(readFileSync(FILE, "utf8")); } catch { return null; } };
const args = process.argv.slice(2);
const state = load() ?? process.exit(1);
const podJson = (name, p) => ({
  metadata: { name },
  status: { containerStatuses: [{ name: "api", ...(p.containerID ? { containerID: p.containerID } : {}),
    state: p.state === "terminated" ? { terminated: { exitCode: 0 } } : p.state === "waiting" ? { waiting: {} } : { running: {} } }] },
});

const li = args.indexOf("logs");
if (li >= 0) {
  const follow = args.includes("-f");
  const pod = args.find((x, i) => i > li && !x.startsWith("-") && args[i - 1] !== "-c" && args[i - 1] !== "-n");
  const since = Date.parse(args.find((x) => x.startsWith("--since-time=")).slice(13));
  const l = state.logs[pod] ?? { lines: [], mode: "hold" };
  if (l.mode === "fail" || !state.pods[pod]) process.exit(1);
  if (l.mode === "hang") { if (!follow) process.exit(1); setInterval(() => {}, 1000); }
  else {
    let sent = 0;
    const out = (lines) => {
      for (; sent < lines.length; sent++) {
        const [ts, text] = lines[sent];
        if (Date.parse(ts.replace(/\.\d+Z$/, "Z")) >= since) process.stdout.write(`[pod/${pod}/api] ${ts} ${text}\n`);
      }
    };
    if (l.mode === "partial") {
      out(l.lines.slice(0, -1));
      const [ts, text] = l.lines[l.lines.length - 1];
      process.stdout.write(`[pod/${pod}/api] ${ts} ${text.slice(0, Math.ceil(text.length / 2))}`);
      process.exit(0);
    }
    out(l.lines);
    if (!follow || l.mode === "close") process.exit(0);
    const cid = state.pods[pod].containerID;
    setInterval(() => {
      const now = load();
      if (!now) return; // 바꾸는 도중이면 다음에
      const p = now.pods[pod];
      out((now.logs[pod] ?? l).lines);
      if (!p || p.state !== "running" || p.containerID !== cid) process.exit(0);
    }, 50);
  }
} else if (args.includes("pods")) {
  if (state.getPodsFail) process.exit(1); // 목록 조회 장애
  process.stdout.write(JSON.stringify({ items: Object.entries(state.pods).map(([n, p]) => podJson(n, p)) }));
} else if (args.includes("pod")) {
  const name = args[args.indexOf("pod") + 1];
  // 한 번 실패: 상태 파일을 다시 쓰지 않고 표시 파일로 센다(테스트의 상태 교체와 겹쳐 되돌려 쓰지 않게)
  if ((state.getPodFailOnce ?? []).includes(name) && !existsSync(`${FILE}.failed-${name}`)) {
    writeFileSync(`${FILE}.failed-${name}`, "1");
    process.exit(1);
  }
  const p = state.pods[name];
  process.stdout.write(p ? JSON.stringify(podJson(name, p)) : "");
}
