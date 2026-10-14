import { api } from "@/lib/apiClient";
import type { Page } from "@/features/order/api/order";
import type { OutboxStatus } from "@/types/contracts";

/**
 * 운영 대시보드 지표. outboxPending·outboxDead=아웃박스 미발행·격리(ADR-028 — Kafka 연결·DLQ 필드는 없앴다).
 * 선택으로 둔 것은 P2a 롤링 배포 호환의 흔적이다(그 이전 API 응답에는 없었다). 화면은 없을 때를 방어한다.
 */
export type AdminDashboard = {
  totalEvents: number;
  paidOrders: number;
  revenue: number;
  outboxPending?: number;
  outboxDead?: number;
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

// --- 이벤트 관리 ---
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

// --- 아웃박스 ---
export type OutboxEvent = {
  id: string;
  aggregateType: string;
  aggregateId: number;
  type: string;
  payload: string;
  status: OutboxStatus;
  attempts: number;
  lastError: string | null;
  createdAt: string;
  publishedAt: string | null;
};

export const getOutbox = (
  params: { status?: string; page?: number; size?: number },
  token: string | null
) => {
  const q = new URLSearchParams();
  if (params.status && params.status !== "all") q.set("status", params.status);
  if (params.page != null) q.set("page", String(params.page));
  if (params.size != null) q.set("size", String(params.size));
  return api<Page<OutboxEvent>>(`/admin/outbox?${q.toString()}`, { token });
};

export const requeueOutbox = (id: string, token: string | null) =>
  api<void>(`/admin/outbox/${id}/requeue`, { method: "POST", token });

export const discardOutbox = (id: string, token: string | null) =>
  api<void>(`/admin/outbox/${id}/discard`, { method: "POST", token });

// --- 알림 임계치 ---
/** 아웃박스 격리(DEAD) 임계치·현재 격리 수·초과 여부. DLQ 필드는 Kafka 제거(ADR-028)로 없앴다. */
export type AlertSettings = {
  outboxDeadThreshold?: number;
  outboxDead?: number;
  outboxBreached?: boolean;
};

export const getAlerts = (token: string | null) =>
  api<AlertSettings>("/admin/alerts", { token });

/** 임계치 수정(아웃박스 격리 임계치, 0 이상). */
export const updateAlerts = (
  body: { outboxDeadThreshold: number },
  token: string | null
) => api<AlertSettings>("/admin/alerts", { method: "PUT", token, body });

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
