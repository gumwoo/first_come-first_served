import { QueryClient } from "@tanstack/react-query";
import { describe, expect, it } from "vitest";

import { clearUserScopedCache } from "@/features/auth/cache";
import { adminKeys } from "@/features/admin/queryKeys";
import { meKeys } from "@/features/order/queryKeys";
import { eventKeys, searchKeys } from "@/features/event/queryKeys";

/**
 * 로그아웃 시 캐시 정리 범위.
 *
 * 쿼리 키에 사용자 식별자가 없으므로, 토큰만 지우면 다음 사용자가 로그인했을 때 이전 사용자의
 * 응답이 캐시에 그대로 남아 있다.
 */
describe("clearUserScopedCache", () => {
  function seeded() {
    const qc = new QueryClient();
    qc.setQueryData(adminKeys.dashboard(), { dlqPending: 3 });
    qc.setQueryData(adminKeys.orderList("PAID", 0, 15), { content: ["이전 관리자의 주문"] });
    qc.setQueryData(meKeys.orderList("ALL", 0, 8), { content: ["이전 사용자의 예매"] });
    qc.setQueryData(meKeys.orderDetail(7), { id: 7 });
    qc.setQueryData(eventKeys.popular(), ["공연"]);
    qc.setQueryData(searchKeys.popularKeywords(), ["검색어"]);
    return qc;
  }

  it("사용자에 종속된 캐시를 지운다", () => {
    const qc = seeded();

    clearUserScopedCache(qc);

    expect(qc.getQueryData(adminKeys.dashboard())).toBeUndefined();
    expect(qc.getQueryData(adminKeys.orderList("PAID", 0, 15))).toBeUndefined();
    expect(qc.getQueryData(meKeys.orderList("ALL", 0, 8))).toBeUndefined();
    expect(qc.getQueryData(meKeys.orderDetail(7))).toBeUndefined();
  });

  it("공개 데이터는 남긴다", () => {
    const qc = seeded();

    clearUserScopedCache(qc);

    // 로그인 여부와 무관한 목록이라 다시 받을 이유가 없다(clear()를 쓰지 않는 이유).
    expect(qc.getQueryData(eventKeys.popular())).toEqual(["공연"]);
    expect(qc.getQueryData(searchKeys.popularKeywords())).toEqual(["검색어"]);
  });
});

/**
 * 키 계층. 무효화는 접두사 부분 일치로 동작하므로, 항목 키가 그룹 키로 시작하지 않으면
 * 목록 무효화가 조용히 빗나간다.
 */
describe("queryKeys", () => {
  it("항목 키는 그룹 키로 시작한다", () => {
    expect(adminKeys.orderList("PAID", 1, 15).slice(0, 2)).toEqual(adminKeys.orders());
    expect(adminKeys.eventList(1, 15).slice(0, 2)).toEqual(adminKeys.events());
    expect(adminKeys.dlqList("PENDING", 0, 10).slice(0, 2)).toEqual(adminKeys.dlq());
    expect(meKeys.orderDetail(1).slice(0, 2)).toEqual(meKeys.orders());
  });

  it("그룹 키는 루트로 시작한다", () => {
    for (const group of [adminKeys.dashboard(), adminKeys.orders(), adminKeys.events(),
      adminKeys.dlq(), adminKeys.alerts()]) {
      expect(group.slice(0, 1)).toEqual(adminKeys.all);
    }
    expect(meKeys.orders().slice(0, 1)).toEqual(meKeys.all);
  });

  /** 이 두 루트가 겹치면 로그아웃이 공개 캐시까지 지운다. */
  it("사용자 종속 루트와 공개 루트는 겹치지 않는다", () => {
    const userRoots = [adminKeys.all[0], meKeys.all[0]];
    expect(userRoots).not.toContain(eventKeys.all[0]);
    expect(userRoots).not.toContain(searchKeys.all[0]);
  });
});
