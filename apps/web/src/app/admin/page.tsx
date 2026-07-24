"use client";

import { useState } from "react";
import Link from "next/link";
import { Server, CalendarCog, RotateCcw, Trash2, AlertTriangle } from "lucide-react";
import { useAdminDashboard, useAdminOrders, useAdminDlq, useDlqAction } from "@/features/admin/hooks/useAdmin";
import { AdminGate } from "@/features/admin/components/AdminGate";
import type { AdminOrderSummary, DlqMessage } from "@/features/admin/api/admin";
import { Card, CardContent } from "@/components/ui/card";
import { Badge, type BadgeProps } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";

// 상태 필터 탭 — id는 백엔드 OrderStatus 값(전체는 all).
const TABS = [
  { id: "all", label: "전체" },
  { id: "PAID", label: "결제완료" },
  { id: "VBANK_WAITING", label: "입금대기" },
  { id: "CANCELLED", label: "취소" },
  { id: "REFUNDED", label: "환불" },
] as const;

const STATUS: Record<string, { label: string; variant: BadgeProps["variant"] }> = {
  PAID: { label: "결제완료", variant: "default" },
  VBANK_WAITING: { label: "입금대기", variant: "outline" },
  PENDING: { label: "결제대기", variant: "muted" },
  CANCELLED: { label: "취소", variant: "muted" },
  REFUNDED: { label: "환불완료", variant: "destructive" },
  EXPIRED: { label: "만료", variant: "muted" },
  FAILED: { label: "실패", variant: "muted" },
};

const won = (n: number) => `${n.toLocaleString()}원`;
const orderNo = (id: number) => `ORD-${String(id).padStart(8, "0")}`;
const statusOf = (s: string) => STATUS[s] ?? { label: s, variant: "muted" as const };

export default function AdminPage() {
  return (
    <AdminGate>
      <AdminConsole />
    </AdminGate>
  );
}

function AdminConsole() {
  const [tab, setTab] = useState<string>("all");
  const [page, setPage] = useState(0);

  const dash = useAdminDashboard();
  const orders = useAdminOrders(tab, page);

  const switchTab = (id: string) => {
    setTab(id);
    setPage(0);
  };

  const totalPages = orders.data ? Math.max(1, Math.ceil(orders.data.total / orders.data.size)) : 1;

  return (
    <main className="mx-auto max-w-6xl px-4 py-8">
      <div className="flex items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold">운영 콘솔</h1>
          <p className="mt-1 text-sm text-muted-foreground">공연·주문 현황을 조회하고 운영 상태를 확인합니다.</p>
        </div>
        <Link href="/admin/events">
          <Button variant="outline"><CalendarCog className="mr-1 h-4 w-4" /> 공연 관리</Button>
        </Link>
      </div>

      {/* 지표 스트립 (카드 나열 대신 한 줄 요약) */}
      <Card className="mt-6">
        <CardContent className="flex flex-wrap items-center gap-x-8 gap-y-4 py-4">
          {dash.isLoading ? (
            <Skeleton className="h-8 w-full" />
          ) : dash.isError ? (
            <span className="text-sm text-destructive">지표를 불러오지 못했습니다.</span>
          ) : dash.data ? (
            <>
              <Stat label="총 공연" value={dash.data.totalEvents.toLocaleString()} />
              <Stat label="결제 완료 주문" value={dash.data.paidOrders.toLocaleString()} />
              <Stat label="누적 매출" value={won(dash.data.revenue)} accent />
              <Stat label="DLQ 적체" value={dash.data.dlqPending.toLocaleString()} warn={dash.data.dlqPending > 0} />
              <div className="ml-auto flex items-center gap-2 text-sm">
                <Server className="h-4 w-4 text-muted-foreground" />
                <span className="text-muted-foreground">Kafka</span>
                <Badge variant={dash.data.kafkaConnected ? "default" : "muted"}>
                  {dash.data.kafkaConnected ? "연결됨" : "미연결"}
                </Badge>
              </div>
            </>
          ) : null}
        </CardContent>
      </Card>

      {/* 주문 조회 */}
      <section className="mt-8">
        <h2 className="text-lg font-semibold">주문 조회</h2>

        <div className="mt-3 flex gap-1 border-b border-border">
          {TABS.map((t) => (
            <button
              key={t.id}
              onClick={() => switchTab(t.id)}
              className={`-mb-px border-b-2 px-4 py-2 text-sm ${
                tab === t.id
                  ? "border-primary font-semibold text-primary"
                  : "border-transparent text-muted-foreground hover:text-foreground"
              }`}
            >
              {t.label}
            </button>
          ))}
        </div>

        <div className="mt-4 space-y-3">
          {orders.isLoading && [0, 1, 2, 3].map((i) => <Skeleton key={i} className="h-14 w-full rounded-lg" />)}
          {orders.isError && (
            <Card><CardContent className="py-10 text-center text-sm text-destructive">주문을 불러오지 못했습니다.</CardContent></Card>
          )}
          {orders.data && orders.data.items.length === 0 && (
            <Card><CardContent className="py-14 text-center text-sm text-muted-foreground">해당 조건의 주문이 없습니다.</CardContent></Card>
          )}

          {orders.data && orders.data.items.length > 0 && (
            <div className="overflow-hidden rounded-lg border border-border">
              {/* 헤더(데스크톱) */}
              <div className="hidden grid-cols-[130px_1fr_1fr_100px_120px_150px] gap-3 border-b border-border bg-muted/40 px-4 py-2 text-xs font-medium text-muted-foreground md:grid">
                <span>주문번호</span><span>주문자</span><span>공연</span><span className="text-right">금액</span><span className="text-right">상태</span><span className="text-right">주문일시</span>
              </div>
              {orders.data.items.map((o) => <OrderRow key={o.orderId} order={o} />)}
            </div>
          )}

          {orders.data && orders.data.total > orders.data.size && (
            <div className="flex items-center justify-center gap-3 pt-1">
              <Button variant="ghost" size="sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>이전</Button>
              <span className="text-sm text-muted-foreground">{page + 1} / {totalPages}</span>
              <Button variant="ghost" size="sm" disabled={page + 1 >= totalPages} onClick={() => setPage((p) => p + 1)}>다음</Button>
            </div>
          )}
        </div>
      </section>

      {/* DLQ (S07 Phase 4c) */}
      <DlqSection />
    </main>
  );
}

