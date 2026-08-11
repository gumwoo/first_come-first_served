// 시나리오 ③ 스파이크 — 오픈 순간 대량 동시 진입.
//
// 티켓 오픈은 **정의상 스파이크**다. 평상시 0에서 순간 수천으로 튀는 것이 이 시스템이 존재하는
// 이유이고, 대기열은 그 순간을 위해 만든 장치다.
//
// 증명할 명제 셋:
//   ① over-admit 0     — 도착량과 무관하게 동시 ADMITTED가 정원(100)을 넘지 않는다
//   ② 순서 보존         — 진입 순번(rank) 순서대로 승격된다. 못 지키면 "선착순"이 거짓이 된다
//   ③ 나머지는 대기      — 입장 못 한 사람이 에러가 아니라 WAITING 상태를 받는다
//
// ③이 특히 중요하다. 9,900명이 500을 받으면 "막았다"가 아니라 "터졌다"이다.
//
// ⚠️ executor는 per-vu-iterations 여야 한다. 1인 1토큰이라 VU와 사용자가 1:1로 고정되는데,
// shared-iterations는 전체 iteration을 VU들이 나눠 갖기 때문에 그 전제가 깨진다
// (hold-contention.js에서 같은 실수를 했다).
//
// 실행:
//   k6 run -e K6_BASE_URL=... -e TOKENS=/path/tokens3k.json -e EVENT_ID=1733 \
//          --summary-export=... infra/k6/spike-queue.js
import http from "k6/http";
import { check } from "k6";
import exec from "k6/execution";
import { SharedArray } from "k6/data";

const BASE = __ENV.K6_BASE_URL || "http://localhost:8080";
const EVENT_ID = __ENV.EVENT_ID;
// ⚠️ open()은 init 컨텍스트에서 **VU마다** 실행된다. 3,000 VU가 각각 수 MB JSON을 파싱하면
// k6 프로세스가 메모리로 죽는다("fatal error: runtime: cannot allocate memory" — 2026-08-11 실측).
// SharedArray는 파싱 결과를 **VU 전체가 공유**해 그 문제를 없앤다.
const USERS = new SharedArray("users", () => JSON.parse(open(__ENV.TOKENS || "./tokens.json")));

export const options = {
  scenarios: {
    spike: {
      executor: "per-vu-iterations",
      vus: USERS.length,
      iterations: 1, // VU(=사용자)당 정확히 1회 진입
      maxDuration: "120s",
    },
  },
  thresholds: {}, // 판정 대상은 속도가 아니라 정합성이다
};

export default function () {
  const u = USERS[exec.vu.idInTest - 1];
  if (!u) return;
  const res = http.post(`${BASE}/events/${EVENT_ID}/queue/token`, null, {
    headers: { Authorization: `Bearer ${u.t}` },
  });

  let status = null;
  let rank = null;
  let qtoken = null;
  try {
    status = res.json("data.status");
    rank = res.json("data.rank");
    qtoken = res.json("data.token");
  } catch (e) {
    /* 파싱 실패는 아래 check에서 잡힌다 */
  }

  // 명제 ②(순서 보존) 검증용. 진입 시점의 rank를 사용자별로 남긴다 — k6는 VU별 데이터를
  // 요약에 담지 못하므로 stdout으로 흘리고 실행 측에서 수집한다.
  // (VU 번호 = 사용자 인덱스. per-vu-iterations라 1:1로 고정된다)
  if (__ENV.RANKLOG === "1") {
    // 승격 순서를 사후 대조하려면 발급된 queueToken이 있어야 한다(GET /queue/status?token=).
    console.log(`RANKLOG ${exec.vu.idInTest} ${rank} ${status} ${res.status} ${qtoken}`);
  }

  check(res, {
    // 진입 자체는 전원 성공해야 한다 — 정원을 넘었다고 진입이 실패하면 안 된다.
    "진입 200": (r) => r.status === 200,
    // 정원 초과분은 에러가 아니라 WAITING 이어야 한다(명제 ③).
    "WAITING 또는 ADMITTED": () => status === "WAITING" || status === "ADMITTED",
    "5xx 없음": (r) => r.status < 500,
    "rank 부여됨": () => typeof rank === "number" && rank >= 0,
  });
}
