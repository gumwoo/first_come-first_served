#!/usr/bin/env node
// run 구간의 RDS·ElastiCache CloudWatch 지표를 run 디렉터리에 남긴다(loadtest-100k-plan §5.3, §7 "원시 데이터").
//
// RDS와 ElastiCache는 클러스터 밖 관리형 서비스라 Prometheus에 없다. 측정 envelope의 RDS·ElastiCache는 t4g(버스터블)라
// CPU 크레딧, EBS I/O 버킷, 네트워크 버스트처럼 직전 부하에 따라 잔량이 달라지는 자원이 있다. 이 신호가 knee 구간에 보이면
// 그 knee가 용량 한계인지 버스트 소진인지 지표만으로는 가를 수 없다 — 그래서 자동으로 분류하지 않고 "버스트 영향 미분리"로
// 표시해 사양을 바꿔 다시 재도록 한다(계획서 §5.3). 그 판단의 근거가 되도록 run마다 이 지표들을 남긴다.
// 그래서 run마다 CPU와 크레딧 지표를 함께 남긴다. 리소스를 철거하면 조회 화면에서 찾기 어려우므로 run이 끝날 때마다 내보낸다.
//
//   node scripts/loadtest/export-cloudwatch.mjs --out artifacts/loadtest/<session>/<run> \
//     --start 2026-10-05T05:00:00Z --end 2026-10-05T05:12:00Z [--period 60] [--rds-id flowticket] [--redis-group flowticket-redis]
//
// --start는 run 시작 이전, --end는 run 종료 이후로 잡는다(시각은 Z 또는 오프셋이 붙은 ISO만 받는다). 해상도는 두 가지다.
//   - 일반 지표: --period(기본 60초) — RDS 표준 모니터링과 ElastiCache의 기본 해상도(Enhanced Monitoring은 켜지 않았다)
//   - CPU 크레딧 지표(CPUCredit*, CPUSurplus*): 5분(300초) — AWS 문서상 이 지표들은 5분 주기로만 발행된다
// 그래서 크레딧 소진은 5분 단위로만 보이고, 구간이 5분 경계를 하나 이상 덮어야 크레딧 점이 생긴다. CloudWatch 수집 지연과
// 5분 집계를 감안해 run이 끝나고 10분쯤 뒤에 내보내고, --end는 run 종료 + 5분 이후로 잡는다(추론, 실측하지 않음).
//
// 결과: <out>/cloudwatch/metrics.json(GetMetricData 응답의 MetricDataResults 그대로), <out>/cloudwatch/_meta.json(대상·
// 정규화한 구간·질의 목록·질의별 점 수와 상태·응답 메시지).
// 0은 "필수 질의마다 점이 하나 이상"이라는 뜻이지 구간 끝까지 다 받았다는 보장이 아니다 — 수집 지연 중에 내보내면 구간
// 앞쪽 점만으로 0이 날 수 있다(그래서 run 종료 10분 뒤에 내보낸다). 인자 오류(2)일 때는 이전 결과를 지우지 않는다.
// 종료 코드: 0 필수 질의가 모두 Complete이고 점이 하나 이상, 1 필수 질의가 빔·어떤 질의든 Complete가 아님(선택 질의의 응답
// 없음은 제외, 앞 페이지의 InternalError·Forbidden 포함)·응답이나 질의에 경고 메시지·복제 그룹 노드 없음·페이지 무한(같은
// 토큰 반복·100쪽 초과)·AWS 호출 실패·저장 실패(위반 아님), 2 인자 오류(달력상 없는 시각·미래 --end 포함).
// 선택 질의(RDS 잉여 크레딧, 버스트 버킷, 네트워크 기준 사용률, 트래픽 관리, Redis 명령 지연)는 발행되지 않을 수 있어(추론)
// 점이 0개여도 문제로 세지 않는다. 응답에서 아예 빠지면 문제다.
import { execFile } from "node:child_process";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { parseArgs } from "node:util";

