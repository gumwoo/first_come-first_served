import type { ReactNode } from "react";

export const won = (n: number) => `${n.toLocaleString()}원`;
export const orderNo = (id: number) => `ORD-${String(id).padStart(8, "0")}`;
export const dateTime = (s: string | null | undefined) => (s ? s.slice(0, 16).replace("T", " ") : "-");

// 상태 색 위계 톤 (라이트/다크 모두 대응).
type Tone = "success" | "warning" | "danger" | "info" | "muted";
const TONE: Record<Tone, string> = {
  success: "bg-emerald-50 text-emerald-700 dark:bg-emerald-500/15 dark:text-emerald-400",
  warning: "bg-amber-50 text-amber-700 dark:bg-amber-500/15 dark:text-amber-400",
  danger: "bg-rose-50 text-rose-700 dark:bg-rose-500/15 dark:text-rose-400",
  info: "bg-blue-50 text-blue-700 dark:bg-blue-500/15 dark:text-blue-400",
  muted: "bg-muted text-muted-foreground",
};

export function Pill({ tone, children }: { tone: Tone; children: ReactNode }) {
  return (
    <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${TONE[tone]}`}>
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
  return <Pill tone={s.tone}>{s.label}</Pill>;
}

const DLQ_STATUS: Record<string, { label: string; tone: Tone }> = {
  PENDING: { label: "대기", tone: "danger" },
  RETRYING: { label: "재시도중", tone: "info" },
  RETRIED: { label: "재시도됨", tone: "success" },
  DISCARDED: { label: "폐기됨", tone: "muted" },
};

export function DlqStatusPill({ status }: { status: string }) {
  const s = DLQ_STATUS[status] ?? { label: status, tone: "muted" as Tone };
  return <Pill tone={s.tone}>{s.label}</Pill>;
}

/** 운영 페이지 공통 헤더 — 타이틀·설명 + 우측 액션 슬롯. */
export function PageHeader({ title, desc, actions }: { title: string; desc?: string; actions?: ReactNode }) {
  return (
    <div className="flex items-start justify-between gap-4 border-b border-border pb-4">
      <div>
        <h1 className="text-xl font-semibold">{title}</h1>
        {desc && <p className="mt-1 text-sm text-muted-foreground">{desc}</p>}
      </div>
      {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
    </div>
  );
}
