// 평시 조회 혼합 부하(docs/testing/traffic-profile.md §2): 화면 가중 메인 25%·상세 40%·검색 20%·내 예매 15%(IMP-048 가정)를 요청 단위로 펼친다.
//   k6 run -e BASE=http://api:8080 -e RATE=33 -e DUR=120s -e IDS_FILE=/w/event-ids.json -e TOKENS_FILE=/w/tokens.json infra/k6/browse-mix.js
//   event-ids.json: 공연 id 배열(JSON). tokens.json: infra/loadgen/mint-tokens.mjs 출력([{ n, t }]).
import http from "k6/http";
import { check } from "k6";

const BASE = __ENV.BASE || "http://api:8080";
const RATE = Number(__ENV.RATE || 100);
const DUR = __ENV.DUR || "120s";
const IDS = JSON.parse(open(__ENV.IDS_FILE || "/w/event-ids.json"));
const TOKENS = JSON.parse(open(__ENV.TOKENS_FILE || "/w/tokens.json")).map((x) => x.t);
const WORDS = ["공연", "콘서트", "뮤지컬", "TRIAGE"];

export const options = {
  scenarios: { browse: { executor: "constant-arrival-rate", rate: RATE, timeUnit: "1s", duration: DUR, preAllocatedVUs: 60, maxVUs: 300 } },
  summaryTrendStats: ["avg", "p(50)", "p(95)", "p(99)", "max"],
};

// 요청 단위 가중(화면 가중 × 화면당 호출): 메인 4종 각 0.25, 상세 0.40, 검색 2종 각 0.20, 내 예매 0.15 → 합 1.95
const W = [["popular", 0.25], ["list", 0.25], ["upcoming", 0.25], ["realtime", 0.25], ["detail", 0.40], ["keywords", 0.20], ["search", 0.20], ["myorders", 0.15]];
const TOTAL = W.reduce((a, [, w]) => a + w, 0);
function pick() {
  let r = Math.random() * TOTAL;
  for (const [k, w] of W) { r -= w; if (r < 0) return k; }
  return "detail";
}

export default function () {
  const k = pick();
  const id = IDS[Math.floor(Math.random() * IDS.length)];
  const p = { tags: { ep: k } };
  let res;
  if (k === "popular") res = http.get(BASE + "/events/popular", p);
  else if (k === "list") res = http.get(BASE + "/events?size=24", p);
  else if (k === "upcoming") res = http.get(BASE + "/events?status=SCHEDULED&size=8", p);
  else if (k === "realtime") res = http.get(BASE + "/events/ranking/realtime", p);
  else if (k === "detail") res = http.get(BASE + "/events/" + id, p);
  else if (k === "keywords") res = http.get(BASE + "/search/popular-keywords", p);
  else if (k === "search") res = http.get(BASE + "/search?q=" + encodeURIComponent(WORDS[Math.floor(Math.random() * WORDS.length)]) + "&size=20", p);
  else res = http.get(BASE + "/me/orders", Object.assign({}, p, { headers: { authorization: "Bearer " + TOKENS[Math.floor(Math.random() * TOKENS.length)] } }));
  check(res, { ok: (r) => r.status === 200 });
}