// 지표 목록 [이름, 통계, 선택]. 통계는 판정·진단에 쓰는 방향으로 고른다(크레딧은 최소, 사용량은 합, 지연은 평균·최대).
// 선택 질의는 비어도 문제로 세지 않는다.
export const RDS_METRICS = [
  ["CPUUtilization", "Average"], ["CPUUtilization", "Maximum"],
  ["CPUCreditBalance", "Minimum"], ["CPUCreditUsage", "Sum"],
  // AWS 문서상 RDS db.t4g는 Unlimited 모드로 구성된다. 크레딧이 떨어져도 성능이 깎이지 않고(용량 결과는 그대로 유효),
  // 그 뒤 쓴 잉여 크레딧이 CPUSurplusCreditBalance에 쌓인다 — 이 값 > 0이면 "Unlimited 버스트 상태에서 잰 용량"으로 표기한다.
  // CPUSurplusCreditsCharged는 잉여가 24시간 최대치를 넘거나 인스턴스를 정지·종료할 때 과금이 확정된 양이라 짧은 run에서는 0일 수 있다.
  // 둘 다 잉여가 없으면 발행되지 않을 수 있어(추론) 선택 질의로 둔다.
  ["CPUSurplusCreditBalance", "Maximum", true], ["CPUSurplusCreditsCharged", "Sum", true],
  ["DatabaseConnections", "Maximum"], ["ReadLatency", "Average"], ["WriteLatency", "Average"],
  ["FreeableMemory", "Minimum"], ["DiskQueueDepth", "Maximum"],
  // EBS 버스트 버킷(인스턴스의 EBS I/O·처리량 크레딧, 백분율). 인스턴스 클래스에 따라 발행되지 않을 수 있어(추론) 선택 질의다 —
  // 비어 있으면 그 버킷에 묶였는지는 "미확인"으로 남는다.
  ["EBSIOBalance%", "Minimum", true], ["EBSByteBalance%", "Minimum", true],
];
export const REDIS_METRICS = [
  ["CPUUtilization", "Maximum"], ["EngineCPUUtilization", "Maximum"],
  ["CPUCreditBalance", "Minimum"], ["CPUCreditUsage", "Sum"],
  ["CurrConnections", "Maximum"], ["NetworkBytesIn", "Sum"], ["NetworkBytesOut", "Sum"],
  ["DatabaseMemoryUsagePercentage", "Maximum"], ["Evictions", "Sum"],
  // 네트워크 한도 초과(AWS 문서: 인스턴스의 최대치를 넘어 대기·손실된 패킷 수). 크레딧 소진인지 인스턴스 상한인지는 이것만으로
  // 가를 수 없다. 크레딧 소비 여부는 아래 NetworkBaseline* 백분율(100을 넘으면 버스트 크레딧을 쓰는 중 — AWS 문서)로 본다.
  // 발행 여부가 노드 타입·엔진에 따라 다를 수 있어(추론) 선택 질의다.
  ["NetworkBandwidthInAllowanceExceeded", "Sum", true], ["NetworkBandwidthOutAllowanceExceeded", "Sum", true],
  ["NetworkPacketsPerSecondAllowanceExceeded", "Sum", true],
  ["NetworkBaselineUsageInPercentage", "Maximum", true], ["NetworkBaselineUsageOutPercentage", "Maximum", true],
  // AWS 문서가 "용량이 부족하다"는 신호로 드는 지표(트래픽 관리가 켜짐).
  ["TrafficManagementActive", "Maximum", true],
  // 명령 처리 지연(마이크로초) — §3.2 진단 신호 "Redis 명령 지연". AWS 문서상 ElastiCache가 명령을 처리하는 데 쓴 CPU 시간
  // (delta(usec)/delta(calls))이라 큐 대기·네트워크 시간은 들어가지 않는다. 단일 스레드가 포화되면 클라이언트가 보는 지연은
  // 커져도 이 값은 평탄할 수 있으므로 포화는 EngineCPUUtilization과 함께 본다. Eval·SortedSet은 명령 종류별, Get·Set은 읽기·
  // 쓰기 명령 전체의 지연이다. 그 종류의 명령이 없던 구간은 발행되지 않을 수 있어(추론) 선택 질의다.
  ["EvalBasedCmdsLatency", "Average", true], ["SortedSetBasedCmdsLatency", "Average", true],
  ["GetTypeCmdsLatency", "Average", true], ["SetTypeCmdsLatency", "Average", true],
];

// CPU 크레딧 지표는 5분 주기로만 발행된다(AWS 문서). 더 잘게 질의해도 점이 늘지 않으므로 300초로 질의한다.
export const CREDIT_PERIOD = 300;
const isCredit = (m) => /^CPU(Credit|Surplus)/.test(m);

