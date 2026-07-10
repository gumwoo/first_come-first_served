"use client";

import { useParams } from "next/navigation";
import Link from "next/link";
import { QRCodeSVG } from "qrcode.react";
import { CheckCircle2, MapPin, Calendar, Ticket } from "lucide-react";
import { useOrder } from "@/features/order/hooks/useOrder";
import { useEvent } from "@/features/event/hooks/useEvents";
import { useAuthStore } from "@/features/auth/store/authStore";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";

export default function CompletePage() {
  const orderId = Number(useParams().id);
  const token = useAuthStore((s) => s.accessToken);
  const { order, loading } = useOrder(orderId, token);
  const { data: event } = useEvent(order?.eventId ?? NaN);

  if (loading) return <main className="mx-auto max-w-lg p-10 text-center text-muted-foreground">불러오는 중…</main>;
  if (!order) return <main className="mx-auto max-w-lg p-10 text-center text-destructive">주문을 찾을 수 없습니다.</main>;

  const paid = order.status === "PAID";
  // QR: 예매번호 기반(추후 서버 HMAC 서명 토큰으로 대체). 위조 방지는 BE-5에서.
  const ticket = `FLOWTICKET-ORDER-${order.orderId}`;

  return (
    <main className="mx-auto max-w-lg px-4 py-10">
      <div className="text-center">
        <CheckCircle2 className="mx-auto mb-2 h-12 w-12 text-primary" />
        <h1 className="text-2xl font-bold">예매가 완료되었습니다</h1>
        <p className="mt-1 text-sm text-muted-foreground">예매번호 #{order.orderId}</p>
      </div>

      {/* QR 모바일 티켓 */}
      <Card className="mt-6">
        <CardContent className="flex flex-col items-center gap-3 pt-6">
          <p className="flex items-center gap-1.5 text-sm font-medium"><Ticket className="h-4 w-4 text-primary" /> 모바일 티켓</p>
          <div className="rounded-lg bg-white p-4">
            {paid ? (
              <QRCodeSVG value={ticket} size={168} />
            ) : (
              <div className="flex h-[168px] w-[168px] items-center justify-center text-xs text-muted-foreground">결제 확정 후 발급</div>
            )}
          </div>
          <p className="text-xs text-muted-foreground">입장 시 이 QR을 제시해 주세요.</p>
        </CardContent>
      </Card>

      {/* 예매 정보 */}
      <Card className="mt-4 bg-muted/30">
        <CardContent className="space-y-2 pt-5 text-sm">
          <p className="text-base font-semibold">{event?.title ?? "-"}</p>
          <p className="flex items-center gap-1.5 text-muted-foreground"><MapPin className="h-4 w-4" /> {event?.venue ?? "-"}</p>
          <p className="flex items-center gap-1.5 text-muted-foreground"><Calendar className="h-4 w-4" /> {event?.startDate ?? "-"}</p>
          <div className="border-t border-border pt-2">
            {order.items.map((i) => (
              <div key={i.seatId} className="flex justify-between text-xs">
                <span>{i.grade}석</span><span>{i.price.toLocaleString()}원</span>
              </div>
            ))}
            <div className="mt-1 flex justify-between font-semibold">
              <span>총 결제 금액</span><span className="text-primary">{order.amount.toLocaleString()}원</span>
            </div>
          </div>
        </CardContent>
      </Card>

      <Card className="mt-4 bg-primary/5">
        <CardContent className="pt-5 text-xs text-muted-foreground">
          <ul className="space-y-1">
            <li>· 공연 당일 신분 확인이 필요할 수 있습니다.</li>
            <li>· 취소/환불은 마이페이지의 환불 규정에 따릅니다.</li>
          </ul>
        </CardContent>
      </Card>

      <div className="mt-5 flex justify-center gap-2">
        <Link href="/"><Button variant="outline">메인으로</Button></Link>
        <Link href={`/events/${order.eventId}`}><Button>공연 상세로</Button></Link>
      </div>
    </main>
  );
}
