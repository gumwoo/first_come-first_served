import { test, expect, Page } from "@playwright/test";
import { seedAdmittedUser, seedOrder, firstSelectable } from "../helpers/seed";

/**
 * TS-052 회귀: SSE 이벤트가 웹 서버(Next, /api rewrite)를 거쳐 브라우저에 실시간으로 도착하는가.
 *
 * Next 서버는 응답을 gzip으로 압축한다(compress 기본값). SSE 프레임은 작고 드문드문 나가므로 압축기
 * 버퍼에 머문 채 브라우저에 닿지 않았다. 화면은 마운트·폴링·재연결 재조회로 결국 따라잡기 때문에
 * "완료 화면이 뜬다"로는 이 결함이 안 보인다. 그래서 화면이 아니라 이벤트 수신 자체를 단언한다.
 *
 * 앱 훅(useOrder·useSeats)과 별개로 테스트가 직접 EventSource를 연다. 앱 훅은 폴링 결과로
 * 화면을 옮기며 연결을 닫을 수 있어, 이벤트가 오기 전에 닫히면 전송 경로와 무관하게 실패한다.
 * 이 테스트가 보는 것은 전송 경로(브라우저 → Next → api)다.
 *
 * CI는 `next start`(압축 켜짐)로 웹을 띄우므로 운영과 같은 조건이다. 헤더(no-transform)를 빼면 실패한다.
 */

type Rec = { t: number; type: string; data?: string };

/** 같은 출처(Next)로 EventSource를 열고, open과 지정한 이벤트를 window.__sse에 기록한다. */
async function openEventSource(page: Page, url: string, events: string[]) {
  await page.evaluate(
    ([u, evs]) => {
      const w = window as unknown as { __sse: Rec[]; __es: EventSource };
      w.__sse = [];
      const es = new EventSource(u as string);
      es.addEventListener("open", () => w.__sse.push({ t: Date.now(), type: "open" }));
      for (const ev of evs as string[]) {
        es.addEventListener(ev, (e) => w.__sse.push({ t: Date.now(), type: ev, data: (e as MessageEvent).data }));
      }
      w.__es = es;
    },
    [url, events] as const
  );
}
const sseLog = (page: Page) => page.evaluate(() => (window as unknown as { __sse: Rec[] }).__sse);
const received = async (page: Page, type: string) => (await sseLog(page)).some((e) => e.type === type);

/** 압축이 SSE를 비켜 가는지 응답 헤더로 직접 본다(브라우저는 gzip을 투명하게 풀어 헤더 차이를 숨긴다). */
async function expectNotCompressed(url: string) {
  const ac = new AbortController();
  const res = await fetch(url, {
    headers: { Accept: "text/event-stream", "Accept-Encoding": "gzip, deflate, br" },
    signal: ac.signal,
  });
  try {
    expect(res.status).toBe(200);
    expect(res.headers.get("cache-control") ?? "").toContain("no-transform");
    expect(res.headers.get("content-encoding")).toBeNull();
  } finally {
    ac.abort();
  }
}

test("주문 SSE: 결제 직후 order.paid가 웹 서버를 거쳐 도착한다", async ({ page, baseURL }) => {
  const { orderId, accessToken } = await seedOrder(page);
  const ticket = async () =>
    (
      await (
        await page.request.post(`/api/orders/${orderId}/sse-ticket`, {
          headers: { Authorization: `Bearer ${accessToken}` },
        })
      ).json()
    ).data.ticket as string;

  await expectNotCompressed(`${baseURL}/api/sse/orders/${orderId}?ticket=${encodeURIComponent(await ticket())}`);

  await page.goto("/");
  await openEventSource(page, `/api/sse/orders/${orderId}?ticket=${encodeURIComponent(await ticket())}`, ["order.paid"]);
  // open은 응답 헤더만 와도 불린다(수정 전에도 통과했다) — 결제를 연결 뒤에 보내기 위한 대기일 뿐 판별 단언이 아니다.
  await expect.poll(() => received(page, "open"), { timeout: 10_000 }).toBe(true);

  const pay = await page.request.post(`/api/orders/${orderId}/payments`, {
    headers: { Authorization: `Bearer ${accessToken}` },
    data: { method: "card", idempotencyKey: `e2e-sse-web-${orderId}` },
  });
  expect((await pay.json()).data.orderStatus).toBe("PAID");

  await expect.poll(() => received(page, "order.paid"), { timeout: 10_000 }).toBe(true);
});

test("좌석 SSE: 선점 직후 seat.held가 웹 서버를 거쳐 도착한다", async ({ page, baseURL }) => {
  const { eventId, queueToken, accessToken } = await seedAdmittedUser(page);
  const map = (await (await page.request.get(`/api/events/${eventId}/seats`)).json()).data;
  const seat = firstSelectable(map);

  await expectNotCompressed(`${baseURL}/api/sse/events/${eventId}/seats`);

  await page.goto("/");
  await openEventSource(page, `/api/sse/events/${eventId}/seats`, ["seat.held"]);
  await expect.poll(() => received(page, "open"), { timeout: 10_000 }).toBe(true);

  const hold = await page.request.post(`/api/events/${eventId}/seats/hold`, {
    headers: { Authorization: `Bearer ${accessToken}` },
    data: { seatIds: [seat.id], queueToken },
  });
  expect(hold.ok()).toBeTruthy();

  await expect.poll(() => received(page, "seat.held"), { timeout: 10_000 }).toBe(true);
  const ev = (await sseLog(page)).find((e) => e.type === "seat.held")!;
  expect(ev.data).toContain(String(seat.id));
});