// GetMetricData 질의. Id는 소문자로 시작하고 영숫자·밑줄만 쓴다. optional은 비어도 문제로 세지 않는 질의다(우리 쪽 표시 —
// AWS에 보내기 전에 뺀다).
// ElastiCache 노드 지표는 CacheClusterId와 CacheNodeId 두 차원으로 발행된다(AWS 문서 예시). 클러스터 모드가 꺼진 복제 그룹의
// 멤버 클러스터는 노드가 하나라 CacheNodeId는 0001이다(문서 예시와 관례에 따른 추론 — 틀리면 질의가 비어 종료 1이 된다).
export function buildQueries({ rdsId, cacheClusterIds, period }) {
  const q = [];
  const id = (s) => s.toLowerCase().replace(/[^a-z0-9_]/g, "_");
  for (const [m, stat, optional] of RDS_METRICS) {
    q.push({ Id: id(`rds_${m}_${stat}`), Label: `rds/${rdsId}/${m}/${stat}`, optional: !!optional,
      MetricStat: { Metric: { Namespace: "AWS/RDS", MetricName: m, Dimensions: [{ Name: "DBInstanceIdentifier", Value: rdsId }] }, Period: isCredit(m) ? CREDIT_PERIOD : period, Stat: stat } });
  }
  cacheClusterIds.forEach((cid, i) => {
    for (const [m, stat, optional] of REDIS_METRICS) {
      q.push({ Id: id(`redis${i}_${m}_${stat}`), Label: `redis/${cid}/${m}/${stat}`, optional: !!optional,
        MetricStat: { Metric: { Namespace: "AWS/ElastiCache", MetricName: m,
          Dimensions: [{ Name: "CacheClusterId", Value: cid }, { Name: "CacheNodeId", Value: "0001" }] },
          Period: isCredit(m) ? CREDIT_PERIOD : period, Stat: stat } });
    }
  });
  return q;
}

// AWS에 보내는 질의(우리 쪽 표시 optional을 뺀다).
export const toApi = (queries) => queries.map(({ optional, ...rest }) => rest);

