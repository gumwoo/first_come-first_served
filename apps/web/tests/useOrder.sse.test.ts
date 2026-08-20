import { renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { FakeEventSource, installFakeEventSource } from "./fakeEventSource";

const getOrder = vi.fn();
const issueSseTicket = vi.fn();
vi.mock("@/features/order/api/order", () => ({
  getOrder: (...a: unknown[]) => getOrder(...a),
  issueSseTicket: (...a: unknown[]) => issueSseTicket(...a),
  orderSseUrl: (id: number, ticket: string) => `/api/sse/orders/${id}?ticket=${ticket}`,
}));

import { useOrder } from "@/features/order/hooks/useOrder";

/**
 * 주문 SSE 구독 티켓의 수명 처리.
 *
 * <p>티켓에 TTL이 생기면서 딜레마가 하나 생겼다 — 만료 후 브라우저가 만료된 URL로 무한
 * 재시도하거나, 반대로 매 `onerror`마다 재발급하면 일시적 단절(ALB idle timeout)마다 티켓
 * 요청이 붙는다. 코드는 `readyState`로 둘을 가른다. **그 분기가 이 파일의 검증 대상이다.**
 */
describe("useOrder SSE 티켓", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    installFakeEventSource();
    getOrder.mockReset();
    issueSseTicket.mockReset();
    getOrder.mockResolvedValue({ orderId: 1, status: "PENDING", amount: 1000, items: [] });
    issueSseTicket.mockResolvedValue({ ticket: "tk-1" });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  async function mount(token: string | null = "at") {
    const view = renderHook(() => useOrder(1, token));
    await vi.advanceTimersByTimeAsync(0);
    return view;
  }

  it("티켓을 받아 그 티켓으로 구독한다", async () => {
    await mount();

    expect(issueSseTicket).toHaveBeenCalledWith(1, "at");
    expect(FakeEventSource.instances).toHaveLength(1);
    expect(FakeEventSource.instances[0].url).toContain("ticket=tk-1");
  });

  /**
   * 토큰 없이 보내면 반드시 401이고, apiClient는 401에서 자동으로 silent refresh를 돌린다.
   * 마운트 직후에는 주문 조회도 같은 이유로 401일 수 있어, 둘이 겹치면 RTR 회전이 겹친다.
   */
  it("토큰이 없으면 티켓을 요청하지 않는다", async () => {
    await mount(null);

    expect(issueSseTicket).not.toHaveBeenCalled();
    expect(FakeEventSource.instances).toHaveLength(0);
  });

  it("일시적 단절(CONNECTING)에는 개입하지 않는다 — 브라우저 자동 재연결에 맡긴다", async () => {
    await mount();
    const es = FakeEventSource.instances[0];

    es.dropTransient();
    await vi.advanceTimersByTimeAsync(10_000);

    expect(issueSseTicket).toHaveBeenCalledTimes(1); // 재발급 없음
    expect(FakeEventSource.instances).toHaveLength(1);
    expect(es.closed).toBe(false);
  });

  it("브라우저가 포기하면(CLOSED) 새 티켓으로 다시 연다 — 티켓 만료 경로", async () => {
    await mount();
    const first = FakeEventSource.instances[0];
    issueSseTicket.mockResolvedValue({ ticket: "tk-2" });

    first.failPermanently();
    await vi.advanceTimersByTimeAsync(2_000);

    expect(issueSseTicket).toHaveBeenCalledTimes(2);
    expect(FakeEventSource.instances).toHaveLength(2);
    expect(FakeEventSource.instances[1].url).toContain("ticket=tk-2");
    expect(first.closed).toBe(true);
  });

  it("티켓 발급이 실패하면 구독을 포기한다 — 화면은 마운트 조회로 이미 채워져 있다", async () => {
    issueSseTicket.mockRejectedValue(new Error("401"));

    await mount();

    expect(FakeEventSource.instances).toHaveLength(0);
    expect(getOrder).toHaveBeenCalled(); // 폴백 조회는 그대로 돈다
  });

  it("언마운트 후에는 재연결하지 않는다", async () => {
    const view = await mount();
    const es = FakeEventSource.instances[0];

    view.unmount();
    es.failPermanently();
    await vi.advanceTimersByTimeAsync(10_000);

    expect(FakeEventSource.instances).toHaveLength(1);
  });
});
