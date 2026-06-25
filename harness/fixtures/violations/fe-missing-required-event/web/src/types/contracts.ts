// 격리용: SUBSCRIBED_EVENTS도 fixture events.yaml의 fe_subscribes와 동일하게
// queue.admitted를 뺀다. 그래야 "필수 구독 누락" 검사만 단독으로 트리거됨.
export const OrderStatus = ["PENDING","VBANK_WAITING","PAID","FAILED","EXPIRED","CANCELLED","REFUNDED"] as const;
export const QueueStatus = ["WAITING","ADMITTED","EXPIRED"] as const;
export const SeatHoldStatus = ["HELD","RELEASED","EXPIRED","CONVERTED"] as const;
export const PaymentMethod = ["card","easy","vbank"] as const;
export const PaymentProvider = ["kakaopay","naverpay","toss","payco"] as const;
export const EventStatus = ["DRAFT","SCHEDULED","ON_SALE","PAUSED","SOLD_OUT","CLOSED"] as const;
export const RefundReason = ["CHANGE_OF_MIND","SCHEDULE_CONFLICT","DUPLICATE","OTHER"] as const;
export const DlqStatus = ["PENDING","RETRYING","RETRIED","DISCARDED"] as const;
export const UserRole = ["ROLE_USER","ROLE_ADMIN","ROLE_DEV"] as const;
export const LoadTestStatus = ["QUEUED","RUNNING","PASSED","FAILED","STOPPED"] as const;
export const SUBSCRIBED_EVENTS = ["order.paid","order.failed","seat.hold.expired","queue.expired","payment.vbank.deposited"] as const;
