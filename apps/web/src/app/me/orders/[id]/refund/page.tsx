"use client";

import { useState } from "react";
import { useParams, useRouter } from "next/navigation";
import Link from "next/link";
import { AlertTriangle, CheckCircle2 } from "lucide-react";
import { useMyOrder } from "@/features/order/hooks/useMyOrders";
import * as orderApi from "@/features/order/api/order";
import { useAuthStore } from "@/features/auth/store/authStore";
import { Card, CardContent } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";

// 취소 수수료 정책(백엔드 RefundPolicy 기본값 미러 — 서버가 최종 권위, FE는 예상 안내).
const FEE_TIERS = [
  { label: "공연 8일 전까지", rate: 0 },
  { label: "3~7일 전", rate: 10 },
  { label: "1~2일 전", rate: 30 },
  { label: "공연 당일·이후", rate: null }, // 환불 불가
] as const;

const REASONS = ["단순 변심", "일정 변경", "중복 예매", "기타"];
const won = (n: number) => `${n.toLocaleString()}원`;

/** 공연일까지 남은 일수(date-only). */
function daysUntil(eventDate: string | null): number | null {
  if (!eventDate) return null;
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const ev = new Date(eventDate + "T00:00:00");
  return Math.round((ev.getTime() - today.getTime()) / 86_400_000);
}

/** 환불 예상 견적(서버 정책 미러). */
function estimate(amount: number, eventDate: string | null) {
  const d = daysUntil(eventDate);
  if (d == null) return { rate: 0, fee: 0, refund: amount, refundable: true };
  if (d <= 0) return { rate: 0, fee: 0, refund: 0, refundable: false };
  const rate = d >= 8 ? 0 : d >= 3 ? 10 : 30;
  const fee = Math.floor((amount * rate) / 100);
  return { rate, fee, refund: amount - fee, refundable: true };
}

