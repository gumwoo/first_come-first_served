// 시나리오 2) 매진 경합: 정합성 시험(성능 시험 아님).
//
// 검증할 명제: 잔여 1석에 N명이 동시에 요청해도 정확히 1명만 성공하고 초과판매가 0이다.
// SeatService.hold()는 전부 아니면 전무라(held != seatIds.size() 이면 SOLD_OUT 롤백) 1석으로 두면
// "정확히 1"을 단언할 수 있다. 경합 버그는 간헐적이라 여러 번 반복한다.
//
// 전제:
//   - 시드 계정의 JWT가 미리 발급돼 있어야 한다(측정 구간에 bcrypt 로그인이 섞이면 안 된다)
//   - 각 사용자가 대기열을 통과해 ADMITTED 상태여야 한다(hold는 isAdmitted를 요구한다)
//   - 대기열 정원이 100이므로 동시 ADMITTED는 최대 100명이다
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
    // per-vu-iterations 여야 한다. shared-iterations는 전체 iteration을 VU들이
    // 나눠 갖는 방식이라, 빠른 VU가 여러 번 가져가고 어떤 VU는 한 번도 실행하지 않을 수 있다.
    // 이 스크립트는 exec.vu.idInTest로 사용자를 고정하므로, 그 경우 "서로 다른 100명이
    // 각각 1번씩"이라는 전제가 깨진다(같은 사용자가 두 번 쏘고 다른 사용자는 안 쏜다).
    contention: {
      executor: "per-vu-iterations",
      vus: USERS.length,
      iterations: 1, // VU당 정확히 1회
      maxDuration: "60s",
    },
  },
  // 이 시나리오는 지연 임계를 걸지 않는다. 판정 대상이 성공 건수이지 속도가 아니다.
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
  // 패자는 SOLD_OUT(409) 이어야 한다. 단순히 4xx로 세면 인증 실패(401)·권한(403)·
  // 잘못된 좌석(400)·rate limit(429)까지 "정상 거절"로 집계돼, 패자가 진짜 좌석 경합에서
  // 진 것인지 알 수 없다. 에러 코드까지 확인한다.
  let code = null;
  try {
    code = res.json("error.code");
  } catch (e) {
    code = null;
  }
  check(res, {
    "승자 200": (r) => r.status === 200,
    "패자 409 SOLD_OUT": (r) => r.status === 409 && code === "SOLD_OUT",
    "5xx 없음": (r) => r.status < 500,
    // 위 둘 중 어느 쪽도 아닌 응답을 잡는다(401·403·400·429 등). 0이어야 정상이다.
    "그 외 응답": (r) => !(r.status === 200) && !(r.status === 409 && code === "SOLD_OUT"),
  });
}
