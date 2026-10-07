// 대기 상태 조회 부하(ADR-023 §2 — 대기 상태 전달을 폴링 주 경로로 옮기기 전후의 조회 비용 측정).
//
// queue-status.js와 무엇이 다른가: 그쪽은 Redis 페일오버 때 토큰 하나를 계속 조회해 유실을 본다. 여기서는 대기 중인 토큰 N개를
// 돌아가며 정해진 도착률로 조회해 "조회 1건이 api·Redis에 얼마를 쓰는가"를 잰다(진입과 섞지 않는다 — 대기자는 미리 만들어 둔다).
//
// TOKENS: 대기 토큰 문자열의 JSON 배열(절대경로). 대기열 Redis에서 뽑아 만든다(예: ZRANGE queue:wait:{eventId} 0 -1).
// iteration 번호로 토큰을 고른다 — 같은 토큰이 N건마다 한 번씩 다시 조회된다(실제 폴링도 같은 토큰을 반복 조회한다).
//
//   k6 run -e K6_BASE_URL=https://flow-ticket.com/api -e TOKENS=/abs/tokens.json -e RATE=3000 -e DURATION=60s \
//          --out json=status.json infra/k6/queue-status-rate.js
import http from "k6/http";
import { check } from "k6";
import exec from "k6/execution";
import { SharedArray } from "k6/data";
import { Counter } from "k6/metrics";

const BASE = __ENV.K6_BASE_URL || "http://localhost:8080";
const RATE = Number(__ENV.RATE || 100);
const DURATION = __ENV.DURATION || "60s";
if (!__ENV.TOKENS) throw new Error("TOKENS(대기 토큰 JSON 배열 파일의 절대경로)가 필요하다");
const TOKENS = new SharedArray("tokens", () => JSON.parse(open(__ENV.TOKENS)));
if (TOKENS.length === 0) throw new Error("TOKENS가 비었다");

export const options = {
  scenarios: {
    status: {
      executor: "constant-arrival-rate",
      rate: RATE,
      timeUnit: "1s",
      duration: DURATION,
      preAllocatedVUs: Number(__ENV.PRE_VUS || Math.max(50, Math.ceil(RATE / 10))),
      maxVUs: Number(__ENV.MAX_VUS || Math.max(200, RATE)),
      gracefulStop: "30s",
    },
  },
  thresholds: {}, // 판정은 사전 등록한 식으로 따로 한다
  summaryTrendStats: ["avg", "p(50)", "p(95)", "p(99)", "max"],
};

// 응답 상태별 수. 대기자를 미리 만들어 두므로 대부분 WAITING이어야 한다 — 다른 값이 많으면 조건이 바뀐 run이다.
const byStatus = new Counter("status_by_queue_status");

export default function () {
  const token = TOKENS[exec.scenario.iterationInTest % TOKENS.length];
  const res = http.get(`${BASE}/queue/status?token=${encodeURIComponent(token)}`, { tags: { name: "queue_status" } });
  let st = "NONE";
  try {
    st = res.json("data.status") || "NONE";
  } catch (e) {
    /* 410 등 오류 본문 */
  }
  byStatus.add(1, { queue_status: res.status === 200 ? st : `HTTP_${res.status}` });
  check(res, { "상태 조회 200": (r) => r.status === 200 });
}
