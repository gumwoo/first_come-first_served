import { api } from "@/lib/apiClient";
import type { Page } from "@/features/order/api/order";

/** 운영 대시보드 지표(S07). kafkaConnected는 Phase 4에서 실연결. */
export type AdminDashboard = {
  totalEvents: number;
  paidOrders: number;
  revenue: number;
  kafkaConnected: boolean;
};

/** 운영 주문 목록 항목(전 사용자). 주문자(userId/email) 포함. */
export type AdminOrderSummary = {
  orderId: number;
  eventId: number;
  eventTitle: string | null;
  userId: number;
  userEmail: string | null;
  status: string; // OrderStatus
  amount: number;
  createdAt: string;
  paidAt: string | null;
};

export const getDashboard = (token: string | null) =>
  api<AdminDashboard>("/admin/dashboard", { token });

/** 전 사용자 주문 목록. status 필터(옵션)·페이징. */
export const getAdminOrders = (
  params: { status?: string; page?: number; size?: number },
  token: string | null
) => {
  const q = new URLSearchParams();
  if (params.status && params.status !== "all") q.set("status", params.status);
  if (params.page != null) q.set("page", String(params.page));
  if (params.size != null) q.set("size", String(params.size));
  return api<Page<AdminOrderSummary>>(`/admin/orders?${q.toString()}`, { token });
};
