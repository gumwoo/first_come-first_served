"use client";

import { useQuery } from "@tanstack/react-query";
import * as orderApi from "@/features/order/api/order";
import { meKeys } from "@/features/order/queryKeys";
import { useAuthStore } from "@/features/auth/store/authStore";

/** 마이페이지 예매 목록(탭 필터 + 페이징). 로그인(토큰) 있을 때만 조회. */
export function useMyOrders(status: string, page: number, size = 8) {
  const token = useAuthStore((s) => s.accessToken);
  return useQuery({
    queryKey: meKeys.orderList(status, page, size),
    queryFn: () => orderApi.getMyOrders({ status, page, size }, token),
    enabled: !!token,
  });
}

/** 마이페이지 예매 상세(선택 항목). */
export function useMyOrder(orderId: number | null) {
  const token = useAuthStore((s) => s.accessToken);
  return useQuery({
    queryKey: meKeys.orderDetail(orderId),
    queryFn: () => orderApi.getMyOrder(orderId as number, token),
    enabled: !!token && orderId != null,
  });
}