export default function RefundPage() {
  const orderId = Number(useParams().id);
  const router = useRouter();
  const token = useAuthStore((s) => s.accessToken);
  const { data: order, isLoading, isError } = useMyOrder(Number.isFinite(orderId) ? orderId : null);

  const [reason, setReason] = useState(REASONS[0]);
  const [agreed, setAgreed] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState<orderApi.RefundResult | null>(null);
  const [failMsg, setFailMsg] = useState<string | null>(null);

  if (isLoading) return <main className="mx-auto max-w-2xl p-10 text-center text-muted-foreground">불러오는 중…</main>;
  if (isError || !order) return <main className="mx-auto max-w-2xl p-10 text-center text-destructive">예매를 찾을 수 없습니다.</main>;

  const q = estimate(order.amount, order.eventDate);
  const cancellable = order.status === "PAID" && q.refundable;

  const submit = async () => {
    setSubmitting(true);
    setFailMsg(null);
    try {
      const res = await orderApi.requestRefund(orderId, { reason, idempotencyKey: crypto.randomUUID() }, token);
      setDone(res);
    } catch {
      setFailMsg("환불 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");
    } finally {
      setSubmitting(false);
      setConfirming(false);
    }
  };

  // 완료 화면
  if (done) {
    return (
      <main className="mx-auto max-w-md px-4 py-16 text-center">
        <CheckCircle2 className="mx-auto mb-3 h-12 w-12 text-primary" />
        <h1 className="text-xl font-bold">예매를 취소했습니다</h1>
        <p className="mt-2 text-sm text-muted-foreground">
          환불 예정 금액 <b className="text-foreground">{won(done.refundAmount)}</b>
          {done.fee > 0 && <> (수수료 {won(done.fee)} 차감)</>}
        </p>
        <div className="mt-6 flex flex-col gap-2">
          <Link href="/me/orders"><Button className="w-full">예매 내역으로</Button></Link>
        </div>
      </main>
    );
  }

  return (
    <main className="mx-auto max-w-2xl px-4 py-8">
      <h1 className="text-2xl font-bold">예매 취소 / 환불</h1>
      <p className="mt-1 text-sm text-muted-foreground">취소 수수료를 확인하고 환불을 신청하세요.</p>

      <div className="mt-6 space-y-4">
        {/* 예매 요약 */}
        <Card>
          <CardContent className="space-y-1 pt-5 text-sm">
            <p className="font-semibold">{order.eventTitle}</p>
            {order.venue && <p className="text-muted-foreground">{order.venue}</p>}
            {order.eventDate && <p className="text-muted-foreground">공연일 {order.eventDate}</p>}
            <div className="mt-2 flex justify-between border-t border-border pt-2">
              <span className="text-muted-foreground">좌석 {order.items.length}석</span>
              <span className="font-semibold">결제 {won(order.amount)}</span>
            </div>
          </CardContent>
        </Card>

        {/* 수수료율 표 */}
        <Card>
          <CardContent className="pt-5">
            <p className="mb-2 text-sm font-medium">취소 수수료 안내</p>
            <table className="w-full text-sm">
              <tbody>
                {FEE_TIERS.map((t) => (
                  <tr key={t.label} className="border-b border-border/60 last:border-0">
                    <td className="py-1.5 text-muted-foreground">{t.label}</td>
                    <td className="py-1.5 text-right font-medium">
                      {t.rate == null ? "환불 불가" : `${t.rate}%`}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </CardContent>
        </Card>

        {/* 환불 예정 금액 */}
        <Card>
          <CardContent className="space-y-1 pt-5 text-sm">
            <div className="flex justify-between"><span className="text-muted-foreground">결제 금액</span><span>{won(order.amount)}</span></div>
            <div className="flex justify-between"><span className="text-muted-foreground">취소 수수료 ({q.rate}%)</span><span>- {won(q.fee)}</span></div>
            <div className="flex justify-between border-t border-border pt-2 text-base font-bold">
              <span>환불 예정 금액</span><span className="text-primary">{won(q.refund)}</span>
            </div>
            <p className="pt-1 text-xs text-muted-foreground">* 예상 금액이며 실제 환불액은 신청 시점 기준으로 확정됩니다.</p>
          </CardContent>
        </Card>

        {!cancellable && (
          <p className="flex items-center gap-2 rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive">
            <AlertTriangle className="h-4 w-4" />
            {order.status !== "PAID" ? "취소할 수 없는 예매입니다." : "공연 당일 이후에는 환불이 불가합니다."}
          </p>
        )}

        {cancellable && (
          <>
            {/* 취소 사유 */}
            <Card>
              <CardContent className="pt-5">
                <p className="mb-2 text-sm font-medium">취소 사유</p>
                <div className="grid grid-cols-2 gap-2">
                  {REASONS.map((r) => (
                    <button key={r} onClick={() => setReason(r)}
                      className={`rounded-md border py-2 text-sm ${
                        reason === r ? "border-primary bg-primary/5 font-medium text-primary" : "border-border text-muted-foreground"}`}>
                      {r}
                    </button>
                  ))}
                </div>
                <label className="mt-4 flex items-center gap-2 text-xs text-muted-foreground">
                  <input type="checkbox" checked={agreed} onChange={(e) => setAgreed(e.target.checked)} />
                  취소 수수료 및 환불 규정을 확인했으며 이에 동의합니다.
                </label>
              </CardContent>
            </Card>

            {failMsg && (
              <p className="rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-center text-xs font-medium text-destructive">{failMsg}</p>
            )}

            <Button variant="destructive" className="w-full" disabled={!agreed} onClick={() => setConfirming(true)}>
              환불 신청
            </Button>
          </>
        )}

        <Button variant="ghost" className="w-full" onClick={() => router.back()}>← 이전으로</Button>
      </div>

      <Dialog
        open={confirming}
        onClose={() => setConfirming(false)}
        title="예매를 취소할까요?"
        footer={
          <div className="flex gap-2">
            <Button variant="ghost" className="flex-1" onClick={() => setConfirming(false)} disabled={submitting}>돌아가기</Button>
            <Button variant="destructive" className="flex-1" onClick={submit} disabled={submitting}>
              {submitting ? "처리 중…" : "취소 확정"}
            </Button>
          </div>
        }
      >
        <p className="text-sm text-muted-foreground">
          취소 후에는 되돌릴 수 없습니다. 환불 예정 금액은 <b className="text-foreground">{won(q.refund)}</b>
          {q.fee > 0 && <> (수수료 {won(q.fee)} 차감)</>}입니다.
        </p>
      </Dialog>
    </main>
  );
}
