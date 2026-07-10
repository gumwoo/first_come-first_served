"use client";

import { useParams } from "next/navigation";
import Link from "next/link";
import { XCircle } from "lucide-react";
import { useOrder } from "@/features/order/hooks/useOrder";
import { useAuthStore } from "@/features/auth/store/authStore";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";

export default function FailedPage() {
  const orderId = Number(useParams().id);
  const token = useAuthStore((s) => s.accessToken);
  const { order } = useOrder(orderId, token);

  return (
    <main className="mx-auto max-w-md px-4 py-16">
      <Card>
        <CardContent className="p-8 text-center">
          <XCircle className="mx-auto mb-3 h-12 w-12 text-destructive" />
          <h1 className="text-xl font-bold">예매를 완료하지 못했습니다</h1>
          <p className="mt-2 text-sm text-muted-foreground">
            결제가 승인되지 않았습니다(승인 거절/오류/시간 초과). 좌석은 제한 시간 내라면 유지되니
            다른 결제수단으로 다시 시도할 수 있습니다.
          </p>
          {order && (
            <p className="mt-2 text-xs text-muted-foreground">총 {order.amount.toLocaleString()}원 · 예매번호 #{order.orderId}</p>
          )}
          <div className="mt-5 flex flex-col gap-2">
            <Link href={`/orders/${orderId}/pay`}><Button className="w-full">다시 시도</Button></Link>
            {order && (
              <Link href={`/events/${order.eventId}`}><Button variant="outline" className="w-full">공연 상세로</Button></Link>
            )}
            <Link href="/search"><Button variant="ghost" className="w-full">다른 공연 보기</Button></Link>
          </div>
        </CardContent>
      </Card>
    </main>
  );
}
