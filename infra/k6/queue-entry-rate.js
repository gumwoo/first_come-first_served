// 대기열 진입 시험(loadtest-100k-plan §2): 정해진 도착률로 대기열에 진입시킨다(open model).
//
// spike-queue.js와 무엇이 다른가: spike-queue는 per-vu-iterations라 VU를 한꺼번에 띄우는 closed model이다.
// 도착률을 정할 수 없고 dropped_iterations도 나오지 않는다(계획서 §2.4 ①). 여기서는 arrival-rate executor로
// "초당 몇 명이 들어오는가"를 통제 변수로 둔다.
//
// 1인 1토큰: arrival-rate executor는 VU를 iteration 사이에 재사용한다. VU 번호로 사용자를 고르면 한 VU가
// 같은 사용자로 여러 번 진입해 "같은 토큰 재반환"이 섞인다. 그래서 iteration 번호로 사용자를 고른다.
//
// 분포(DIST):
//   constant     — constant-arrival-rate. 목표 USERS_N/ENTRY_SECONDS arrivals/s를 일정하게. 비교 기준 시험.
//   frontloaded  — ramping-arrival-rate. 평균의 1.8배에서 0.2배로 ENTRY_SECONDS 동안 선형 감소.
//                  면적이 평균 × 시간이라 총 진입 수는 constant와 같다. **프로젝트가 정의한 stress workload이며
//                  실제 티켓 오픈 트래픽 데이터에서 나온 분포가 아니다**(계획서 §2).
// 설정한 rate는 목표이지 측정값이 아니다. 실제 발생한 도착은 entry_arrivals 시계열로 따로 센다(§2.3).
//
// SSE 발생기와 잇기(계획서 §2.4 ②): EMIT_TOKENS=1이면 발급된 대기 토큰을 "QTOKEN <token>" 한 줄로 로그에 낸다.
//   k6 run --log-format=raw ... 2>&1 | node infra/loadgen/sse-hold.mjs ...
// 진입 iteration 안에서 SSE를 붙들지 않는다. 붙들면 VU가 대기 내내 묶여 발생기가 먼저 무너진다.
//
// 분산 실행: 발생기 G대가 나눠 걸 때 각자 USERS_N = 전체/G, USER_OFFSET = 자기 몫의 시작 인덱스.
//
// 실행. 새 옵션 이름에는 K6_ 접두사를 쓰지 않는다(README "⚠️" 참고). K6_BASE_URL은 k6 옵션이 아니라
// 이 저장소 스크립트들이 함께 쓰는 기존 이름이라 그대로 둔다.
// USERS는 절대경로로 준다. k6의 open()은 상대경로를 이 스크립트 디렉터리 기준으로 읽는다.
//   k6 run -e K6_BASE_URL=... -e EVENT_ID=1733 -e USERS=/abs/path/tokens.json -e USERS_N=10000 \
//          -e DIST=constant --out json=entry.json infra/k6/queue-entry-rate.js
import http from "k6/http";
import { check } from "k6";
import exec from "k6/execution";
import { SharedArray } from "k6/data";
import { Counter } from "k6/metrics";

const BASE = __ENV.K6_BASE_URL || "http://localhost:8080";
const EVENT_ID = __ENV.EVENT_ID;
const DIST = __ENV.DIST || "constant";
const ENTRY_SECONDS = Number(__ENV.ENTRY_SECONDS || 10);
const USER_OFFSET = Number(__ENV.USER_OFFSET || 0);
const EMIT_TOKENS = __ENV.EMIT_TOKENS === "1";

// open()은 init 컨텍스트에서 VU마다 실행된다. SharedArray로 한 번만 파싱해 VU가 공유한다(spike-queue.js 참고).
if (!__ENV.USERS) throw new Error("USERS(사용자 토큰 파일의 절대경로)가 필요하다");
const USERS = new SharedArray("users", () => JSON.parse(open(__ENV.USERS)));
const USERS_N = Number(__ENV.USERS_N || USERS.length - USER_OFFSET);

