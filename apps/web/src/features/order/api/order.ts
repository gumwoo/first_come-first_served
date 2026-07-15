import { api } from "@/lib/apiClient";

export type OrderItem = { seatId: number; grade: string; price: number };
export type Order = {
  orderId: number;
  eventId: number;
  status: string; // OrderStatus
  amount: number;
  expiresAt: string;
  items: OrderItem[];
};
export type PaymentResult = {
  paymentId: number;
  paymentStatus: string; // PaymentStatus
  orderStatus: string;
  vbankAccount: string | null;
  depositDeadline: string | null;
};

/** 좌석 선점(holdId)을 주문으로 승격. */
export const createOrder = (holdId: number, token: string | null) =>
  api<Order>("/orders", { method: "POST", token, body: { holdId } });

export const getOrder = (orderId: number, token: string | null) =>
  api<Order>(`/orders/${orderId}`, { token });

/** 결제 시도. idempotencyKey는 클라이언트 생성(더블클릭 멱등). */
export const payOrder = (
  orderId: number,
  body: { method: string; provider?: string; idempotencyKey: string },
  token: string | null
) => api<PaymentResult>(`/orders/${orderId}/payments`, { method: "POST", token, body });

/** 결제창(Toss) 인증 확정. 결제창이 발급한 paymentKey로 서버 승인(BE-5). */
export const confirmPayment = (orderId: number, paymentKey: string, token: string | null) =>
  api<PaymentResult>(`/orders/${orderId}/payments/confirm`, {
    method: "POST",
    token,
    body: { paymentKey },
  });

/** 무통장 입금 확인(개발/데모 트리거). 실운영은 PG 웹훅. */
export const confirmVbankDeposit = (orderId: number, token: string | null) =>
  api<PaymentResult>(`/dev/vbank/${orderId}/deposit`, { method: "POST", token });

/** 주문 실시간 SSE. order.paid / payment.vbank.deposited / order.failed. */
export const orderSseUrl = (orderId: number) => `/api/sse/orders/${orderId}`;

// --- 마이페이지(S06) ---
export type Page<T> = { items: T[]; page: number; size: number; total: number };

export type MyOrderSummary = {
  orderId: number;
  eventId: number;
  eventTitle: string | null;
  posterUrl: string | null;
  eventDate: string | null; // YYYY-MM-DD
  status: string; // OrderStatus
  amount: number;
  seatCount: number;
  createdAt: string;
};

export type MyOrderDetail = {
  orderId: number;
  eventId: number;
  eventTitle: string | null;
  posterUrl: string | null;
  venue: string | null;
  eventDate: string | null;
  status: string;
  amount: number;
  paidAt: string | null;
  items: OrderItem[];
};

/** 본인 예매 목록. status 탭: undefined/all=전체, upcoming=예정(PAID), cancelled=취소. */
export const getMyOrders = (
  params: { status?: string; page?: number; size?: number },
  token: string | null
) => {
  const q = new URLSearchParams();
  if (params.status && params.status !== "all") q.set("status", params.status);
  if (params.page != null) q.set("page", String(params.page));
  if (params.size != null) q.set("size", String(params.size));
  return api<Page<MyOrderSummary>>(`/me/orders?${q.toString()}`, { token });
};

/** 본인 예매 상세(소유자 검증). */
export const getMyOrder = (orderId: number, token: string | null) =>
  api<MyOrderDetail>(`/me/orders/${orderId}`, { token });
