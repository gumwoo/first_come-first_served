import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, waitFor } from "@testing-library/react";
import { createElement } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";

// 이 테스트가 보는 것은 "인증이 사라질 때 캐시가 비는가" 하나다. 네트워크·스토어는 대역으로 바꾼다.
const refresh = vi.fn();
vi.mock("@/features/auth/api/auth", () => ({
  refresh: () => refresh(),
  getMe: vi.fn(async () => ({ id: 1, role: "ROLE_USER" })),
}));
vi.mock("@/features/auth/store/authStore", () => ({
  useAuthStore: () => ({ setAccessToken: vi.fn(), setUser: vi.fn() }),
}));
let refresher: (() => Promise<string | null>) | null = null;
vi.mock("@/lib/apiClient", () => ({
  setTokenRefresher: (fn: (() => Promise<string | null>) | null) => {
    refresher = fn;
  },
}));

import { AuthBootstrap } from "@/features/auth/components/AuthBootstrap";
import { adminKeys } from "@/features/admin/queryKeys";
import { meKeys } from "@/features/order/queryKeys";
import { eventKeys } from "@/features/event/queryKeys";

/**
 * 인증이 사라지는 경로는 로그아웃 버튼만이 아니다. 리프레시 토큰이 만료·폐기되면 사용자는
 * 아무것도 누르지 않았는데 로그인 상태가 끝난다. 그때 캐시를 두면 다음 사용자가 로그인했을 때
 * 이전 사용자의 주문 목록·운영 지표가 남아 있다.
 */
describe("AuthBootstrap", () => {
  function seeded() {
    const qc = new QueryClient();
    qc.setQueryData(meKeys.orderList("ALL", 0, 8), { content: ["이전 사용자의 예매"] });
    qc.setQueryData(adminKeys.dashboard(), { dlqPending: 1 });
    qc.setQueryData(eventKeys.popular(), ["공연"]);
    return qc;
  }

  // JSX 대신 createElement를 쓴다. 이 워크스페이스의 vitest에는 JSX 변환 플러그인이 없고,
  // 이 테스트 하나 때문에 빌드 파이프라인을 늘릴 이유가 없다.
  function mount(qc: QueryClient) {
    return render(
      createElement(QueryClientProvider, { client: qc }, createElement(AuthBootstrap))
    );
  }

  beforeEach(() => {
    refresh.mockReset();
    refresher = null;
  });

  it("복원(refresh) 실패 시 사용자 캐시를 지운다", async () => {
    refresh.mockRejectedValue(new Error("refresh token expired"));
    const qc = seeded();

    mount(qc);

    await waitFor(() => {
      expect(qc.getQueryData(meKeys.orderList("ALL", 0, 8))).toBeUndefined();
    });
    expect(qc.getQueryData(adminKeys.dashboard())).toBeUndefined();
    // 공개 데이터는 로그인과 무관하다.
    expect(qc.getQueryData(eventKeys.popular())).toEqual(["공연"]);
  });

  it("401 재발급기가 실패하면 사용자 캐시를 지운다", async () => {
    refresh.mockResolvedValueOnce({ accessToken: "at-1" });
    const qc = seeded();
    mount(qc);
    await waitFor(() => expect(refresher).not.toBeNull());

    // 이후 요청이 401을 받아 재발급을 시도했고, 그것마저 실패한 상황
    refresh.mockRejectedValue(new Error("refresh token revoked"));
    const token = await refresher!();

    expect(token).toBeNull();
    expect(qc.getQueryData(meKeys.orderList("ALL", 0, 8))).toBeUndefined();
    expect(qc.getQueryData(adminKeys.dashboard())).toBeUndefined();
  });

  it("복원에 성공하면 캐시를 건드리지 않는다", async () => {
    refresh.mockResolvedValue({ accessToken: "at-1" });
    const qc = seeded();

    mount(qc);
    await waitFor(() => expect(refresher).not.toBeNull());

    expect(qc.getQueryData(meKeys.orderList("ALL", 0, 8))).toEqual({
      content: ["이전 사용자의 예매"],
    });
  });
});
