// 시나리오 ② 매진 경합 — **성능 시험이 아니라 정합성 시험이다.**
//
// 증명할 명제: 잔여 1석에 N명이 동시에 달려들어도 **정확히 1명만 성공**하고 초과판매가 0이다.
// 초과판매는 지연이 아니라 사고다 — 5ms에 일어나든 5초에 일어나든 2명이 성공하면 시스템은 실패다.
//
// 왜 1석인가: SeatService.hold()의 holdIfAvailable()은 **전부 아니면 전무**다
// (held != seatIds.size() 이면 SOLD_OUT 롤백). 좌석을 1석으로 두면 "정확히 1"이라는
// 가장 단순하고 반박 불가능한 단언이 된다.
//
// 왜 반복하는가: 경합 버그는 한 번으로 안 나온다. 20회에 1회 깨지는 것이 가장 위험하다.
//
// 전제:
//   - 시드 계정의 JWT가 미리 발급돼 있어야 한다(측정 구간에 bcrypt 로그인이 섞이면 안 된다)
//   - 각 사용자가 대기열을 통과해 ADMITTED 상태여야 한다(hold는 isAdmitted를 요구한다)
//   - 대기열 정원이 100이므로 동시 ADMITTED는 최대 100명이다 — 이것이 이 경로의 구조적 상한이다
//
// 실행:
//   k6 run -e K6_BASE_URL=... -e TOKENS=/path/tokens.json -e EVENT_ID=1733 \
//          -e SEAT_ID=160500 infra/k6/hold-contention.js
import http from "k6/http";
import { check } from "k6";
import exec from "k6/execution";

const BASE = __ENV.K6_BASE_URL || "http://localhost:8080";
const EVENT_ID = __ENV.EVENT_ID;
const SEAT_ID = __ENV.SEAT_ID;
// 사전 발급된 [{n, t}] 목록. t = accessToken, q = queueToken(ADMITTED 상태)
const USERS = JSON.parse(open(__ENV.TOKENS || "./tokens.json"));

export const options = {
  scenarios: {
    // shared-iterations: 각 VU가 정확히 1회씩만 실행한다. 같은 좌석에 동시에 달려드는
    // 상황을 만드는 것이 목적이라 도착률이 아니라 **동시 출발**이 중요하다.
    contention: {
      executor: "shared-iterations",
      vus: USERS.length,
      iterations: USERS.length,
      maxDuration: "60s",
    },
  },
  // 이 시나리오는 지연 임계를 걸지 않는다 — 판정 대상이 성공 건수이지 속도가 아니다.
  thresholds: {},
};

export default function () {
  const u = USERS[exec.vu.idInTest - 1];
  if (!u || !u.q) return;
  const res = http.post(
    `${BASE}/events/${EVENT_ID}/seats/hold`,
    JSON.stringify({ seatIds: [Number(SEAT_ID)], queueToken: u.q }),
    { headers: { "Content-Type": "application/json", Authorization: `Bearer ${u.t}` } }
  );
  // 성공은 200 하나뿐이고, 나머지는 SOLD_OUT 등 **정상 거절**이어야 한다.
  // 500이 섞이면 정합성은 지켰더라도 거절 경로가 깨진 것이므로 별도로 센다.
  check(res, {
    "hold 성공(200)": (r) => r.status === 200,
    "정상 거절(4xx)": (r) => r.status >= 400 && r.status < 500,
    "서버 오류 아님(5xx 0)": (r) => r.status < 500,
  });
}
