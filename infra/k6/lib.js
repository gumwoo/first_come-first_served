// k6 공용 헬퍼 — baseURL, 임계 기준, 대상 이벤트 자동 탐색.
// 응답은 공통 래퍼 { "data": ... } (ApiResponse). 백엔드 직접 호출(:8080, /api 프리픽스 없음).
import http from "k6/http";

export const BASE = __ENV.K6_BASE_URL || "http://localhost:8080";

// performance-rules.md 목표 임계. 초과해도 실행은 계속(무릎 탐색용) — 통과/실패 표시만.
export const THRESHOLDS = {
  http_req_duration: ["p(95)<300"], // 핵심 API p95 ≤ 300ms
  http_req_failed: ["rate<0.005"], // 에러율 ≤ 0.5%
};

/** 판매중이면서 좌석이 있는 이벤트 하나를 찾는다(부하 대상). setup()에서 1회 호출. */
export function discoverEvent() {
  const res = http.get(`${BASE}/events?status=ON_SALE&size=20`);
  const items = (res.json("data.items") || []);
  for (const e of items) {
    const map = http.get(`${BASE}/events/${e.id}/seats`);
    const seats = map.json("data.seats") || [];
    if (seats.length > 0) return e.id;
  }
  if (items.length > 0) return items[0].id;
  throw new Error("ON_SALE 이벤트가 없음 — 백엔드에 이벤트/좌석 시드 필요");
}
