// S08 시나리오 ① 조회 API 부하 — 사용자가 가장 자주 치는 읽기 경로에 부하를 걸어
// RPS가 더 이상 선형 증가하지 않고 p95/p99가 급등하는 "무릎(포화점)"을 찾는다.
// 대상: 목록(GET /events) · 좌석맵(GET /events/{id}/seats) · 상세(GET /events/{id}).
//
// 과거 주의사항(해소됨, 기록용):
//   요청의 20%인 `GET /events/{id}`가 **요청마다 외부 KOPIS API를 호출**하던 시절에는 이 시나리오의
//   VU를 올리는 것이 곧 남의 API를 두들기는 일이었다. 실측(300 VU · 362 req/s)에서 상세 호출이
//   초당 70회 수준으로 나가 KOPIS 이용 제한(IP당 1초 10회)을 약 7배 초과해 400 Request Blocked를
//   2,014건 맞았다.
//
//   상세를 동기화 배치가 미리 받아 DB에 저장하도록 바꾸면서 이 경로에서 외부 호출이 사라졌다.
//   이제 VU를 올려도 외부로 나가지 않는다 — 부하가 우리 시스템 안에서만 돈다.
//   (보장은 EventDetailNoExternalCallTest가 회귀로 지킨다)
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
