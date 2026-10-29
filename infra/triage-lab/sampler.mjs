// 1초마다 api·대기열의 /actuator/prometheus에서 필요한 지표만 읽어 한 줄(JSON)씩 쓴다.
//   node sampler.mjs <out.jsonl> <seconds>
// 히스토그램·타이머는 누적값(sum/count)을 그대로 남긴다. 구간 평균은 분석 쪽에서 차분으로 구한다
// (지표 응답이 밀려도 누적값은 표본 시각과 무관하게 맞는다, IMP-043).
import fs from "node:fs";

const [out, secs] = process.argv.slice(2);
if (!out || !Number(secs)) {
  console.error("사용: node sampler.mjs <out.jsonl> <seconds>");
  process.exit(2);
}
const PORTS = { api: Number(process.env.LAB_API_PORT || 18080), queue: Number(process.env.LAB_QUEUE_PORT || 18081) }; // lab.sh와 같은 환경변수
const WANT = /^(process_cpu_time_ns_total|tomcat_threads_busy_threads|hikaricp_connections_active|hikaricp_connections_pending|hikaricp_connections_usage_seconds_(sum|count)|hikaricp_connections_acquire_seconds_(sum|count)|lettuce_command_completion_seconds_(sum|count))\b/;

async function read(port) {
  const t0 = Date.now();
  // localhost는 IPv6(::1)로 먼저 풀리는데, 이 PC의 Docker 포트 전달은 IPv6 연결을 끊어 지표를 못 읽은 run이 있었다(2026-10-29).
  const txt = await (await fetch(`http://127.0.0.1:${port}/actuator/prometheus`)).text();
  const t1 = Date.now();
  const row = { rtt: t1 - t0 };
  for (const line of txt.split("\n")) {
    if (!WANT.test(line)) continue;
    const i = line.lastIndexOf(" ");
    const name = line.slice(0, i).replace(/\{.*$/, "");
    row[name] = (row[name] ?? 0) + Number(line.slice(i + 1)); // 라벨(풀·명령)별 값을 합친다
  }
  return row;
}

const fd = fs.openSync(out, "w");
const end = Date.now() + Number(secs) * 1000;
while (Date.now() < end) {
  const t = Date.now();
  try {
    const rows = await Promise.all(Object.values(PORTS).map(read));
    fs.writeSync(fd, JSON.stringify({ t, ...Object.fromEntries(Object.keys(PORTS).map((k, i) => [k, rows[i]])) }) + "\n");
  } catch (e) {
    fs.writeSync(fd, JSON.stringify({ t, error: String(e) }) + "\n");
  }
  await new Promise((r) => setTimeout(r, Math.max(0, 1000 - (Date.now() - t))));
}
fs.closeSync(fd);
