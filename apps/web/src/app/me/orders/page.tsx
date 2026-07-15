"use client";

import { useState } from "react";
import Link from "next/link";
import { Ticket, Receipt, ChevronRight } from "lucide-react";
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
    <main className="mx-auto max-w-5xl px-4 py-8">
      <h1 className="text-2xl font-bold">마이페이지</h1>
      <p className="mt-1 text-sm text-muted-foreground">내 예매 내역을 확인하고 취소·환불할 수 있어요.</p>

      <div className="mt-6 grid gap-6 lg:grid-cols-[240px_1fr]">
        {/* 좌측: 프로필 + 탭 */}
        <aside className="space-y-4">
          <Card>
            <CardContent className="space-y-1 pt-5">
              <p className="text-lg font-semibold">{user?.name ?? "회원"}</p>
              <p className="text-xs text-muted-foreground">{user?.email ?? ""}</p>
            </CardContent>
          </Card>
          <nav className="flex gap-2 lg:flex-col">
            {TABS.map((t) => (
              <button
                key={t.id}
                onClick={() => switchTab(t.id)}
                className={`rounded-md px-3 py-2 text-left text-sm ${
                  tab === t.id ? "bg-primary/10 font-semibold text-primary" : "text-muted-foreground hover:bg-muted"
                }`}
              >
                {t.label}
              </button>
            ))}
          </nav>
        </aside>

        {/* 우측: 목록 + 상세 */}
        <section className="space-y-4">
          {!user && (
            <Card><CardContent className="py-10 text-center text-sm text-muted-foreground">로그인이 필요합니다.</CardContent></Card>
          )}

          {isLoading && (
            <div className="space-y-3">
              {[0, 1, 2].map((i) => <Skeleton key={i} className="h-24 w-full rounded-lg" />)}
            </div>
          )}

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

          {data?.items.map((o) => (
            <OrderRow key={o.orderId} order={o} selected={selected === o.orderId}
                      onSelect={() => setSelected(selected === o.orderId ? null : o.orderId)} />
          ))}

          {/* 상세 패널 */}
          {selected != null && (
            <Card>
              <CardContent className="pt-5">
                {detail.isLoading && <Skeleton className="h-20 w-full" />}
                {detail.data && (
                  <div className="space-y-3 text-sm">
                    <p className="font-semibold">{detail.data.eventTitle}</p>
                    {detail.data.venue && <p className="text-muted-foreground">{detail.data.venue}</p>}
                    {detail.data.eventDate && <p className="text-muted-foreground">공연일 {detail.data.eventDate}</p>}
                    <div className="rounded-md bg-muted/40 p-3">
                      {detail.data.items.map((it, i) => (
                        <div key={i} className="flex justify-between text-xs">
                          <span>{it.grade}석</span><span>{won(it.price)}</span>
                        </div>
                      ))}
                      <div className="mt-2 flex justify-between border-t border-border pt-2 font-semibold">
                        <span>총 결제</span><span className="text-primary">{won(detail.data.amount)}</span>
                      </div>
                    </div>
                    <div className="flex gap-2">
                      {detail.data.status === "PAID" && (
                        <>
                          <Link href={`/orders/${detail.data.orderId}/complete`} className="flex-1">
                            <Button className="w-full"><Ticket className="mr-1 h-4 w-4" /> 모바일 티켓 보기</Button>
                          </Link>
                          <Link href={`/me/orders/${detail.data.orderId}/refund`} className="flex-1">
                            <Button variant="ghost" className="w-full">예매 취소</Button>
                          </Link>
                        </>
                      )}
                    </div>
                  </div>
                )}
              </CardContent>
            </Card>
          )}

          {/* 페이지네이션 */}
          {data && data.total > data.size && (
            <div className="flex items-center justify-center gap-3 pt-2">
              <Button variant="ghost" size="sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>이전</Button>
              <span className="text-sm text-muted-foreground">{page + 1} / {totalPages}</span>
              <Button variant="ghost" size="sm" disabled={page + 1 >= totalPages} onClick={() => setPage((p) => p + 1)}>다음</Button>
            </div>
          )}
        </section>
      </div>
    </main>
  );
}

function OrderRow({ order, selected, onSelect }: { order: MyOrderSummary; selected: boolean; onSelect: () => void }) {
  const s = STATUS[order.status] ?? { label: order.status, variant: "muted" as const };
  return (
    <button onClick={onSelect} className="block w-full text-left">
      <Card className={selected ? "border-primary" : ""}>
        <CardContent className="flex items-center gap-4 py-4">
          <div className="h-20 w-14 shrink-0 overflow-hidden rounded bg-muted">
            {order.posterUrl && <img src={order.posterUrl} alt="" className="h-full w-full object-cover" />}
          </div>
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-2">
              <Badge variant={s.variant}>{s.label}</Badge>
              {order.eventDate && <span className="text-xs text-muted-foreground">{order.eventDate}</span>}
            </div>
            <p className="mt-1 truncate font-medium">{order.eventTitle ?? "공연"}</p>
            <p className="text-xs text-muted-foreground">좌석 {order.seatCount}석 · {won(order.amount)}</p>
          </div>
          <ChevronRight className="h-5 w-5 shrink-0 text-muted-foreground" />
        </CardContent>
      </Card>
    </button>
  );
}
