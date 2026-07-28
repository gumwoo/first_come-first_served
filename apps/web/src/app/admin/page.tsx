"use client";

import Link from "next/link";
import { ArrowRight } from "lucide-react";
import { useAdminDashboard, useAdminOrders, useAlerts } from "@/features/admin/hooks/useAdmin";
import { PageHeader, won } from "@/features/admin/components/ui";
import { OrderTable } from "@/features/admin/components/OrderTable";
import { Skeleton } from "@/components/ui/skeleton";

export default function AdminDashboardPage() {
  const dash = useAdminDashboard();
  const alerts = useAlerts();
  const orders = useAdminOrders("all", 0, 6);

  const kafka = dash.data?.kafkaConnected;

  return (
    <div className="mx-auto max-w-6xl space-y-6 px-6 py-8">
      <PageHeader
        title="대시보드"
        desc="공연·주문 현황과 운영 상태를 확인합니다."
        actions={
          <span className="inline-flex items-center gap-1.5 text-xs text-muted-foreground">
            <span className={`h-1.5 w-1.5 rounded-full ${kafka ? "bg-emerald-500" : "bg-muted-foreground/60"}`} />
            {kafka === undefined ? "Kafka …" : kafka ? "Kafka 연결" : "Kafka 미연결"}
          </span>
        }
      />

      {/* 임계치 초과 경고 */}
      {alerts.data?.breached && (
        <div className="flex items-center gap-2 rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700 dark:border-rose-500/30 dark:bg-rose-500/10 dark:text-rose-400">
          DLQ 적체 {alerts.data.dlqPending}건이 임계치({alerts.data.dlqPendingThreshold})를 넘었습니다.{" "}
          <Link href="/admin/dlq" className="font-medium underline">실패 메시지 확인</Link>
        </div>
      )}

      {/* 지표 — 얇은 구분선의 절제된 요약 행 */}
      {dash.isLoading ? (
        <Skeleton className="h-20 w-full rounded-lg" />
      ) : dash.isError ? (
        <p className="text-sm text-destructive">지표를 불러오지 못했습니다.</p>
      ) : dash.data ? (
        <div className="grid grid-cols-2 gap-px overflow-hidden rounded-lg border border-border bg-border sm:grid-cols-4">
          <StatCell label="총 공연" value={dash.data.totalEvents.toLocaleString()} />
          <StatCell label="결제 완료 주문" value={dash.data.paidOrders.toLocaleString()} />
          <StatCell label="누적 매출" value={won(dash.data.revenue)} />
          <StatCell label="DLQ 적체" value={dash.data.dlqPending.toLocaleString()} warn={dash.data.dlqPending > 0} />
        </div>
      ) : null}

      {/* 최근 주문 미리보기 */}
      <section className="space-y-3">
        <div className="flex items-center justify-between">
          <h2 className="text-sm font-medium">최근 주문</h2>
          <Link href="/admin/orders" className="inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground">
            전체 보기 <ArrowRight className="h-3.5 w-3.5" />
          </Link>
        </div>
        {orders.isLoading ? (
          <Skeleton className="h-40 w-full rounded-lg" />
        ) : orders.data && orders.data.items.length > 0 ? (
          <OrderTable items={orders.data.items} />
        ) : (
          <p className="rounded-lg border border-border py-10 text-center text-sm text-muted-foreground">
            주문이 없습니다.
          </p>
        )}
      </section>
    </div>
  );
}

function StatCell({ label, value, warn }: { label: string; value: string; warn?: boolean }) {
  return (
    <div className="bg-background px-5 py-4">
      <p className="text-[11px] text-muted-foreground">{label}</p>
      <p className={`mt-1 text-lg font-medium tabular-nums ${warn ? "text-rose-600 dark:text-rose-400" : ""}`}>
        {value}
      </p>
    </div>
  );
}
