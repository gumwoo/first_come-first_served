"use client";

import { useState } from "react";
import Link from "next/link";
import { User, Ticket, Receipt, ChevronRight } from "lucide-react";
import { useMyOrders, useMyOrder } from "@/features/order/hooks/useMyOrders";
import { useAuthStore } from "@/features/auth/store/authStore";
import type { MyOrderSummary } from "@/features/order/api/order";
import { Card, CardContent } from "@/components/ui/card";
import { Badge, type BadgeProps } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";

const TABS = [
  { id: "all", label: "전체" },
  { id: "upcoming", label: "예정" },
  { id: "cancelled", label: "취소" },
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

export default function MyOrdersPage() {
  const user = useAuthStore((s) => s.user);
  const [tab, setTab] = useState<string>("all");
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<number | null>(null);

  const { data, isLoading, isError } = useMyOrders(tab, page);
  const detail = useMyOrder(selected);

  const switchTab = (id: string) => {
    setTab(id);
    setPage(0);
    setSelected(null);
  };

  const totalPages = data ? Math.max(1, Math.ceil(data.total / data.size)) : 1;

  return (
    <main className="mx-auto max-w-6xl px-4 py-8">
      <h1 className="text-2xl font-bold">마이페이지 / 예매 내역</h1>
      <p className="mt-1 text-sm text-muted-foreground">내 예매 내역을 확인하고 취소·환불할 수 있어요.</p>

      <div className="mt-6 grid gap-6 lg:grid-cols-[200px_1fr]">
        {/* 프로필 사이드바 */}
        <aside>
          <Card>
            <CardContent className="flex flex-col items-center gap-2 py-6 text-center">
              <div className="flex h-16 w-16 items-center justify-center rounded-full bg-muted">
                <User className="h-8 w-8 text-muted-foreground" />
              </div>
              <div>
                <p className="font-semibold">{user?.name ?? "회원"}</p>
                <Badge variant="outline" className="mt-1">일반 회원</Badge>
              </div>
              <p className="break-all text-xs text-muted-foreground">{user?.email ?? ""}</p>
            </CardContent>
          </Card>
        </aside>

        {/* 목록 + 상세 */}
        <section>
          {/* 탭 */}
          <div className="flex gap-1 border-b border-border">
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

          <div className="mt-4 grid gap-6 xl:grid-cols-[1fr_300px]">
            {/* 목록 */}
            <div className="space-y-3">
              {!user && (
                <Card><CardContent className="py-10 text-center text-sm text-muted-foreground">로그인이 필요합니다.</CardContent></Card>
              )}
              {isLoading && [0, 1, 2].map((i) => <Skeleton key={i} className="h-20 w-full rounded-lg" />)}
              {isError && (
                <Card><CardContent className="py-10 text-center text-sm text-destructive">목록을 불러오지 못했습니다.</CardContent></Card>
              )}
              {data && data.items.length === 0 && (
                <Card>
                  <CardContent className="flex flex-col items-center gap-2 py-14 text-center">
                    <Receipt className="h-10 w-10 text-muted-foreground" />
                    <p className="text-sm text-muted-foreground">예매 내역이 없습니다.</p>
                    <Link href="/search"><Button variant="ghost" className="mt-1">공연 보러 가기</Button></Link>
                  </CardContent>
                </Card>
              )}

              {data && data.items.length > 0 && (
                <div className="overflow-hidden rounded-lg border border-border">
                  {/* 헤더(데스크톱) */}
                  <div className="hidden grid-cols-[1fr_96px_110px_84px] gap-3 border-b border-border bg-muted/40 px-4 py-2 text-xs font-medium text-muted-foreground sm:grid">
                    <span>이벤트</span><span>공연일</span><span className="text-right">결제금액</span><span className="text-right">상태</span>
                  </div>
                  {data.items.map((o) => (
                    <OrderRow key={o.orderId} order={o} selected={selected === o.orderId}
                              onSelect={() => setSelected(selected === o.orderId ? null : o.orderId)} />
                  ))}
                </div>
              )}

              {/* 페이지네이션 */}
              {data && data.total > data.size && (
                <div className="flex items-center justify-center gap-3 pt-1">
                  <Button variant="ghost" size="sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>이전</Button>
                  <span className="text-sm text-muted-foreground">{page + 1} / {totalPages}</span>
                  <Button variant="ghost" size="sm" disabled={page + 1 >= totalPages} onClick={() => setPage((p) => p + 1)}>다음</Button>
                </div>
              )}
            </div>

            {/* 선택 예매 상세 */}
            <aside className="xl:sticky xl:top-6 xl:self-start">
              <Card>
                <CardContent className="pt-5">
                  {selected == null && (
                    <p className="py-10 text-center text-sm text-muted-foreground">예매를 선택하면<br />상세가 표시됩니다.</p>
                  )}
                  {selected != null && detail.isLoading && <Skeleton className="h-56 w-full" />}
                  {selected != null && detail.data && (
                    <div className="space-y-3 text-sm">
                      <div className="flex gap-3">
                        <div className="h-24 w-16 shrink-0 overflow-hidden rounded bg-muted">
                          {detail.data.posterUrl && <img src={detail.data.posterUrl} alt="" className="h-full w-full object-cover" />}
                        </div>
                        <div className="min-w-0">
                          <p className="font-semibold leading-tight">{detail.data.eventTitle}</p>
                          {detail.data.venue && <p className="mt-1 text-xs text-muted-foreground">{detail.data.venue}</p>}
                          {detail.data.eventDate && <p className="text-xs text-muted-foreground">{detail.data.eventDate}</p>}
                          <span className="mt-1 inline-block"><Badge variant={statusOf(detail.data.status).variant}>{statusOf(detail.data.status).label}</Badge></span>
                        </div>
                      </div>
                      <dl className="space-y-1 border-t border-border pt-3 text-xs">
                        <Row k="예매번호" v={orderNo(detail.data.orderId)} />
                        <Row k="좌석" v={detail.data.items.map((it) => `${it.grade}석`).join(", ")} />
                        <Row k="수량" v={`${detail.data.items.length}매`} />
                        {detail.data.paidAt && <Row k="예매일" v={detail.data.paidAt.slice(0, 10)} />}
                      </dl>
                      <div className="flex justify-between border-t border-border pt-3 font-semibold">
                        <span>총 결제 금액</span><span className="text-primary">{won(detail.data.amount)}</span>
                      </div>
                      {detail.data.status === "PAID" && (
                        <div className="space-y-2 pt-1">
                          <Link href={`/orders/${detail.data.orderId}/complete`}>
                            <Button className="w-full"><Ticket className="mr-1 h-4 w-4" /> 모바일 티켓 보기</Button>
                          </Link>
                          <Link href={`/me/orders/${detail.data.orderId}/refund`}>
                            <Button variant="ghost" className="w-full">예매 취소</Button>
                          </Link>
                        </div>
                      )}
                    </div>
                  )}
                </CardContent>
              </Card>
            </aside>
          </div>
        </section>
      </div>
    </main>
  );
}

function Row({ k, v }: { k: string; v: string }) {
  return (
    <div className="flex justify-between gap-3">
      <dt className="shrink-0 text-muted-foreground">{k}</dt>
      <dd className="truncate text-right">{v}</dd>
    </div>
  );
}

function OrderRow({ order, selected, onSelect }: { order: MyOrderSummary; selected: boolean; onSelect: () => void }) {
  const s = statusOf(order.status);
  return (
    <button
      onClick={onSelect}
      className={`grid w-full items-center gap-3 border-b border-border px-4 py-3 text-left last:border-0 sm:grid-cols-[1fr_96px_110px_84px] ${
        selected ? "bg-primary/5" : "hover:bg-muted/40"
      }`}
    >
      {/* 이벤트 */}
      <div className="flex min-w-0 items-center gap-3">
        <div className="h-14 w-10 shrink-0 overflow-hidden rounded bg-muted">
          {order.posterUrl && <img src={order.posterUrl} alt="" className="h-full w-full object-cover" />}
        </div>
        <div className="min-w-0">
          <p className="truncate font-medium">{order.eventTitle ?? "공연"}</p>
          <p className="text-xs text-muted-foreground">{orderNo(order.orderId)} · 좌석 {order.seatCount}석</p>
        </div>
      </div>
      {/* 공연일 */}
      <span className="text-sm text-muted-foreground sm:text-foreground">{order.eventDate ?? "-"}</span>
      {/* 금액 */}
      <span className="text-sm font-medium sm:text-right">{won(order.amount)}</span>
      {/* 상태 */}
      <span className="flex items-center justify-between gap-1 sm:justify-end">
        <Badge variant={s.variant}>{s.label}</Badge>
        <ChevronRight className="h-4 w-4 text-muted-foreground sm:hidden" />
      </span>
    </button>
  );
}
