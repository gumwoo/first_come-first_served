import type { AdminOrderSummary } from "@/features/admin/api/admin";
import { OrderStatusPill, won, orderNo, dateTime } from "@/features/admin/components/ui";

/** 운영 주문 테이블 — 대시보드 미리보기·주문 조회 페이지 공용. */
export function OrderTable({ items }: { items: AdminOrderSummary[] }) {
  return (
    <div className="overflow-x-auto rounded-lg border border-border">
      <table className="w-full min-w-[720px] text-sm">
        <thead>
          <tr className="border-b border-border bg-muted/40 text-xs text-muted-foreground">
            <th className="px-4 py-2.5 text-left font-medium">주문번호</th>
            <th className="px-4 py-2.5 text-left font-medium">주문자</th>
            <th className="px-4 py-2.5 text-left font-medium">공연</th>
            <th className="px-4 py-2.5 text-right font-medium">금액</th>
            <th className="px-4 py-2.5 text-right font-medium">상태</th>
            <th className="px-4 py-2.5 text-right font-medium">주문일시</th>
          </tr>
        </thead>
        <tbody>
          {items.map((o) => (
            <tr key={o.orderId} className="border-b border-border last:border-0 hover:bg-muted/30">
              <td className="px-4 py-2.5 font-mono text-xs text-muted-foreground">{orderNo(o.orderId)}</td>
              <td className="max-w-[200px] truncate px-4 py-2.5">{o.userEmail ?? `#${o.userId}`}</td>
              <td className="max-w-[200px] truncate px-4 py-2.5 text-muted-foreground">
                {o.eventTitle ?? `이벤트 #${o.eventId}`}
              </td>
              <td className="px-4 py-2.5 text-right tabular-nums">{won(o.amount)}</td>
              <td className="px-4 py-2.5 text-right"><OrderStatusPill status={o.status} /></td>
              <td className="px-4 py-2.5 text-right text-xs text-muted-foreground">
                {dateTime(o.paidAt ?? o.createdAt)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
