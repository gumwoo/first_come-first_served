// 조회 부하 — **도착률 고정(open model)**. read-load.js(VU 고정, closed model)의 짝이다.
//
// 왜 둘 다 필요한가: closed model은 서버가 느려지면 한 VU의 사이클이 길어져 **부하가 스스로
// 줄어든다.** 그래서 포화점을 지나쳐도 그래프가 완만해 보이고 무릎이 흐려진다.
// open model은 응답이 느려져도 초당 도착 수를 유지하므로 "이 시스템이 이 속도를 감당하는가"를
// 직접 묻는다. 감당 못 하면 대기·에러로 드러난다.
//
// 실행:
//   k6 run -e K6_BASE_URL=https://flow-ticket.com/api -e RATE=100 -e RUN_FOR=2m infra/k6/read-load-rate.js
//
// ⚠️ 옵션 이름에 K6_ 접두사를 쓰지 않는다. K6_DURATION 같은 이름은 **k6 자신의 환경변수 옵션**이라
// 여기 scenarios 설정을 통째로 덮어쓴다("env level configuration overrode scenarios configuration
// entirely" 경고와 함께 executor가 사라지고 VU 1개로 돈다). 2026-08-11에 실제로 그렇게 측정이
// 무효화됐다 — 100 rps를 요청했는데 25 rps만 나왔고 vus_max=1이었다.
//
// 관측은 대시보드에서 함께 본다(#211로 k6 지표가 Prometheus에 들어간다):
//   K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write k6 run -o experimental-prometheus-rw ...
import http from "k6/http";
import { check } from "k6";
import { BASE, THRESHOLDS, discoverEvent } from "./lib.js";

const RATE = __ENV.RATE ? parseInt(__ENV.RATE, 10) : 100;
const DURATION = __ENV.RUN_FOR || "2m";

export const options = {
  scenarios: {
    read_rate: {
      executor: "constant-arrival-rate",
      rate: RATE,
      timeUnit: "1s",
      duration: DURATION,
      // preAllocated가 부족하면 k6가 스스로 병목이 되어 도착률을 못 채운다.
      // 응답이 느려질수록 더 많은 VU가 필요하므로 넉넉히 잡고 maxVUs로 상한을 둔다.
      preAllocatedVUs: Math.max(50, RATE),
      maxVUs: Math.max(200, RATE * 8),
    },
  },
  thresholds: THRESHOLDS,
  summaryTrendStats: ["avg", "p(95)", "p(99)", "max"],
};

export function setup() {
  return { eventId: discoverEvent() };
}

export default function (data) {
  const id = data.eventId;
  // read-load.js와 같은 비중이어야 두 모델을 비교할 수 있다.
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
