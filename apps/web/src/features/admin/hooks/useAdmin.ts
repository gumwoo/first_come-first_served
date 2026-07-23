"use client";

import { useQuery } from "@tanstack/react-query";
import * as adminApi from "@/features/admin/api/admin";
import { useAuthStore } from "@/features/auth/store/authStore";

/** 관리자 여부(FE 게이트용). 서버가 최종 권한 판단은 /admin/** 게이트로 수행. */
export function useIsAdmin() {
  return useAuthStore((s) => s.user?.role === "ROLE_ADMIN");
}

/** 운영 대시보드 지표. 관리자 토큰 있을 때만 조회. */
export function useAdminDashboard() {
  const token = useAuthStore((s) => s.accessToken);
  const isAdmin = useIsAdmin();
  return useQuery({
    queryKey: ["admin", "dashboard"],
    queryFn: () => adminApi.getDashboard(token),
    enabled: !!token && isAdmin,
  });
}

/** 운영 주문 목록(상태 필터 + 페이징). */
export function useAdminOrders(status: string, page: number, size = 15) {
  const token = useAuthStore((s) => s.accessToken);
  const isAdmin = useIsAdmin();
  return useQuery({
    queryKey: ["admin", "orders", { status, page, size }],
    queryFn: () => adminApi.getAdminOrders({ status, page, size }, token),
    enabled: !!token && isAdmin,
  });
}
