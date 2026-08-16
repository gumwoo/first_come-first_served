// 엔드포인트 **단일** 조회 부하 — read-load-rate.js(50:30:20 혼합)의 짝이다.
//
// 왜 필요한가: 혼합 부하로는 "어느 엔드포인트가 비용을 얼마나 먹는지"를 못 가른다.
// 캐시로 얻을 수 있는 개선의 상한은 **캐시 가능한 경로가 전체 비용에서 차지하는 비율**이고
// (Amdahl), 그 분모를 구하려면 엔드포인트를 하나씩 떼어 같은 도착률로 재야 한다.
//
// ⚠️ **요청 비율과 비용 비율은 다르다.** 혼합에서 seats가 요청의 50%라도 비용의 50%라는
// 보장이 없다. 그래서 "요청 50% → 최대 2배"식 계산을 하면 안 된다.
//
// 실행(엔드포인트별로 각각):
//   k6 run -e K6_BASE_URL=https://flow-ticket.com/api -e EP=seats  -e RATE=400 -e RUN_FOR=90s infra/k6/read-load-endpoint.js
//   k6 run ... -e EP=list ...
//   k6 run ... -e EP=detail ...
//
// ⚠️ **포화 아래에서 재야 한다.** 무릎(600~650) 위에서는 큐잉 때문에 지연이 부풀어
// "이 엔드포인트의 고유 비용"이 아니라 "밀린 정도"를 재게 된다. 기본 400 rps는
// benchmarks/phase1-saturation 기준 전 구간 통과 구간(550까지 0% 실패)의 안쪽이다.
//
// ⚠️ 워밍업은 **별도 실행**이다(측정 요약에 섞이지 않게). 프로토콜은
// docs/testing/cache-experiment-plan.md 참고 — read-load-rate.js로 300 rps·45초 선행.
//
// ⚠️ 옵션 이름에 K6_ 접두사를 쓰지 않는다. K6_DURATION 등은 k6 자신의 환경변수 옵션이라
// scenarios 설정을 통째로 덮어쓴다(2026-08-11에 실제로 측정이 무효화됐다).
import http from "k6/http";
import { check } from "k6";
import { BASE, THRESHOLDS, discoverEvent } from "./lib.js";

const EP = __ENV.EP || "seats";
const RATE = __ENV.RATE ? parseInt(__ENV.RATE, 10) : 400;
const DURATION = __ENV.RUN_FOR || "90s";

// 혼합(read-load-rate.js)에서 쓰는 것과 **같은 경로여야** 비교가 성립한다.
const ENDPOINTS = {
  seats: (id) => `${BASE}/events/${id}/seats`,
  list: () => `${BASE}/events?status=ON_SALE&page=0&size=20`,
  detail: (id) => `${BASE}/events/${id}`,
};

// browsing = 좌석맵을 뺀 나머지. 0단계(엔드포인트 단일)가 아니라 **2단계 B 시나리오**용이다.
// 혼합의 원래 비중이 list:detail = 30:20 이므로 그 상대 비율(60:40)을 보존한다 —
// 임의로 50:50을 쓰면 A 시나리오와 비교가 성립하지 않는다.
const BROWSING_LIST_SHARE = 0.6;

const isBrowsing = EP === "browsing";
if (!isBrowsing && !ENDPOINTS[EP]) {
  throw new Error(
    `EP는 ${Object.keys(ENDPOINTS).join("|")}|browsing 중 하나여야 한다 (받은 값: ${EP})`);
}

/** 이번 요청이 때릴 엔드포인트 키. browsing이면 비중대로 고른다. */
function pick() {
  if (!isBrowsing) {
    return EP;
  }
  return Math.random() < BROWSING_LIST_SHARE ? "list" : "detail";
}

export const options = {
  scenarios: {
    // 이름에 엔드포인트를 박아 요약·Prometheus에서 어느 실행인지 바로 보이게 한다.
    [`ep_${EP}`]: {
      executor: "constant-arrival-rate",
      rate: RATE,
      timeUnit: "1s",
      duration: DURATION,
      preAllocatedVUs: Math.max(50, RATE),
      maxVUs: Math.max(200, RATE * 8),
    },
  },
  thresholds: THRESHOLDS,
  summaryTrendStats: ["avg", "p(95)", "p(99)", "max"],
  // 세 실행을 하나의 Prometheus 시계열에서 구분하기 위한 공통 태그.
  tags: { ep: EP, exp: "endpoint-cost" },
};

export function setup() {
  return { eventId: discoverEvent() };
}

export default function (data) {
  // 태그는 **실제로 때린 엔드포인트**로 단다 — browsing 실행에서도 list/detail이 갈려 보여야
  // 나중에 "어느 쪽이 비용을 먹었나"를 되짚을 수 있다.
  const key = pick();
  const res = http.get(ENDPOINTS[key](data.eventId), { tags: { ep: key } });
  check(res, { "status 200": (x) => x.status === 200 });
}
