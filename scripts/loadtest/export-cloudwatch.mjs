#!/usr/bin/env node
// run 구간의 RDS·ElastiCache CloudWatch 지표를 run 디렉터리에 남긴다(loadtest-100k-plan §5.3, §7 "원시 데이터").
//
// RDS와 ElastiCache는 클러스터 밖 관리형 서비스라 Prometheus에 없다. 측정 envelope의 RDS·ElastiCache는 t4g(버스터블)라,
// CPU 크레딧 상태가 결과에 섞일 수 있다 — 같은 workload라도 직전 부하에 따라 크레딧이 남았는지가 달라진다. 병목이
// "용량"인지 "크레딧 소진"인지 가르려면 run마다 CPU와 크레딧 지표를 함께 남겨야 한다. CloudWatch도 철거 뒤 리소스가
// 사라지면 조회 화면에서 찾기 어려우므로, run이 끝날 때마다 내보낸다.
//
//   node scripts/loadtest/export-cloudwatch.mjs --out artifacts/loadtest/<session>/<run> \
//     --start 2026-10-05T05:00:00Z --end 2026-10-05T05:12:00Z [--period 60] [--rds-id flowticket] [--redis-group flowticket-redis]
//
// --start는 run 시작 이전, --end는 run 종료 이후로 잡는다. CloudWatch 지표는 수집·집계에 몇 분 늦게 들어오므로(추론,
// 표준 모니터링 기준) run이 끝나고 5분쯤 뒤에 내보낸다. --period 기본 60초는 RDS 표준 모니터링과 ElastiCache의 기본
// 해상도다(Enhanced Monitoring은 켜지 않았다).
//
// 결과: <out>/cloudwatch/metrics.json(GetMetricData 응답의 MetricDataResults 그대로), <out>/cloudwatch/_meta.json(대상·
// 구간·질의 목록·질의별 점 수와 상태).
// 종료 코드: 0 모든 질의가 Complete이고 점이 하나 이상, 1 일부 질의가 비었거나 Complete가 아님·AWS 호출 실패(위반 아님),
// 2 인자 오류.
import { execFile } from "node:child_process";
import { mkdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { parseArgs } from "node:util";

// 지표 목록. 통계는 판정·진단에 쓰는 방향으로 고른다(크레딧은 최소, 사용량은 합, 지연은 평균·최대).
export const RDS_METRICS = [
  ["CPUUtilization", "Average"], ["CPUUtilization", "Maximum"],
  ["CPUCreditBalance", "Minimum"], ["CPUCreditUsage", "Sum"],
  // RDS T 계열은 Unlimited 모드로 동작하는 것으로 알려져 있다(추론, 이 계정에서 확인하지 않음). 그러면 크레딧이 떨어져도
  // 성능이 깎이지 않고 잉여 크레딧이 과금된다 — 그 흔적이 아래 두 지표다.
  ["CPUSurplusCreditBalance", "Maximum"], ["CPUSurplusCreditsCharged", "Sum"],
  ["DatabaseConnections", "Maximum"], ["ReadLatency", "Average"], ["WriteLatency", "Average"],
  ["FreeableMemory", "Minimum"], ["DiskQueueDepth", "Maximum"],
];
export const REDIS_METRICS = [
  ["CPUUtilization", "Maximum"], ["EngineCPUUtilization", "Maximum"],
  ["CPUCreditBalance", "Minimum"], ["CPUCreditUsage", "Sum"],
  ["CurrConnections", "Maximum"], ["NetworkBytesIn", "Sum"], ["NetworkBytesOut", "Sum"],
  ["DatabaseMemoryUsagePercentage", "Maximum"], ["Evictions", "Sum"],
];

// GetMetricData 질의. Id는 소문자로 시작하고 영숫자·밑줄만 쓴다.
export function buildQueries({ rdsId, cacheClusterIds, period }) {
  const q = [];
  const id = (s) => s.toLowerCase().replace(/[^a-z0-9_]/g, "_");
  for (const [m, stat] of RDS_METRICS) {
    q.push({ Id: id(`rds_${m}_${stat}`), Label: `rds/${rdsId}/${m}/${stat}`,
      MetricStat: { Metric: { Namespace: "AWS/RDS", MetricName: m, Dimensions: [{ Name: "DBInstanceIdentifier", Value: rdsId }] }, Period: period, Stat: stat } });
  }
  cacheClusterIds.forEach((cid, i) => {
    for (const [m, stat] of REDIS_METRICS) {
      q.push({ Id: id(`redis${i}_${m}_${stat}`), Label: `redis/${cid}/${m}/${stat}`,
        MetricStat: { Metric: { Namespace: "AWS/ElastiCache", MetricName: m, Dimensions: [{ Name: "CacheClusterId", Value: cid }] }, Period: period, Stat: stat } });
    }
  });
  return q;
}

// 질의별 점 수와 상태. 비었거나 Complete가 아니면 문제로 센다.
export function summarize(queries, results) {
  const byId = new Map();
  for (const r of results) {
    const prev = byId.get(r.Id);
    // 페이지가 나뉘면 같은 Id가 여러 번 온다. 점을 합치고, 하나라도 Complete가 아니면 그 상태를 남긴다.
    if (prev) { prev.points += (r.Values || []).length; if (r.StatusCode !== "Complete") prev.status = r.StatusCode; }
    else byId.set(r.Id, { points: (r.Values || []).length, status: r.StatusCode });
  }
  const perQuery = queries.map((q) => ({ id: q.Id, label: q.Label, ...(byId.get(q.Id) || { points: 0, status: "Missing" }) }));
  const problems = perQuery.filter((p) => p.points === 0 || p.status !== "Complete").map((p) => `${p.label}: ${p.status}, 점 ${p.points}`);
  return { perQuery, problems };
}

// AWS CLI. 테스트에서는 AWS_CLI_BIN에 가짜 CLI(.mjs)를 줘서 실제 AWS를 부르지 않는다.
function aws(args) {
  const bin = process.env.AWS_CLI_BIN || "aws";
  const [cmd, pre] = bin.endsWith(".mjs") ? [process.execPath, [bin]] : [bin, []];
  return new Promise((resolve, reject) => {
    execFile(cmd, [...pre, ...args, "--output", "json"], { timeout: 120_000, maxBuffer: 64 * 1024 * 1024 }, (err, stdout, stderr) => {
      if (err) return reject(new Error(`aws ${args.slice(0, 2).join(" ")} 실패: ${(stderr || err.message).trim().split("\n").pop()}`));
      try { resolve(JSON.parse(stdout)); } catch (e) { reject(new Error(`aws ${args.slice(0, 2).join(" ")} 응답을 읽지 못했다: ${e.message}`)); }
    });
  });
}

async function main() {
  const USAGE = "사용: export-cloudwatch.mjs --out <run 디렉터리> --start <ISO> --end <ISO> [--period 60] [--rds-id flowticket] [--redis-group flowticket-redis]";
  let a;
  try {
    ({ values: a } = parseArgs({
      options: {
        out: { type: "string" }, start: { type: "string" }, end: { type: "string" },
        period: { type: "string", default: "60" },
        "rds-id": { type: "string", default: "flowticket" },
        "redis-group": { type: "string", default: "flowticket-redis" },
        region: { type: "string", default: process.env.AWS_REGION || "ap-northeast-2" },
      },
    }));
  } catch (e) {
    console.error(`${e.message}\n${USAGE}`);
    process.exitCode = 2;
    return;
  }
  const start = Date.parse(a.start ?? ""), end = Date.parse(a.end ?? "");
  const idOk = (s) => /^[A-Za-z0-9-]+$/.test(s ?? "");
  if (!a.out || !Number.isFinite(start) || !Number.isFinite(end) || !(end > start) || !/^[1-9]\d*$/.test(a.period) ||
      Number(a.period) % 60 !== 0 || !idOk(a["rds-id"]) || !idOk(a["redis-group"]) || !idOk(a.region)) {
    console.error(`${USAGE}\n(--period는 60의 배수, 식별자는 영숫자·하이픈)`);
    process.exitCode = 2;
    return;
  }
  const dir = `${a.out}/cloudwatch`;
  mkdirSync(dir, { recursive: true });
  // 이전 내보내기와 섞이지 않게 먼저 지운다. 도중에 죽으면 _meta.json이 없어 불완전한 결과로 보인다.
  rmSync(`${dir}/_meta.json`, { force: true });
  rmSync(`${dir}/metrics.json`, { force: true });

  const region = ["--region", a.region];
  // 복제 그룹의 노드(캐시 클러스터) 목록. 노드마다 지표가 따로 있다.
  const rg = await aws(["elasticache", "describe-replication-groups", "--replication-group-id", a["redis-group"], ...region]);
  const cacheClusterIds = rg?.ReplicationGroups?.[0]?.MemberClusters ?? [];
  if (cacheClusterIds.length === 0) throw new Error(`복제 그룹 ${a["redis-group"]}의 노드를 찾지 못했다`);

  const queries = buildQueries({ rdsId: a["rds-id"], cacheClusterIds, period: Number(a.period) });
  const qfile = join(tmpdir(), `cw-queries-${process.pid}.json`);
  writeFileSync(qfile, JSON.stringify(queries));
  const results = [];
  try {
    let token;
    do {
      const args = ["cloudwatch", "get-metric-data", "--metric-data-queries", `file://${qfile}`,
        "--start-time", new Date(start).toISOString(), "--end-time", new Date(end).toISOString(), "--scan-by", "TimestampAscending", ...region];
      if (token) args.push("--next-token", token);
      const r = await aws(args);
      results.push(...(r.MetricDataResults ?? []));
      token = r.NextToken;
    } while (token);
  } finally {
    rmSync(qfile, { force: true });
  }

  const { perQuery, problems } = summarize(queries, results);
  writeFileSync(`${dir}/metrics.json`, JSON.stringify(results, null, 2));
  writeFileSync(`${dir}/_meta.json`, JSON.stringify({
    start: a.start, end: a.end, period: Number(a.period), region: a.region,
    rdsId: a["rds-id"], redisGroup: a["redis-group"], cacheClusterIds, perQuery, problems,
  }, null, 2));
  for (const p of problems) console.error(`[cloudwatch] ${p}`);
  console.error(`[cloudwatch] 질의 ${queries.length}개, 문제 ${problems.length}개 → ${dir}`);
  process.exitCode = problems.length ? 1 : 0;
}

if (/export-cloudwatch\.mjs$/.test(process.argv[1] ?? "")) {
  main().catch((e) => {
    // AWS 호출 실패는 위반이 아니라 "일부 실패"(1)다. 인자 오류(2)와 구분한다.
    console.error(`[cloudwatch] 실패: ${e.message || e}`);
    process.exitCode = 1;
  });
}
