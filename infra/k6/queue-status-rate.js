// 대기 상태 조회 부하(ADR-023 §2 — 대기 상태 전달을 폴링 주 경로로 옮기기 전후의 조회 비용 측정).
//
// queue-status.js와 무엇이 다른가: 그쪽은 Redis 페일오버 때 토큰 하나를 계속 조회해 유실을 본다. 여기서는 대기 중인 토큰 N개를
// 돌아가며 정해진 도착률로 조회해 "조회 1건이 api·Redis에 얼마를 쓰는가"를 잰다(진입과 섞지 않는다 — 대기자는 미리 만들어 둔다).
//
// TOKENS: 대기 토큰 문자열의 JSON 배열(절대경로). 대기열 Redis에서 뽑아 만든다(예: ZRANGE queue:wait:{eventId} 0 -1).
// iteration 번호로 토큰을 고른다 — 같은 토큰이 N건마다 한 번씩 다시 조회된다(실제 폴링도 같은 토큰을 반복 조회한다).
//
// 연결 미리 맺기(WARM_SECONDS, 기본 0 = 끔): 0보다 크면 조회 전 WARM_SECONDS 동안 VU마다 1건으로 keep-alive 연결을 열고,
// 조회는 그 뒤에 시작한다(queue-entry-rate.js와 같은 방식). 그리고 MAX_VUS 기본을 PRE_VUS로 둬 실행 중 VU를 새로 만들지 않는다.
// 왜: 조회 1건 비용을 재는 시험이라 연결 수립을 빼야 한다. 측정 세션 20261005-1440에서 3,000/s·사전 할당 300으로 돌리자 시작 직후
// 새 연결(약 300)이 몰린 초에 반복이 떨어졌다(dropped). 또 실행 중 서버 응답이 "VU 수 ÷ 도착률" 이상 멈추면 VU가 바닥나 k6가 VU와
// 새 연결을 늘리고, 그 새 연결이 지연을 더 키웠다 — 그래서 켜면 실행 중 VU를 늘리지 않는다. 그 순간 지연을 흡수하려면 VU를 넉넉히 둔다
// (3,000/s에 1,000 — 약 330 ms 정지까지, 계산). 순간 지연의 원인은 확인하지 않았다.
//
//   k6 run -e K6_BASE_URL=https://flow-ticket.com/api -e TOKENS=/abs/tokens.json -e RATE=3000 -e DURATION=60s \
//          -e WARM_SECONDS=20 -e PRE_VUS=1000 --out json=status.json infra/k6/queue-status-rate.js
import http from "k6/http";
import { check, sleep } from "k6";
import exec from "k6/execution";
import { SharedArray } from "k6/data";
import { Counter } from "k6/metrics";

const BASE = __ENV.K6_BASE_URL || "http://localhost:8080";
const RATE = Number(__ENV.RATE || 100);
const DURATION = __ENV.DURATION || "60s";
const WARM_SECONDS = Number(__ENV.WARM_SECONDS || 0);
if (!(WARM_SECONDS >= 0)) throw new Error(`WARM_SECONDS가 0 이상의 수가 아니다: ${__ENV.WARM_SECONDS}`);
if (!__ENV.TOKENS) throw new Error("TOKENS(대기 토큰 JSON 배열 파일의 절대경로)가 필요하다");
const TOKENS = new SharedArray("tokens", () => JSON.parse(open(__ENV.TOKENS)));
if (TOKENS.length === 0) throw new Error("TOKENS가 비었다");

const PRE_VUS = Number(__ENV.PRE_VUS || Math.max(50, Math.ceil(RATE / 10)));
// 연결 미리 맺기를 켜면 실행 중 VU를 늘리지 않는다(늘어난 VU는 새 연결을 연다). 모자라면 dropped로 드러난다.
const MAX_VUS = Number(__ENV.MAX_VUS || (WARM_SECONDS > 0 ? PRE_VUS : Math.max(200, RATE)));

const status = {
  executor: "constant-arrival-rate",
  rate: RATE,
  timeUnit: "1s",
  duration: DURATION,
  preAllocatedVUs: PRE_VUS,
  maxVUs: MAX_VUS,
  gracefulStop: "30s",
};

export const options = {
  scenarios:
    WARM_SECONDS > 0
      ? {
          // 모든 VU가 한꺼번에 시작해 각자 [0, 0.8 × WARM_SECONDS) 무작위로 기다린 뒤 1건 — 연결 수립을 창 전체에 펼친다.
          warm: { executor: "per-vu-iterations", vus: PRE_VUS, iterations: 1, maxDuration: `${WARM_SECONDS}s`, gracefulStop: "0s", exec: "warm" },
          status: { ...status, startTime: `${WARM_SECONDS}s` },
        }
      : { status },
  thresholds: {}, // 판정은 사전 등록한 식으로 따로 한다
  summaryTrendStats: ["avg", "p(50)", "p(95)", "p(99)", "max"],
};

// 응답 상태별 수. 대기자를 미리 만들어 두므로 대부분 WAITING이어야 한다 — 다른 값이 많으면 조건이 바뀐 run이다.
const byStatus = new Counter("status_by_queue_status");

// 연결만 여는 요청: 없는 토큰으로 상태를 물어 410(QUEUE_EXPIRED)을 받는다. 같은 호스트라 같은 연결을 쓴다. name 태그로 가른다.
export function warm() {
  sleep(Math.random() * WARM_SECONDS * 0.8);
  http.get(`${BASE}/queue/status?token=warm-${exec.vu.idInTest}`, {
    tags: { name: "warm_connect" },
    responseCallback: http.expectedStatuses(410),
  });
}

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
