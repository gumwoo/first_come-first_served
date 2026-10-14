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

  /**
   * 시계를 1초씩 act 안에서 흘린다. 상태 변화(PENDING→PAID 등)가 렌더·효과 정리까지 끝난 뒤 다음 틱이 오게 하기 위해서다.
   * act 밖에서 흘리면 렌더 시점이 실제 이벤트 루프에 달려 CI에서 순서가 바뀐다(첫 테스트가 CI에서만 실패한 원인 — 추정).
   * 한 act로 길게 흘리면 반대로 그 act가 끝날 때까지 렌더가 미뤄진다.
   */
  async function advance(ms: number) {
    for (let left = ms; left > 0; left -= 1_000) {
      const step = Math.min(1_000, left);
      await act(async () => {
        await vi.advanceTimersByTimeAsync(step);
      });
    }
    if (ms === 0) {
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0);
      });
    }
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
    await advance(0);
    getOrder.mockClear(); // 마운트 조회는 세지 않는다 — 이후의 재조회만 본다
    return view;
  }

  it("PENDING이면 5초마다 재조회한다", async () => {
    getOrder.mockResolvedValue(order("PENDING"));
    await mount();

    await advance(5_000);
    expect(getOrder).toHaveBeenCalledTimes(1);
    await advance(10_000);
    expect(getOrder).toHaveBeenCalledTimes(3);
  });

  it("주문 만료 시각이 지나면 멈춘다", async () => {
    getOrder.mockResolvedValue(order("PENDING", 12_000)); // 12초 뒤 만료
    await mount();

    await advance(60_000);
    expect(getOrder).toHaveBeenCalledTimes(2); // 5초·10초. 15초 틱에서 만료를 보고 멈춘다
  });

  it("종료 상태면 재조회하지 않는다", async () => {
    getOrder.mockResolvedValue(order("PAID"));
    await mount();

    await advance(30_000);
    setVisibility("hidden");
    setVisibility("visible");
    await advance(0);
    expect(getOrder).not.toHaveBeenCalled();
  });

  it("재조회로 PAID를 받으면 그 뒤로는 멈춘다", async () => {
    getOrder.mockResolvedValue(order("PENDING"));
    await mount();
    getOrder.mockResolvedValue(order("PAID"));

    await advance(5_000); // 이 재조회가 PAID를 받는다
    await advance(30_000);
    expect(getOrder).toHaveBeenCalledTimes(1);
  });

  it("탭이 숨겨지면 멈추고, 돌아오면 1회 재조회한 뒤 다시 돈다", async () => {
    getOrder.mockResolvedValue(order("PENDING"));
    await mount();

    act(() => setVisibility("hidden"));
    await advance(30_000);
    expect(getOrder).not.toHaveBeenCalled();

    act(() => setVisibility("visible"));
    await advance(0);
    expect(getOrder).toHaveBeenCalledTimes(1); // 복귀 즉시

    await advance(5_000);
    expect(getOrder).toHaveBeenCalledTimes(2); // 주기 재개
  });

  it("VBANK_WAITING은 주기 재조회 없이 탭 복귀 때만, focus가 겹쳐도 1회", async () => {
    getOrder.mockResolvedValue(order("VBANK_WAITING"));
    await mount();

    await advance(30_000);
    expect(getOrder).not.toHaveBeenCalled();

    act(() => {
      setVisibility("visible");
      window.dispatchEvent(new Event("focus"));
    });
    await advance(0);
    expect(getOrder).toHaveBeenCalledTimes(1);
  });

  it("언마운트하면 타이머와 리스너를 정리한다", async () => {
    getOrder.mockResolvedValue(order("PENDING"));
    const view = await mount();

    view.unmount();
    await advance(30_000);
    setVisibility("visible");
    expect(getOrder).not.toHaveBeenCalled();
  });

  it("응답이 주기보다 느려도 앞선 요청이 끝나기 전에는 새로 내지 않아, 결국 반영된다", async () => {
    getOrder.mockResolvedValue(order("PENDING"));
    const view = await mount();
    // 이후 모든 응답이 6초 걸리고 서버 상태는 PAID다. 매 틱 새 요청을 내면 순번 가드가 앞선 응답을 모두 버린다.
    getOrder.mockImplementation(() => new Promise((r) => setTimeout(() => r(order("PAID")), 6_000)));

    await advance(60_000);
    expect(view.result.current.order?.status).toBe("PAID");
    expect(getOrder.mock.calls.length).toBeLessThanOrEqual(2); // 5초 틱 1회(+그 응답 전 틱은 건너뜀)
  });

  it("숨은 탭으로 마운트되면 돌지 않다가, 보이면 재조회하고 주기를 시작한다", async () => {
    visibility = "hidden";
    getOrder.mockResolvedValue(order("PENDING"));
    await mount();

    await advance(20_000);
    expect(getOrder).not.toHaveBeenCalled();

    act(() => setVisibility("visible"));
    await advance(5_000);
    expect(getOrder).toHaveBeenCalledTimes(2); // 복귀 1 + 주기 1
  });

  it("만료 뒤 탭 복귀는 1회 재조회하지만 주기는 다시 돌지 않는다", async () => {
    getOrder.mockResolvedValue(order("PENDING", 3_000));
    await mount();
    await advance(10_000); // 만료 지남, 주기 멈춤
    getOrder.mockClear();

    act(() => setVisibility("visible"));
    await advance(30_000);
    expect(getOrder).toHaveBeenCalledTimes(1);
  });

  it("복귀 재조회는 1초 안의 중복만 묶고, 그 뒤의 focus는 다시 재조회한다", async () => {
    getOrder.mockResolvedValue(order("VBANK_WAITING"));
    await mount();

    act(() => window.dispatchEvent(new Event("focus")));
    await advance(1_500);
    act(() => window.dispatchEvent(new Event("focus")));
    await advance(0);
    expect(getOrder).toHaveBeenCalledTimes(2);
  });

  it("PENDING에서 VBANK_WAITING으로 바뀌면 주기 재조회를 멈춘다", async () => {
    getOrder.mockResolvedValue(order("PENDING"));
    await mount();
    getOrder.mockResolvedValue(order("VBANK_WAITING"));

    await advance(5_000); // 이 재조회가 VBANK_WAITING을 받는다
    await advance(30_000);
    expect(getOrder).toHaveBeenCalledTimes(1);
  });

  it("토큰이 없으면 재조회하지 않는다", async () => {
    getOrder.mockResolvedValue(order("PENDING"));
    renderHook(() => useOrder(1, null));
    await advance(0);
    getOrder.mockClear();

    await advance(30_000);
    expect(getOrder).not.toHaveBeenCalled();
  });

  it("주기 재조회가 실패해도 다음 틱에 다시 낸다(진행 중 카운트가 실패 경로에서도 풀린다)", async () => {
    getOrder.mockResolvedValue(order("PENDING"));
    await mount();
    getOrder.mockRejectedValueOnce(new Error("503"));

    await advance(5_000); // 실패
    await advance(5_000); // 다시 냄
    expect(getOrder).toHaveBeenCalledTimes(2);
  });

  it("늦게 도착한 PENDING 응답이 나중에 받은 PAID를 덮지 않는다", async () => {
    getOrder.mockResolvedValue(order("PENDING"));
    const view = await mount();

    let resolveSlow: (v: unknown) => void = () => {};
    getOrder.mockImplementationOnce(() => new Promise((r) => (resolveSlow = r))); // 5초 틱: 느린 응답
    await advance(5_000);

    getOrder.mockResolvedValueOnce(order("PAID")); // 탭 복귀: 나중에 시작했지만 먼저 도착
    act(() => setVisibility("visible"));
    await advance(0);
    expect(view.result.current.order?.status).toBe("PAID");

    await act(async () => resolveSlow(order("PENDING")));
    expect(view.result.current.order?.status).toBe("PAID"); // 순번 가드(latest)
  });
});
