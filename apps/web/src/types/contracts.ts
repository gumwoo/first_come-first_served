// BE 계약 미러. contracts/enums.yaml · events.yaml 과 일치해야 함(하네스 diff).
// 변경 시 contracts/ + BE enum + 테스트를 같은 PR에서 함께 수정한다.

export const OrderStatus = [
  "PENDING",
  "VBANK_WAITING",
  "PAID",
  "FAILED",
  "EXPIRED",
  "CANCELLED",
  "REFUNDED",
] as const;
export type OrderStatus = (typeof OrderStatus)[number];

export const QueueStatus = ["WAITING", "ADMITTED", "EXPIRED"] as const;
export type QueueStatus = (typeof QueueStatus)[number];

export const SeatHoldStatus = ["HELD", "RELEASED", "EXPIRED", "CONVERTED"] as const;
export type SeatHoldStatus = (typeof SeatHoldStatus)[number];

export const SeatGrade = ["VIP", "R", "S", "A"] as const;
export type SeatGrade = (typeof SeatGrade)[number];

export const PaymentMethod = ["card", "easy", "vbank"] as const;
export type PaymentMethod = (typeof PaymentMethod)[number];

export const PaymentProvider = ["kakaopay", "naverpay", "toss", "payco"] as const;
export type PaymentProvider = (typeof PaymentProvider)[number];

export const EventStatus = [
  "DRAFT",
  "SCHEDULED",
  "ON_SALE",
  "PAUSED",
  "SOLD_OUT",
  "CLOSED",
] as const;
export type EventStatus = (typeof EventStatus)[number];

export const RefundReason = [
  "CHANGE_OF_MIND",
  "SCHEDULE_CONFLICT",
  "DUPLICATE",
  "OTHER",
] as const;
export type RefundReason = (typeof RefundReason)[number];

export const DlqStatus = ["PENDING", "RETRYING", "RETRIED", "DISCARDED"] as const;
export type DlqStatus = (typeof DlqStatus)[number];

export const UserRole = ["ROLE_USER", "ROLE_ADMIN", "ROLE_DEV"] as const;
export type UserRole = (typeof UserRole)[number];

export const LoadTestStatus = [
  "QUEUED",
  "RUNNING",
  "PASSED",
  "FAILED",
  "STOPPED",
] as const;
export type LoadTestStatus = (typeof LoadTestStatus)[number];

// FE가 구독하는 실시간 이벤트 — contracts/events.yaml fe_subscribes 와 일치
export const SUBSCRIBED_EVENTS = [
  "order.paid",
  "order.failed",
  "seat.hold.expired",
  "queue.admitted",
  "queue.expired",
  "payment.vbank.deposited",
] as const;
export type SubscribedEvent = (typeof SUBSCRIBED_EVENTS)[number];

// 공통 응답 래퍼
export type ApiSuccess<T> = { data: T };
export type ApiError = { error: { code: string; message: string } };