if (!EVENT_ID) throw new Error("EVENT_ID가 필요하다");
if (!(USERS_N > 0)) throw new Error(`USERS_N이 0 이하다: ${USERS_N}`);
if (USER_OFFSET + USERS_N > USERS.length) {
  throw new Error(`사용자 토큰 부족: offset ${USER_OFFSET} + ${USERS_N} > ${USERS.length}`);
}

const AVG_RATE = USERS_N / ENTRY_SECONDS;
// 응답이 느려질수록 같은 도착률에 더 많은 VU가 필요하다. 부족하면 dropped_iterations로 드러나고
// 그 run은 무효다(§3.1). 상한은 발생기 메모리로 묶이므로 실행 쪽에서 조정한다.
const PRE_VUS = Number(__ENV.PRE_VUS || Math.max(50, Math.ceil(AVG_RATE)));
const MAX_VUS = Number(__ENV.MAX_VUS || Math.max(200, Math.ceil(AVG_RATE * 4)));

function scenario() {
  const common = { timeUnit: "1s", preAllocatedVUs: PRE_VUS, maxVUs: MAX_VUS, gracefulStop: "30s" };
  if (DIST === "constant") {
    return { executor: "constant-arrival-rate", rate: Math.round(AVG_RATE), duration: `${ENTRY_SECONDS}s`, ...common };
  }
  if (DIST === "frontloaded") {
    return {
      executor: "ramping-arrival-rate",
      startRate: Math.round(AVG_RATE * 1.8),
      stages: [{ duration: `${ENTRY_SECONDS}s`, target: Math.round(AVG_RATE * 0.2) }],
      ...common,
    };
  }
  throw new Error(`알 수 없는 DIST: ${DIST} (constant | frontloaded)`);
}

export const options = {
  scenarios: { entry: scenario() },
  thresholds: {}, // 판정은 계획서 §3이 한다. 여기서 실패 표시를 내면 판정과 섞인다.
  summaryTrendStats: ["avg", "p(50)", "p(95)", "p(99)", "max"],
};

// 진입 요청을 보내기 직전에 하나씩 쌓인다(= 그 iteration이 시작된 시각). 1초 단위 peak arrivals/s는 이 시계열
// (--out json)에서만 계산한다(§2.3). 사용자가 없어 요청을 못 보낸 iteration은 넣지 않고 entry_no_user로 센다.
const arrivals = new Counter("entry_arrivals");
// 처리된 진입 = 토큰 발급 200(§2.3). HTTP 요청 수(http_reqs)와 섞지 않는다.
const processed = new Counter("entry_processed");
// 준비한 사용자보다 iteration이 많아진 경우(분포 반올림). 0이 아니면 그만큼 도착이 덜 나갔다.
const noUser = new Counter("entry_no_user");

export default function () {
  const i = exec.scenario.iterationInTest;
  const u = i < USERS_N ? USERS[USER_OFFSET + i] : undefined;
  if (!u) {
    noUser.add(1);
    return;
  }
  arrivals.add(1, { dist: DIST });
  const res = http.post(`${BASE}/events/${EVENT_ID}/queue/token`, null, {
    headers: { Authorization: `Bearer ${u.t}` },
    tags: { name: "queue_entry", dist: DIST },
  });

  let status = null;
  let token = null;
  try {
    status = res.json("data.status");
    token = res.json("data.token");
  } catch (e) {
    /* 파싱 실패는 아래 check에서 잡힌다 */
  }
  const ok = check(res, {
    "진입 200": (r) => r.status === 200,
    // 정원 초과분은 에러가 아니라 WAITING이어야 한다.
    "WAITING 또는 ADMITTED": () => status === "WAITING" || status === "ADMITTED",
  });
  if (ok) processed.add(1, { dist: DIST });
  if (EMIT_TOKENS && token) console.log(`QTOKEN ${token}`);
}
