"use client";

import { useState } from "react";
import { useAdminOrders } from "@/features/admin/hooks/useAdmin";
import { PageHeader } from "@/features/admin/components/ui";
import { OrderTable } from "@/features/admin/components/OrderTable";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";

const TABS = [
  { id: "all", label: "전체" },
  { id: "PAID", label: "결제완료" },
  { id: "VBANK_WAITING", label: "입금대기" },
  { id: "CANCELLED", label: "취소" },
  { id: "REFUNDED", label: "환불" },
] as const;

export default function AdminOrdersPage() {
  const [tab, setTab] = useState<string>("all");
  const [page, setPage] = useState(0);
  const orders = useAdminOrders(tab, page);

  const switchTab = (id: string) => {
    setTab(id);
    setPage(0);
  };
  const totalPages = orders.data ? Math.max(1, Math.ceil(orders.data.total / orders.data.size)) : 1;

  return (
    <div className="mx-auto max-w-6xl space-y-5 px-6 py-8">
      <PageHeader title="주문 조회" desc="전 사용자 주문을 상태별로 조회합니다." />

      <div className="flex gap-1 border-b border-border">
        {TABS.map((t) => (
          <button
            key={t.id}
            onClick={() => switchTab(t.id)}
            className={`-mb-px border-b-2 px-4 py-2 text-sm ${
              tab === t.id
                ? "border-primary font-medium text-primary"
                : "border-transparent text-muted-foreground hover:text-foreground"
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>

      {orders.isLoading ? (
        <Skeleton className="h-64 w-full rounded-lg" />
      ) : orders.isError ? (
        <p className="rounded-lg border border-border py-10 text-center text-sm text-destructive">
          주문을 불러오지 못했습니다.
        </p>
      ) : orders.data && orders.data.items.length === 0 ? (
        <p className="rounded-lg border border-border py-14 text-center text-sm text-muted-foreground">
          해당 조건의 주문이 없습니다.
        </p>
      ) : orders.data ? (
        <OrderTable items={orders.data.items} />
      ) : null}

      {orders.data && orders.data.total > orders.data.size && (
        <div className="flex items-center justify-center gap-3">
          <Button variant="ghost" size="sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>이전</Button>
          <span className="text-sm text-muted-foreground">{page + 1} / {totalPages}</span>
          <Button variant="ghost" size="sm" disabled={page + 1 >= totalPages} onClick={() => setPage((p) => p + 1)}>다음</Button>
        </div>
      )}
    </div>
  );
}
