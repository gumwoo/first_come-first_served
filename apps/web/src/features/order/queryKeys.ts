/** 마이페이지(내 예매) 쿼리 키. 로그인 사용자에 종속되므로 로그아웃 때 all을 통째로 제거한다. */
export const meKeys = {
  all: ["me"] as const,

  orders: () => [...meKeys.all, "orders"] as const,
  orderList: (status: string, page: number, size: number) =>
    [...meKeys.orders(), { status, page, size }] as const,
  orderDetail: (orderId: number | null) => [...meKeys.orders(), "detail", orderId] as const,
};
