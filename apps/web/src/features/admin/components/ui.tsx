import type { ReactNode } from "react";

export const won = (n: number) => `${n.toLocaleString()}원`;
export const orderNo = (id: number) => `ORD-${String(id).padStart(8, "0")}`;
export const dateTime = (s: string | null | undefined) => (s ? s.slice(0, 16).replace("T", " ") : "-");

// 상태 표시 = 색 칩이 아니라 작은 점 + 무채색 텍스트(Stripe/Linear 톤). 색은 의미에만.
type Tone = "success" | "warning" | "danger" | "info" | "muted";
const DOT: Record<Tone, string> = {
  success: "bg-emerald-500",
  warning: "bg-amber-500",
  danger: "bg-rose-500",
  info: "bg-blue-500",
  muted: "bg-muted-foreground/60",
};

export function StatusDot({ tone, children }: { tone: Tone; children: ReactNode }) {
  return (
    <span className="inline-flex items-center gap-1.5 whitespace-nowrap text-muted-foreground">
      <span className={`h-1.5 w-1.5 shrink-0 rounded-full ${DOT[tone]}`} />
      {children}
    </span>
  );
}

const ORDER_STATUS: Record<string, { label: string; tone: Tone }> = {
  PAID: { label: "결제완료", tone: "success" },
  VBANK_WAITING: { label: "입금대기", tone: "warning" },
  PENDING: { label: "결제대기", tone: "muted" },
  CANCELLED: { label: "취소", tone: "muted" },
  REFUNDED: { label: "환불완료", tone: "danger" },
  EXPIRED: { label: "만료", tone: "muted" },
  FAILED: { label: "실패", tone: "muted" },
};

export function OrderStatusPill({ status }: { status: string }) {
  const s = ORDER_STATUS[status] ?? { label: status, tone: "muted" as Tone };
  return <StatusDot tone={s.tone}>{s.label}</StatusDot>;
}

const DLQ_STATUS: Record<string, { label: string; tone: Tone }> = {
  PENDING: { label: "대기", tone: "danger" },
  RETRYING: { label: "재시도중", tone: "info" },
  RETRIED: { label: "재시도됨", tone: "success" },
  DISCARDED: { label: "폐기됨", tone: "muted" },
};

export function DlqStatusPill({ status }: { status: string }) {
  const s = DLQ_STATUS[status] ?? { label: status, tone: "muted" as Tone };
  return <StatusDot tone={s.tone}>{s.label}</StatusDot>;
}

const EVENT_STATUS: Record<string, { label: string; tone: Tone }> = {
  DRAFT: { label: "초안", tone: "muted" },
  SCHEDULED: { label: "예정", tone: "info" },
  ON_SALE: { label: "판매중", tone: "success" },
  PAUSED: { label: "일시중지", tone: "warning" },
  SOLD_OUT: { label: "매진", tone: "danger" },
  CLOSED: { label: "종료", tone: "muted" },
};

export function EventStatusPill({ status }: { status: string }) {
  const s = EVENT_STATUS[status] ?? { label: status, tone: "muted" as Tone };
  return <StatusDot tone={s.tone}>{s.label}</StatusDot>;
}

export const eventStatusLabel = (status: string) => EVENT_STATUS[status]?.label ?? status;

/** 운영 페이지 공통 헤더 — 타이틀·설명 + 우측 액션 슬롯. */
export function PageHeader({ title, desc, actions }: { title: string; desc?: string; actions?: ReactNode }) {
  return (
    <div className="flex items-start justify-between gap-4 border-b border-border pb-4">
      <div>
        <h1 className="text-lg font-medium tracking-tight">{title}</h1>
        {desc && <p className="mt-1 text-sm text-muted-foreground">{desc}</p>}
      </div>
      {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
    </div>
  );
}
