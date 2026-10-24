// Redis 장애 중 api 인스턴스별 readiness와 엔드포인트 응답을 잰다(ADR-030 근거 측정).
//   node scripts/resilience/redis-outage-probe.mjs --targets a=http://ready-a:8080,b=http://ready-b:8080 --tokens tokens.json --event 173 --seat 17693 --label stop [--rounds 3]
// tokens.json: [{ n, t }] (infra/loadgen/mint-tokens.mjs). 사용자 토큰 하나만 쓴다.
// 결과: 한 줄에 요청 하나(JSON) — label, round, target, endpoint, status, ms, code. 요청마다 75초 상한(Lettuce 기본 명령 타임아웃 60초보다 길게).
import fs from "node:fs";

const arg = (k, d = null) => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : d; };
// 대상은 포트(localhost) 또는 주소. Docker Desktop 포트 전달은 동시 연결이 8개를 넘으면 30초 뒤 끊겨(ECONNRESET, 2026-10-24 시험)
// 실험실 네트워크 안의 컨테이너에서 이름으로 부른다(예: a=http://ready-a:8080).
const targets = arg("--targets").split(",").map((kv) => kv.split("=")).map(([name, v]) => ({ name, base: /^\d+$/.test(v) ? `http://localhost:${v}` : v }));
const token = JSON.parse(fs.readFileSync(arg("--tokens"), "utf8"))[0].t;
const event = arg("--event"), seat = Number(arg("--seat")), label = arg("--label"), rounds = Number(arg("--rounds", "3"));
const auth = { authorization: `Bearer ${token}`, "content-type": "application/json" };

const ENDPOINTS = [
  { name: "readyz", method: "GET", path: "/readyz" },
  { name: "events_list", method: "GET", path: "/events?size=5" },
  { name: "event_detail", method: "GET", path: `/events/${event}` },
  { name: "event_seats", method: "GET", path: `/events/${event}/seats` },
  { name: "my_orders", method: "GET", path: "/me/orders", headers: auth },
  { name: "queue_token", method: "POST", path: `/events/${event}/queue/token`, headers: auth },
  { name: "seat_hold", method: "POST", path: `/events/${event}/seats/hold`, headers: auth, body: { seatIds: [seat], queueToken: "probe-not-admitted" } },
];

async function call(t, e) {
  const t0 = Date.now();
  try {
    const res = await fetch(t.base + e.path, {
      method: e.method, headers: e.headers, body: e.body ? JSON.stringify(e.body) : undefined, signal: AbortSignal.timeout(75000),
    });
    const text = await res.text();
    let code = null;
    try { code = JSON.parse(text)?.code ?? JSON.parse(text)?.error?.code ?? JSON.parse(text)?.status ?? null; } catch { /* 본문이 JSON이 아님 */ }
    return { status: res.status, ms: Date.now() - t0, code };
  } catch (err) {
    return { status: null, ms: Date.now() - t0, code: err.cause?.code ?? err.name };
  }
}

for (let round = 1; round <= rounds; round++) {
  // 인스턴스·엔드포인트를 동시에 부른다(장애 중 한 요청이 60초를 물어도 나머지를 막지 않게)
  const rows = await Promise.all(targets.flatMap((t) => ENDPOINTS.map(async (e) => ({ label, round, target: t.name, endpoint: e.name, ...(await call(t, e)) }))));
  for (const r of rows) console.log(JSON.stringify(r));
}
