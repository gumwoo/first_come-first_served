// VIOLATION: OrderStatus에서 REFUNDED 누락 → 하네스가 실패해야 함
export const OrderStatus = [
  "PENDING",
  "VBANK_WAITING",
  "PAID",
  "FAILED",
  "EXPIRED",
  "CANCELLED",
] as const;

export const SUBSCRIBED_EVENTS = [
  "order.paid",
  "order.failed",
  "seat.hold.expired",
  "queue.admitted",
  "queue.expired",
  "payment.vbank.deposited",
] as const;
