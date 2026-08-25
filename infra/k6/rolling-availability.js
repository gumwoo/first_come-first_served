// 롤링 배포 중 가용성 측정 — IMP-015 §8(미검증 조건 재현)용.
//
// read-load-rate.js와 목적이 다르다. 저쪽은 "이 시스템이 이 속도를 감당하는가"(포화점 탐색)를
// 묻고, 이쪽은 **"배포 중에 요청이 떨어지는가"**를 묻는다. 그래서 부하는 일부러 낮게 두고
// (기본 25 rps — IMP-015 §3과 같은 값) 대신 **실패한 요청 하나하나의 시각을 남긴다.**
// 3,600건 중 1건짜리 사건을 찾는 측정이라 집계값(p95·에러율)만으로는 아무것도 못 본다.
//
// 왜 공인 도메인을 때리는가: k6-job.yaml은 Service로 직접 쏘지만, 여기서는 **ALB를 반드시
// 경로에 넣어야 한다.** 검증 대상인 deregistration_delay·타깃 등록 해제가 ALB의 동작이기
// 때문이다. Service로 쏘면 측정하려는 그 구간을 건너뛴다.
// 동시에 Job으로 클러스터 안에서 돌려 집 회선 변동은 배제한다(IMP-015 §6의 한계).
//
// 실행은 scripts/rolling-deploy-test.sh가 한다. 직접 돌릴 일은 거의 없다:
//   k6 run -e BASE_URL=https://flow-ticket.com/api -e RATE=25 -e RUN_FOR=4m \
//          infra/k6/rolling-availability.js
//
// ⚠️ 옵션 이름에 K6_ 접두사를 쓰지 않는다(read-load-rate.js와 같은 이유 — K6_DURATION은
// k6 자신의 환경변수 옵션이라 scenarios를 통째로 덮어쓴다. 2026-08-11에 측정이 그렇게 무효화됐다).
import http from "k6/http";
import { Counter } from "k6/metrics";

const BASE = __ENV.BASE_URL || "https://flow-ticket.com/api";
const RATE = __ENV.RATE ? parseInt(__ENV.RATE, 10) : 25;
const RUN_FOR = __ENV.RUN_FOR || "4m";
const EVENT_ID = __ENV.EVENT_ID || "";
// 부하 모델. 기본은 open(도착률 고정). closed는 IMP-015 §3의 "병렬 6워커"를 재현하기 위한 것으로,
// **두 모델이 같은 장애를 다르게 보는지**를 확인하는 대조군 전용이다.
const MODEL = __ENV.MODEL || "open";
const VUS = __ENV.VUS ? parseInt(__ENV.VUS, 10) : 6;

const non2xx = new Counter("non2xx_total");

// ⚠️ 두 모델은 같은 장애를 다르게 본다. closed는 응답이 늦어지면 그 VU가 묶여 **부하가 스스로
// 줄어들고**(read-load-rate.js 헤더 참조), 연결이 10초 매달리는 구간에서는 VU 수만큼만 실패할 수
// 있다. open은 응답이 늦어도 초당 도착 수를 유지하므로 그 창을 정면으로 때린다.
// 그래서 closed의 "0건"은 무중단의 증거가 아니라 **측정기가 못 본 것**일 수 있다.
const scenario =
  MODEL === "closed"
    ? { executor: "constant-vus", vus: VUS, duration: RUN_FOR, gracefulStop: "30s" }
    : {
        executor: "constant-arrival-rate",
        rate: RATE,
        timeUnit: "1s",
        duration: RUN_FOR,
        preAllocatedVUs: Math.max(20, RATE),
        maxVUs: Math.max(100, RATE * 8),
        // 롤링 중 한 요청이 오래 걸려도 그 자리에서 끊지 않는다. 여기서 잘라내면
        // 우리가 찾는 사건(느려짐 → 실패)을 측정기가 먼저 지워버린다.
        gracefulStop: "30s",
      };

export const options = {
  scenarios: { rolling: scenario },
  // 임계로 실행을 멈추지 않는다. 판정은 오케스트레이터가 5xx 개수로 한다.
  thresholds: {},
  summaryTrendStats: ["avg", "p(95)", "p(99)", "max"],
  // 리다이렉트를 따라가면 3xx가 200으로 보인다. 배포 중 라우팅이 흔들리는 것을
  // 놓치지 않기 위해 그대로 둔다.
  maxRedirects: 0,
};

export function setup() {
  if (EVENT_ID) return { eventId: EVENT_ID };
  // 좌석 조회는 DB를 실제로 탄다. IMP-015 §3이 빈 목록 대신 이걸 고른 이유와 같다 —
  // DB를 거의 안 타는 요청은 무중단의 증거로 약하다.
  const res = http.get(`${BASE}/events?status=ON_SALE&size=20`);
  const items = res.json("data.items") || [];
  for (const e of items) {
    const map = http.get(`${BASE}/events/${e.id}/seats`);
    if ((map.json("data.seats") || []).length > 0) return { eventId: String(e.id) };
  }
  throw new Error("좌석이 있는 ON_SALE 이벤트가 없다 — scripts/seed-demo-data.sh 먼저");
}

export default function (data) {
  const res = http.get(`${BASE}/events/${data.eventId}/seats`, { tags: { ep: "seats" } });
  if (res.status !== 200) {
    non2xx.add(1, { status: String(res.status) });
    // 이 한 줄이 이 스크립트의 존재 이유다. 오케스트레이터가 롤링 타임라인과 겹쳐 읽는다.
    // status=0은 연결 자체가 끊긴 것(TCP/TLS) — ALB 502와 구분해야 한다.
    console.error(
      `NON2XX ts=${new Date().toISOString()} status=${res.status} ` +
        `dur=${Math.round(res.timings.duration)}ms err=${res.error_code || 0}`
    );
  }
}

export function handleSummary(data) {
  const m = data.metrics;
  const total = m.http_reqs ? m.http_reqs.values.count : 0;
  const bad = m.non2xx_total ? m.non2xx_total.values.count : 0;
  const dur = m.http_req_duration ? m.http_req_duration.values : {};
  // 오케스트레이터가 파싱하는 한 줄. 사람이 읽는 요약은 stdout에 그대로 둔다.
  const line =
    "SUMMARY_JSON " +
    JSON.stringify({
      total,
      non2xx: bad,
      p95: Math.round(dur["p(95)"] || 0),
      p99: Math.round(dur["p(99)"] || 0),
      max: Math.round(dur.max || 0),
      model: MODEL,
      rate: MODEL === "closed" ? null : RATE,
      vus: MODEL === "closed" ? VUS : null,
      duration: RUN_FOR,
    });
  return { stdout: `\n${line}\n` };
}
