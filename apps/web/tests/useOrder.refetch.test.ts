import { act, cleanup, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { installFakeEventSource } from "./fakeEventSource";

const getOrder = vi.fn();
const issueSseTicket = vi.fn();
vi.mock("@/features/order/api/order", () => ({
  getOrder: (...a: unknown[]) => getOrder(...a),
  issueSseTicket: (...a: unknown[]) => issueSseTicket(...a),
  orderSseUrl: (id: number, ticket: string) => `/api/sse/orders/${id}?ticket=${ticket}`,
}));

import { useOrder } from "@/features/order/hooks/useOrder";

/**
 * 진행 중 상태의 보완 재조회.
 *
 * SSE가 붙어 있어도 이벤트 하나가 빠질 수 있고(파드의 Redis 구독 연결 순간 단절 등), order.paid는 종료 이벤트라
 * 다시 오지 않는다. 그래서 PENDING 동안은 만료까지 5초마다, PENDING·VBANK_WAITING은 탭 복귀 때 서버 상태로
 * 되맞춘다. 범위를 진행 중 상태로 좁혀 비용을 상한으로 묶는 것이 이 파일의 검증 대상이다.
 */
describe("useOrder 진행 중 상태 재조회", () => {
  let visibility: DocumentVisibilityState = "visible";

  const order = (status: string, expiresInMs = 300_000) => ({
    orderId: 1,
    status,
    amount: 1000,
    items: [],
    expiresAt: new Date(Date.now() + expiresInMs).toISOString(),
  });

  function setVisibility(v: DocumentVisibilityState) {
    visibility = v;
    document.dispatchEvent(new Event("visibilitychange"));
  }

  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-10-14T12:00:00Z"));
    installFakeEventSource();
    visibility = "visible";
    Object.defineProperty(document, "visibilityState", { configurable: true, get: () => visibility });
    getOrder.mockReset();
    issueSseTicket.mockReset();
    issueSseTicket.mockResolvedValue({ ticket: "tk-1" });
  });

  afterEach(() => {
    // vitest globals가 꺼져 있어 testing-library의 자동 정리가 돌지 않는다. 남은 훅의 타이머·리스너가
    // 다음 테스트의 mock을 소비하지 않게 직접 언마운트한다.
    cleanup();
    vi.useRealTimers();
  });

  async function mount() {
    const view = renderHook(() => useOrder(1, "at"));
    await vi.advanceTimersByTimeAsync(0);
    getOrder.mockClear(); // 마운트 조회는 세지 않는다 — 이후의 재조회만 본다
    return view;
  }

  it("PENDING이면 5초마다 재조회한다", async () => {
    getOrder.mockResolvedValue(order("PENDING"));
    await mount();

    await vi.advanceTimersByTimeAsync(5_000);
    expect(getOrder).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(10_000);
    expect(getOrder).toHaveBeenCalledTimes(3);
  });

  it("주문 만료 시각이 지나면 멈춘다", async () => {
    getOrder.mockResolvedValue(order("PENDING", 12_000)); // 12초 뒤 만료
    await mount();

    await vi.advanceTimersByTimeAsync(60_000);
    expect(getOrder).toHaveBeenCalledTimes(2); // 5초·10초. 15초 틱에서 만료를 보고 멈춘다
  });

  it("종료 상태면 재조회하지 않는다", async () => {
    getOrder.mockResolvedValue(order("PAID"));
    await mount();

    await vi.advanceTimersByTimeAsync(30_000);
    setVisibility("hidden");
    setVisibility("visible");
    await vi.advanceTimersByTimeAsync(0);
    expect(getOrder).not.toHaveBeenCalled();
  });

  it("재조회로 PAID를 받으면 그 뒤로는 멈춘다", async () => {
    getOrder.mockResolvedValue(order("PENDING"));
    await mount();
    getOrder.mockResolvedValue(order("PAID"));

    await vi.advanceTimersByTimeAsync(5_000); // 이 재조회가 PAID를 받는다
    await vi.advanceTimersByTimeAsync(30_000);
    expect(getOrder).toHaveBeenCalledTimes(1);
  });

  it("탭이 숨겨지면 멈추고, 돌아오면 1회 재조회한 뒤 다시 돈다", async () => {
    getOrder.mockResolvedValue(order("PENDING"));
    await mount();

    act(() => setVisibility("hidden"));
    await vi.advanceTimersByTimeAsync(30_000);
    expect(getOrder).not.toHaveBeenCalled();

    act(() => setVisibility("visible"));
    await vi.advanceTimersByTimeAsync(0);
    expect(getOrder).toHaveBeenCalledTimes(1); // 복귀 즉시

    await vi.advanceTimersByTimeAsync(5_000);
    expect(getOrder).toHaveBeenCalledTimes(2); // 주기 재개
  });

  it("VBANK_WAITING은 주기 재조회 없이 탭 복귀 때만, focus가 겹쳐도 1회", async () => {
    getOrder.mockResolvedValue(order("VBANK_WAITING"));
    await mount();

    await vi.advanceTimersByTimeAsync(30_000);
    expect(getOrder).not.toHaveBeenCalled();

    act(() => {
      setVisibility("visible");
      window.dispatchEvent(new Event("focus"));
    });
    await vi.advanceTimersByTimeAsync(0);
    expect(getOrder).toHaveBeenCalledTimes(1);
  });

  it("언마운트하면 타이머와 리스너를 정리한다", async () => {
    getOrder.mockResolvedValue(order("PENDING"));
    const view = await mount();

    view.unmount();
    await vi.advanceTimersByTimeAsync(30_000);
    setVisibility("visible");
    expect(getOrder).not.toHaveBeenCalled();
  });

  it("늦게 도착한 PENDING 응답이 나중에 받은 PAID를 덮지 않는다", async () => {
    getOrder.mockResolvedValue(order("PENDING"));
    const view = await mount();

    let resolveSlow: (v: unknown) => void = () => {};
    getOrder.mockImplementationOnce(() => new Promise((r) => (resolveSlow = r))); // 5초 틱: 느린 응답
    await vi.advanceTimersByTimeAsync(5_000);

    getOrder.mockResolvedValueOnce(order("PAID")); // 탭 복귀: 나중에 시작했지만 먼저 도착
    await act(async () => {
      setVisibility("visible");
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(view.result.current.order?.status).toBe("PAID");

    await act(async () => resolveSlow(order("PENDING")));
    expect(view.result.current.order?.status).toBe("PAID"); // 순번 가드(latest)
  });
});
