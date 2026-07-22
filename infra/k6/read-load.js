// S08 시나리오 ① 조회 API 부하 — 사용자가 가장 자주 치는 읽기 경로에 부하를 걸어
// RPS가 더 이상 선형 증가하지 않고 p95/p99가 급등하는 "무릎(포화점)"을 찾는다.
// 대상: 목록(GET /events) · 좌석맵(GET /events/{id}/seats) · 상세(GET /events/{id}).
//
// 실행(VU를 바꿔가며 각각):
//   k6 run -e K6_VUS=300  --summary-export=benchmarks/read-load-vu300.json infra/k6/read-load.js
//   k6 run -e K6_VUS=500  --summary-export=benchmarks/read-load-vu500.json infra/k6/read-load.js
//   k6 run -e K6_VUS=750  --summary-export=benchmarks/read-load-vu750.json infra/k6/read-load.js
//   k6 run -e K6_VUS=1000 --summary-export=benchmarks/read-load-vu1000.json infra/k6/read-load.js
import http from "k6/http";
import { check } from "k6";
import { BASE, THRESHOLDS, discoverEvent } from "./lib.js";

const VUS = __ENV.K6_VUS ? parseInt(__ENV.K6_VUS, 10) : 300;
const DURATION = __ENV.K6_DURATION || "30s";

export const options = {
  scenarios: {
    read: { executor: "constant-vus", vus: VUS, duration: DURATION },
  },
  thresholds: THRESHOLDS,
  summaryTrendStats: ["avg", "p(95)", "p(99)", "max"],
};

export function setup() {
  return { eventId: discoverEvent() };
}

export default function (data) {
  const id = data.eventId;
  // 실제 사용 비중에 가깝게: 좌석맵 50% · 목록 30% · 상세 20%
  const r = Math.random();
  let res;
  if (r < 0.5) {
    res = http.get(`${BASE}/events/${id}/seats`, { tags: { ep: "seats" } });
  } else if (r < 0.8) {
    res = http.get(`${BASE}/events?status=ON_SALE&page=0&size=20`, { tags: { ep: "list" } });
  } else {
    res = http.get(`${BASE}/events/${id}`, { tags: { ep: "detail" } });
  }
  check(res, { "status 200": (x) => x.status === 200 });
}
