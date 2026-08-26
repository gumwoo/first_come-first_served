// 대기열 상태 폴링 부하 — **Redis를 실제로 타는 경로**.
//
// 왜 별도 스크립트인가: rolling-availability.js는 `/events/{id}/seats`를 때리는데, 그 경로는
// DB와 캐시를 타지 대기열 Redis 자료구조를 타지 않는다. Redis 페일오버를 재려면
// **Redis가 진실원인 경로**를 때려야 유실 범위까지 볼 수 있다.
//   /queue/status?token=... → QueueService.status() → Redis ZSet 순번 조회
//
// 실패한 요청 하나하나의 시각을 남긴다(rolling-availability.js와 같은 이유) — 페일오버는
// 짧은 창에서 일어나므로 집계값으로는 보이지 않는다.
//
// 실행은 scripts/redis-failover-test.sh가 한다.
import http from "k6/http";
import { Counter } from "k6/metrics";

const BASE = __ENV.BASE_URL || "https://flow-ticket.com/api";
const RATE = __ENV.RATE ? parseInt(__ENV.RATE, 10) : 25;
const RUN_FOR = __ENV.RUN_FOR || "6m";
const TOKEN = __ENV.QUEUE_TOKEN || "";
// 장애 전 상태. 이 값에서 벗어나면 슬롯을 잃은 것으로 본다.
const EXPECT_STATUS = __ENV.EXPECT_STATUS || "";

const non2xx = new Counter("non2xx_total");
// 순번을 별도로 센다. 200이어도 순번이 사라지면 그것이 유실이다.
const posMissing = new Counter("position_missing_total");

export const options = {
  scenarios: {
    queue: {
      executor: "constant-arrival-rate",
      rate: RATE,
      timeUnit: "1s",
      duration: RUN_FOR,
      preAllocatedVUs: Math.max(20, RATE),
      maxVUs: Math.max(100, RATE * 8),
      gracefulStop: "30s",
    },
  },
  thresholds: {},
  summaryTrendStats: ["avg", "p(95)", "p(99)", "max"],
  maxRedirects: 0,
};

export function setup() {
  if (!TOKEN) throw new Error("QUEUE_TOKEN이 비었다 — 오케스트레이터가 발급해 넘겨야 한다");
  return { token: TOKEN };
}

export default function (data) {
  const res = http.get(`${BASE}/queue/status?token=${encodeURIComponent(data.token)}`, {
    tags: { ep: "queue-status" },
  });
  if (res.status !== 200) {
    non2xx.add(1, { status: String(res.status) });
    console.error(
      `NON2XX ts=${new Date().toISOString()} status=${res.status} ` +
        `dur=${Math.round(res.timings.duration)}ms err=${res.error_code || 0}`
    );
    return;
  }
  // ⚠️ 200이어도 대기열 상태가 사라질 수 있다. Redis 복제는 비동기라 최근 쓰기가 유실될 수
  // 있고, 그때 "요청은 성공했는데 대기열에서 사라진" 상태가 된다 — ADR-012가 말한 유실이다.
  //
  // ⚠️ 응답 형식을 측정 전에 확인했다(2026-08-26):
  //   {"rank":0,"total":0,"etaSeconds":0,"status":"ADMITTED"}
  // 필드는 `position`이 아니라 **rank**이고, 입장 완료(ADMITTED)면 rank가 0이 된다.
  // 그래서 rank로는 유실을 판정할 수 없다 — **status**가 신호다.
  let st = null;
  try {
    st = res.json("data.status");
  } catch (e) {
    st = null;
  }
  if (st === null || st === undefined) {
    posMissing.add(1);
    console.error(`NOSTATE ts=${new Date().toISOString()} body=${String(res.body).slice(0, 160)}`);
    return;
  }
  // ⚠️ 상태 변화를 전부 유실로 세면 안 된다. 대기열은 1.5초마다 WAITING → ADMITTED로
  // **정상 승격**한다. 초판이 그걸 유실로 세어 워밍업 45초에 1,188건이 찍혔다(2026-08-26).
  //
  // 유실은 **한 방향뿐이다** — 이미 입장한 토큰이 자리를 잃는 것.
  //   WAITING  → ADMITTED : 정상 승격
  //   ADMITTED → 그 외    : 슬롯 상실 = 유실
  if (EXPECT_STATUS === "ADMITTED" && st !== "ADMITTED") {
    posMissing.add(1);
    console.error(
      `STATECHANGE ts=${new Date().toISOString()} from=ADMITTED to=${st} rank=${res.json("data.rank")}`
    );
  }
}

export function handleSummary(data) {
  const m = data.metrics;
  const line =
    "SUMMARY_JSON " +
    JSON.stringify({
      total: m.http_reqs ? m.http_reqs.values.count : 0,
      non2xx: m.non2xx_total ? m.non2xx_total.values.count : 0,
      posMissing: m.position_missing_total ? m.position_missing_total.values.count : 0,
      p95: Math.round((m.http_req_duration?.values || {})["p(95)"] || 0),
      p99: Math.round((m.http_req_duration?.values || {})["p(99)"] || 0),
      max: Math.round((m.http_req_duration?.values || {}).max || 0),
      rate: RATE,
      duration: RUN_FOR,
    });
  return { stdout: `\n${line}\n` };
}