// 질의별 점 수와 상태. 필수 질의가 비었거나, 어떤 질의든 Complete가 아니면 문제로 센다. 응답 최상위 메시지(경고)도 문제다.
export function summarize(queries, results, messages = []) {
  const byId = new Map();
  for (const r of results) {
    const prev = byId.get(r.Id);
    // 페이지가 나뉘면 같은 Id가 여러 번 온다. 점을 합치고, 상태는 마지막 페이지 것을 쓴다 — 앞 페이지의 PartialData는
    // "NextToken으로 이어 받으라"는 정상 신호다(AWS 문서). 마지막 페이지까지 Complete가 아니면 문제다.
    // 다만 앞 페이지의 InternalError·Forbidden은 마지막 페이지가 Complete여도 빠진 데이터가 없다고 장담할 수 없어 문제로 남긴다.
    const bad = r.StatusCode === "InternalError" || r.StatusCode === "Forbidden";
    if (prev) { prev.points += (r.Values || []).length; prev.status = r.StatusCode; if (bad) prev.pageError = r.StatusCode; }
    else byId.set(r.Id, { points: (r.Values || []).length, status: r.StatusCode, ...(bad ? { pageError: r.StatusCode } : {}) });
  }
  const perQuery = queries.map((q) => ({ id: q.Id, label: q.Label, optional: !!q.optional, ...(byId.get(q.Id) || { points: 0, status: "Missing" }) }));
  const problems = perQuery
    // 선택 질의는 "점이 0개"만 봐준다. 응답에서 아예 빠진 질의(Missing)는 GetMetricData가 질의마다 결과를 돌려주므로(추론)
    // 이상이라 선택 질의여도 문제로 센다.
    .filter((p) => (p.points === 0 && !p.optional) || p.status !== "Complete" || p.pageError)
    .map((p) => `${p.label}: ${p.status}${p.pageError ? `(앞 페이지 ${p.pageError})` : ""}, 점 ${p.points}`);
  for (const m of messages) problems.push(`응답 메시지: ${m.Code}${m.Value ? " " + m.Value : ""}`);
  // 질의별 메시지(예: 일부 데이터 누락 경고)도 문제로 센다 — Complete여도 결과를 그대로 믿을 수 없다는 뜻이다.
  for (const r of results) for (const m of r.Messages || []) problems.push(`질의 메시지(${r.Id}): ${m.Code}${m.Value ? " " + m.Value : ""}`);
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
  // 오프셋 없는 시각은 Date.parse가 로컬 시간으로 읽는다. Z나 ±hh:mm이 붙은 ISO만 받는다.
  // 달력상 있는 시각인지도 본다(2월 30일·24시를 Date.parse가 넘겨 읽는 것을 막는다). 오프셋과 무관하게 날짜·시각 칸만 대조한다.
  const zoned = (x) => {
    const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d{1,3})?)?(?:Z|[+-]\d{2}:\d{2})$/.exec(x ?? "");
    if (!m) return false;
    const [y, mo, d, h, mi, s] = [m[1], m[2], m[3], m[4], m[5], m[6] ?? "0"].map(Number);
    const u = new Date(Date.UTC(y, mo - 1, d, h, mi, s));
    return u.getUTCFullYear() === y && u.getUTCMonth() === mo - 1 && u.getUTCDate() === d && u.getUTCHours() === h &&
      u.getUTCMinutes() === mi && u.getUTCSeconds() === s;
  };
  const start = zoned(a.start) ? Date.parse(a.start) : NaN, end = zoned(a.end) ? Date.parse(a.end) : NaN;
  const idOk = (s) => /^[A-Za-z0-9-]+$/.test(s ?? "");
  // --end가 지금보다 뒤면 그 구간은 아직 오지 않았다. 앞쪽 점만으로 "필수 질의마다 점 1개 이상"을 채워 0이 나올 수 있으므로
  // 인자 오류로 막는다(구간 끝의 데이터를 덜 받았는지는 이것으로 다 막지 못한다 — §5.3 한계).
  if (Number.isFinite(end) && end > Date.now()) {
    console.error(`--end(${a.end})가 지금보다 뒤다 — run이 끝나고 수집 지연을 기다린 뒤 내보낸다`);
    process.exitCode = 2;
    return;
  }
  if (!a.out || !Number.isFinite(start) || !Number.isFinite(end) || !(end > start) || !/^[1-9]\d*$/.test(a.period) ||
      Number(a.period) % 60 !== 0 || !idOk(a["rds-id"]) || !idOk(a["redis-group"]) || !idOk(a.region)) {
    console.error(`${USAGE}\n(시각은 Z·오프셋이 붙은 ISO, --period는 60의 배수, 식별자는 영숫자·하이픈)`);
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
  // 질의 파일은 이 실행만의 임시 디렉터리에 둔다(공유 tmpdir의 예측 가능한 이름을 피한다).
  const qdir = mkdtempSync(join(tmpdir(), "cw-queries-"));
  const qfile = join(qdir, "queries.json");
  writeFileSync(qfile, JSON.stringify(toApi(queries)));
  const results = [];
  const messages = [];
  try {
    let token;
    const seen = new Set();
    do {
      const args = ["cloudwatch", "get-metric-data", "--metric-data-queries", `file://${qfile}`,
        "--start-time", new Date(start).toISOString(), "--end-time", new Date(end).toISOString(), "--scan-by", "TimestampAscending",
        // 페이지는 직접 넘긴다(CLI 자동 페이지 처리와 섞이지 않게).
        "--no-paginate", ...region];
      if (token) args.push("--next-token", token);
      const r = await aws(args);
      results.push(...(r.MetricDataResults ?? []));
      messages.push(...(r.Messages ?? []));
      token = r.NextToken;
      // 같은 토큰이 다시 오거나 페이지가 비정상적으로 많으면 끝나지 않으므로 실패로 끝낸다.
      if (token && (seen.has(token) || seen.size >= 100)) throw new Error("get-metric-data 페이지가 끝나지 않는다(같은 NextToken 반복 또는 100쪽 초과)");
      if (token) seen.add(token);
    } while (token);
  } finally {
    rmSync(qdir, { recursive: true, force: true });
  }

  const { perQuery, problems } = summarize(queries, results, messages);
  writeFileSync(`${dir}/metrics.json`, JSON.stringify(results, null, 2));
  writeFileSync(`${dir}/_meta.json`, JSON.stringify({
    start: new Date(start).toISOString(), end: new Date(end).toISOString(), startArg: a.start, endArg: a.end,
    period: Number(a.period), creditPeriod: CREDIT_PERIOD, region: a.region, messages,
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
