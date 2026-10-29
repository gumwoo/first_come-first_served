// api 컨테이너 cgroup CPU 통계를 Prometheus 형식으로 낸다(:9101/metrics). run 수집의 cgroup-sampler.sh와 같은 파일을 읽는다.
// 이름은 cAdvisor(쿠버네티스 kubelet)와 같게 둬 운영에서도 같은 식을 쓸 수 있게 한다.
// 컨테이너 안에서 읽으면 그 컨테이너 할당량에 같이 묶이므로 호스트 cgroup 이름공간(cgroup: host)에서 돈다.
//   CGROUP_ID=<api 컨테이너 전체 id> node cgroup-exporter.mjs
import http from "node:http";
import fs from "node:fs";

const id = process.env.CGROUP_ID;
if (!id) { console.error("CGROUP_ID가 필요하다"); process.exit(2); }
const file = `/sys/fs/cgroup/docker/${id}/cpu.stat`;
const label = `{name="${process.env.CGROUP_NAME || "api"}"}`;

http.createServer((req, res) => {
  if (req.url !== "/metrics") { res.writeHead(404).end(); return; }
  let stat;
  try {
    stat = Object.fromEntries(fs.readFileSync(file, "utf8").trim().split("\n").map((l) => l.split(" ")).map(([k, v]) => [k, Number(v)]));
  } catch (e) {
    res.writeHead(500).end(String(e)); // 컨테이너가 다시 만들어지면 id가 바뀐다. 0으로 내지 않고 실패시킨다
    return;
  }
  res.writeHead(200, { "Content-Type": "text/plain; version=0.0.4" }).end([
    "# TYPE container_cpu_usage_seconds_total counter",
    `container_cpu_usage_seconds_total${label} ${stat.usage_usec / 1e6}`,
    "# TYPE container_cpu_cfs_periods_total counter",
    `container_cpu_cfs_periods_total${label} ${stat.nr_periods}`,
    "# TYPE container_cpu_cfs_throttled_periods_total counter",
    `container_cpu_cfs_throttled_periods_total${label} ${stat.nr_throttled}`,
    "",
  ].join("\n"));
}).listen(9101);
