"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import * as adminApi from "@/features/admin/api/admin";
import type { EventInput } from "@/features/admin/api/admin";
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

/** 운영 이벤트 목록(최신순 페이징). */
export function useAdminEvents(page: number, size = 15) {
  const token = useAuthStore((s) => s.accessToken);
  const isAdmin = useIsAdmin();
  return useQuery({
    queryKey: ["admin", "events", { page, size }],
    queryFn: () => adminApi.getAdminEvents({ page, size }, token),
    enabled: !!token && isAdmin,
  });
}

/** 이벤트 등록·수정(성공 시 목록 무효화). */
export function useSaveAdminEvent() {
  const token = useAuthStore((s) => s.accessToken);
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: number | null; body: EventInput }) =>
      id == null ? adminApi.createAdminEvent(body, token) : adminApi.updateAdminEvent(id, body, token),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["admin", "events"] });
      qc.invalidateQueries({ queryKey: ["admin", "dashboard"] });
    },
  });
}

/** DLQ 적재 목록(상태 필터 + 페이징). */
export function useAdminDlq(status: string, page: number, size = 10) {
  const token = useAuthStore((s) => s.accessToken);
  const isAdmin = useIsAdmin();
  return useQuery({
    queryKey: ["admin", "dlq", { status, page, size }],
    queryFn: () => adminApi.getDlq({ status, page, size }, token),
    enabled: !!token && isAdmin,
  });
}

/** DLQ 재시도/폐기(성공 시 목록·대시보드 무효화). */
export function useDlqAction() {
  const token = useAuthStore((s) => s.accessToken);
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, action }: { id: number; action: "retry" | "discard" }) =>
      action === "retry" ? adminApi.retryDlq(id, token) : adminApi.discardDlq(id, token),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["admin", "dlq"] });
      qc.invalidateQueries({ queryKey: ["admin", "dashboard"] });
    },
  });
}
