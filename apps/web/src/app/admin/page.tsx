"use client";

import { useState } from "react";
import Link from "next/link";
import { ShieldAlert, Server } from "lucide-react";
import { useAdminDashboard, useAdminOrders, useIsAdmin } from "@/features/admin/hooks/useAdmin";
import { useAuthStore } from "@/features/auth/store/authStore";
import type { AdminOrderSummary } from "@/features/admin/api/admin";
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
  const user = useAuthStore((s) => s.user);
  const isAdmin = useIsAdmin();

  // 로그인 안 함 → 안내. 로그인했으나 관리자 아님 → 접근 거부.
  if (!user) return <Gate title="로그인이 필요합니다" desc="관리자 계정으로 로그인해 주세요." showLogin />;
  if (!isAdmin) return <Gate title="접근 권한이 없습니다" desc="이 페이지는 관리자 전용입니다." />;

  return <AdminConsole />;
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
      <h1 className="text-2xl font-bold">운영 콘솔</h1>
      <p className="mt-1 text-sm text-muted-foreground">공연·주문 현황을 조회하고 운영 상태를 확인합니다.</p>

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
    </main>
  );
}

function Stat({ label, value, accent }: { label: string; value: string; accent?: boolean }) {
  return (
    <div>
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className={`text-xl font-bold ${accent ? "text-primary" : ""}`}>{value}</p>
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

function Gate({ title, desc, showLogin }: { title: string; desc: string; showLogin?: boolean }) {
  return (
    <main className="mx-auto flex max-w-md flex-col items-center gap-3 px-4 py-24 text-center">
      <ShieldAlert className="h-12 w-12 text-muted-foreground" />
      <h1 className="text-xl font-bold">{title}</h1>
      <p className="text-sm text-muted-foreground">{desc}</p>
      {showLogin && <Link href="/login"><Button className="mt-2">로그인</Button></Link>}
    </main>
  );
}