function DlqSection() {
  const [page, setPage] = useState(0);
  const dlq = useAdminDlq("all", page);
  const action = useDlqAction();

  const totalPages = dlq.data ? Math.max(1, Math.ceil(dlq.data.total / dlq.data.size)) : 1;

  return (
    <section className="mt-10">
      <div className="flex items-center gap-2">
        <AlertTriangle className="h-5 w-5 text-muted-foreground" />
        <h2 className="text-lg font-semibold">DLQ (실패 메시지)</h2>
      </div>
      <p className="mt-1 text-sm text-muted-foreground">
        컨슈머 재시도가 소진된 메시지입니다. 원인 확인 후 재시도하거나 폐기합니다.
      </p>

      <div className="mt-4 space-y-3">
        {dlq.isLoading && [0, 1].map((i) => <Skeleton key={i} className="h-16 w-full rounded-lg" />)}
        {dlq.isError && (
          <Card><CardContent className="py-8 text-center text-sm text-destructive">DLQ를 불러오지 못했습니다.</CardContent></Card>
        )}
        {dlq.data && dlq.data.items.length === 0 && (
          <Card><CardContent className="py-10 text-center text-sm text-muted-foreground">실패 메시지가 없습니다. 🎉</CardContent></Card>
        )}
        {dlq.data && dlq.data.items.map((m) => (
          <DlqRow key={m.id} msg={m} busy={action.isPending}
                  onRetry={() => action.mutate({ id: m.id, action: "retry" })}
                  onDiscard={() => action.mutate({ id: m.id, action: "discard" })} />
        ))}

        {dlq.data && dlq.data.total > dlq.data.size && (
          <div className="flex items-center justify-center gap-3 pt-1">
            <Button variant="ghost" size="sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>이전</Button>
            <span className="text-sm text-muted-foreground">{page + 1} / {totalPages}</span>
            <Button variant="ghost" size="sm" disabled={page + 1 >= totalPages} onClick={() => setPage((p) => p + 1)}>다음</Button>
          </div>
        )}
      </div>
    </section>
  );
}

function DlqRow({ msg, busy, onRetry, onDiscard }: {
  msg: DlqMessage; busy: boolean; onRetry: () => void; onDiscard: () => void;
}) {
  const s = DLQ_STATUS[msg.status] ?? { label: msg.status, variant: "muted" as const };
  const done = msg.status === "RETRIED" || msg.status === "DISCARDED";
  return (
    <Card>
      <CardContent className="flex flex-col gap-2 py-3 md:flex-row md:items-center md:justify-between">
        <div className="min-w-0 space-y-1">
          <div className="flex items-center gap-2">
            <Badge variant={s.variant}>{s.label}</Badge>
            <span className="font-mono text-xs text-muted-foreground">{msg.topic}</span>
            <span className="text-xs text-muted-foreground">{msg.createdAt.slice(0, 16).replace("T", " ")}</span>
          </div>
          <p className="truncate font-mono text-xs">{msg.payload}</p>
          {msg.errorMessage && <p className="truncate text-xs text-destructive">{msg.errorMessage}</p>}
        </div>
        {!done && (
          <div className="flex shrink-0 gap-2">
            <Button size="sm" variant="outline" disabled={busy} onClick={onRetry}>
              <RotateCcw className="mr-1 h-3.5 w-3.5" /> 재시도
            </Button>
            <Button size="sm" variant="ghost" disabled={busy} onClick={onDiscard}>
              <Trash2 className="mr-1 h-3.5 w-3.5" /> 폐기
            </Button>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

const DLQ_STATUS: Record<string, { label: string; variant: BadgeProps["variant"] }> = {
  PENDING: { label: "대기", variant: "destructive" },
  RETRYING: { label: "재시도중", variant: "outline" },
  RETRIED: { label: "재시도됨", variant: "default" },
  DISCARDED: { label: "폐기됨", variant: "muted" },
};

function Stat({ label, value, accent, warn }: { label: string; value: string; accent?: boolean; warn?: boolean }) {
  return (
    <div>
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className={`text-xl font-bold ${warn ? "text-destructive" : accent ? "text-primary" : ""}`}>{value}</p>
    </div>
  );
}

function OrderRow({ order }: { order: AdminOrderSummary }) {
  const s = statusOf(order.status);
  const when = (order.paidAt ?? order.createdAt)?.slice(0, 16).replace("T", " ");
  return (
    <div className="grid gap-3 border-b border-border px-4 py-3 text-sm last:border-0 md:grid-cols-[130px_1fr_1fr_100px_120px_150px] md:items-center">
      <span className="font-mono text-xs text-muted-foreground">{orderNo(order.orderId)}</span>
      <span className="truncate">{order.userEmail ?? `#${order.userId}`}</span>
      <span className="truncate text-muted-foreground">{order.eventTitle ?? `이벤트 #${order.eventId}`}</span>
      <span className="font-medium md:text-right">{won(order.amount)}</span>
      <span className="md:text-right"><Badge variant={s.variant}>{s.label}</Badge></span>
      <span className="text-xs text-muted-foreground md:text-right">{when ?? "-"}</span>
    </div>
  );
}
