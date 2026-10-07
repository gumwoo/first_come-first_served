#!/usr/bin/env node
// collect-api-logs.test.mjs용 가짜 kubectl. 상태는 FAKE_KUBE_STATE(JSON 파일)에서 매 호출마다 읽는다:
//   { pods: ["a", ...],               — get pods 목록(지금 있는 파드)
//     logs: { a: { lines: [..], mode: "hold" | "close" } } }
//   logs -f: lines를 `[pod/<파드>/api] <줄>`로 내고, hold면 붙어 있다가 파드가 pods에서 빠지면 끝난다(실제 kubectl이 컨테이너 종료로
//            스트림을 닫는 것과 같다), close면 바로 끝난다(파드가 살아 있는데 스트림이 끊긴 경우).
//   get pod <이름>: pods에 있으면 이름을, 없으면 빈 출력(--ignore-not-found와 같다).
import { readFileSync } from "node:fs";

const state = JSON.parse(readFileSync(process.env.FAKE_KUBE_STATE, "utf8"));
const args = process.argv.slice(2);
const i = args.indexOf("logs");
if (args.includes("pods")) {
  process.stdout.write(state.pods.map((p) => p + "\n").join(""));
} else if (i >= 0) {
  const pod = args[i + 2];
  const l = state.logs[pod] ?? { lines: [], mode: "hold" };
  process.stdout.write(l.lines.map((x) => `[pod/${pod}/api] ${x}\n`).join(""));
  if (l.mode === "hold") {
    setInterval(() => {
      const now = JSON.parse(readFileSync(process.env.FAKE_KUBE_STATE, "utf8"));
      if (!now.pods.includes(pod)) process.exit(0);
    }, 100);
  }
} else if (args.includes("pod")) {
  const name = args[args.indexOf("pod") + 1];
  process.stdout.write(state.pods.includes(name) ? `${name} ` : "");
}
