import { api } from "@/lib/apiClient";
import type { Page } from "@/features/order/api/order";

/** 운영 대시보드 지표(S07). kafkaConnected=실 연결(4a), dlqPending=DLQ 적체(4c). */
export type AdminDashboard = {
  totalEvents: number;
  paidOrders: number;
  revenue: number;
  kafkaConnected: boolean;
  dlqPending: number;
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

// --- 이벤트 관리(S07) ---
export type AdminEventSummary = {
  id: number;
  title: string;
  venue: string | null;
  genre: string | null;
  status: string; // EventStatus
  startDate: string | null;
  endDate: string | null;
  basePrice: number | null;
  fromKopis: boolean;
};

export type AdminEventDetail = {
  id: number;
  kopisId: string | null;
  title: string;
  venue: string | null;
  region: string | null;
  genre: string | null;
  posterUrl: string | null;
  startDate: string | null;
  endDate: string | null;
  runningTime: string | null;
  ageLimit: string | null;
  status: string;
  basePrice: number | null;
  createdAt: string;
  updatedAt: string;
};

/** 수동 등록/부분 수정 본문. null/undefined 필드는 서버에서 무시(변경 없음). */
export type EventInput = {
  title?: string;
  venue?: string | null;
  region?: string | null;
  genre?: string | null;
  posterUrl?: string | null;
  startDate?: string | null;
  endDate?: string | null;
  runningTime?: string | null;
  ageLimit?: string | null;
  status?: string;
  basePrice?: number | null;
};

export const getAdminEvents = (
  params: { page?: number; size?: number },
  token: string | null
) => {
  const q = new URLSearchParams();
  if (params.page != null) q.set("page", String(params.page));
  if (params.size != null) q.set("size", String(params.size));
  return api<Page<AdminEventSummary>>(`/admin/events?${q.toString()}`, { token });
};

export const getAdminEvent = (id: number, token: string | null) =>
  api<AdminEventDetail>(`/admin/events/${id}`, { token });

export const createAdminEvent = (body: EventInput, token: string | null) =>
  api<AdminEventDetail>("/admin/events", { method: "POST", token, body });

export const updateAdminEvent = (id: number, body: EventInput, token: string | null) =>
  api<AdminEventDetail>(`/admin/events/${id}`, { method: "PATCH", token, body });

// --- DLQ(S07 Phase 4c) ---
export type DlqMessage = {
  id: number;
  topic: string;
  payload: string;
  errorMessage: string | null;
  status: string; // DlqStatus
  createdAt: string;
  retriedAt: string | null;
};

export const getDlq = (
  params: { status?: string; page?: number; size?: number },
  token: string | null
) => {
  const q = new URLSearchParams();
  if (params.status && params.status !== "all") q.set("status", params.status);
  if (params.page != null) q.set("page", String(params.page));
  if (params.size != null) q.set("size", String(params.size));
  return api<Page<DlqMessage>>(`/admin/dlq?${q.toString()}`, { token });
};

export const retryDlq = (id: number, token: string | null) =>
  api<void>(`/admin/dlq/${id}/retry`, { method: "POST", token });

export const discardDlq = (id: number, token: string | null) =>
  api<void>(`/admin/dlq/${id}/discard`, { method: "POST", token });

// --- 알림 임계치(S07) ---
export type AlertSettings = {
  dlqPendingThreshold: number;
  dlqPending: number;
  breached: boolean;
};

export const getAlerts = (token: string | null) =>
  api<AlertSettings>("/admin/alerts", { token });

export const updateAlerts = (dlqPendingThreshold: number, token: string | null) =>
  api<AlertSettings>("/admin/alerts", { method: "PUT", token, body: { dlqPendingThreshold } });

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
