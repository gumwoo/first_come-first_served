"use client";

import { useEffect, useMemo, useState } from "react";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import { loadTossPayments } from "@tosspayments/payment-sdk";
import { CreditCard, Smartphone, Landmark, Clock, TimerOff } from "lucide-react";
import { useOrder } from "@/features/order/hooks/useOrder";
import * as orderApi from "@/features/order/api/order";
import { useEvent } from "@/features/event/hooks/useEvents";
import { useAuthStore } from "@/features/auth/store/authStore";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";

type Method = "card" | "easy" | "vbank";
const PROVIDERS = [
  { id: "kakaopay", label: "카카오페이" },
  { id: "naverpay", label: "네이버페이" },
  { id: "toss", label: "토스페이" },
  { id: "payco", label: "페이코" },
] as const;

function mmss(s: number) {
  const m = Math.floor(s / 60);
  return `${String(m).padStart(2, "0")}:${String(Math.max(0, s % 60)).padStart(2, "0")}`;
}

// Toss 결제창(실 PG 데모): 클라이언트 키가 설정되면 카드는 결제창 인증 경로를 사용.
const TOSS_CLIENT_KEY = process.env.NEXT_PUBLIC_TOSS_CLIENT_KEY;
const tossOrderId = (orderId: number) => `FLOWTICKET-ORDER-${orderId}`;

export default function PayPage() {
  const orderId = Number(useParams().id);
  const router = useRouter();
  const searchParams = useSearchParams();
  const token = useAuthStore((s) => s.accessToken);
  const { order, loading, error } = useOrder(orderId, token);
  const { data: event } = useEvent(order?.eventId ?? NaN);

  const [method, setMethod] = useState<Method>("card");
  const [provider, setProvider] = useState<string>("kakaopay");
  const [submitting, setSubmitting] = useState(false);
  const [failMsg, setFailMsg] = useState<string | null>(null);
  const [vbank, setVbank] = useState<orderApi.PaymentResult | null>(null);
  const [remain, setRemain] = useState<number | null>(null);

  // 결제 성공 시 완료 화면으로
  useEffect(() => {
    if (order?.status === "PAID") router.replace(`/orders/${orderId}/complete`);
  }, [order?.status, orderId, router]);

  // Toss 결제창 인증 후 리다이렉트 복귀(paymentKey 쿼리) → 서버 확정
  useEffect(() => {
    const paymentKey = searchParams.get("paymentKey");
    if (!paymentKey || !token) return;
    setSubmitting(true);
    orderApi
      .confirmPayment(orderId, paymentKey, token)
      .then((res) =>
        router.replace(`/orders/${orderId}/${res.orderStatus === "PAID" ? "complete" : "failed"}`)
      )
      .catch(() => {
        setFailMsg("결제 승인에 실패했습니다. 다시 시도해 주세요.");
        setSubmitting(false);
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchParams, token, orderId]);

  // 제한시간 카운트다운(주문 expiresAt 기준)
  useEffect(() => {
    if (!order) return;
    const tick = () => setRemain(Math.floor((new Date(order.expiresAt).getTime() - Date.now()) / 1000));
    tick();
    const t = setInterval(tick, 1000);
    return () => clearInterval(t);
  }, [order]);

  const expired = remain !== null && remain <= 0 && order?.status !== "PAID";

  const pay = async () => {
    if (!order || submitting) return;
    setSubmitting(true);
    setFailMsg(null);
    try {
      // 카드·간편결제 + Toss 클라이언트 키가 있으면 실 PG 결제창 인증 경로(BE-5).
      // Toss "카드" 결제창은 카카오페이·토스페이 등 간편결제도 함께 제공하므로 두 탭 모두 여기로 라우팅한다.
      if ((method === "card" || method === "easy") && TOSS_CLIENT_KEY) {
        const toss = await loadTossPayments(TOSS_CLIENT_KEY);
        const origin = window.location.origin;
        await toss.requestPayment("카드", {
          amount: order.amount,
          orderId: tossOrderId(orderId),
          orderName: event?.title ?? "FlowTicket 예매",
          successUrl: `${origin}/orders/${orderId}/pay`,
          failUrl: `${origin}/orders/${orderId}/pay`,
        });
        return; // 결제창으로 리다이렉트 — 복귀 시 useEffect가 확정
      }

      const key = crypto.randomUUID();
      const res = await orderApi.payOrder(
        orderId,
        { method, provider: method === "easy" ? provider : undefined, idempotencyKey: key },
        token
      );
      if (method === "vbank") {
        setVbank(res); // 입금 대기로 전환
      } else if (res.orderStatus === "PAID") {
        router.replace(`/orders/${orderId}/complete`);
      } else {
        router.replace(`/orders/${orderId}/failed`);
      }
    } catch (e) {
      // Toss 결제창은 사용자가 닫으면(취소) 예외를 던진다 — 실패가 아니므로 조용히 결제 화면 유지.
      const code = (e as { code?: string })?.code;
      if (code === "USER_CANCEL" || code === "PAY_PROCESS_CANCELED") return;
      setFailMsg("결제 처리 중 오류가 발생했습니다. 다시 시도해 주세요.");
    } finally {
      setSubmitting(false);
    }
  };

  const confirmDeposit = async () => {
    try {
      await orderApi.confirmVbankDeposit(orderId, token);
      router.replace(`/orders/${orderId}/complete`);
    } catch {
      setFailMsg("입금 확인에 실패했습니다.");
    }
  };

  const grades = useMemo(() => {
    const m = new Map<string, number>();
    (order?.items ?? []).forEach((i) => m.set(i.grade, (m.get(i.grade) ?? 0) + 1));
    return [...m.entries()];
  }, [order]);

  if (loading) return <main className="mx-auto max-w-5xl p-10 text-center text-muted-foreground">결제 정보를 불러오는 중…</main>;
  if (error || !order) return <main className="mx-auto max-w-5xl p-10 text-center text-destructive">주문을 찾을 수 없습니다.</main>;

  if (expired) {
    return (
      <main className="mx-auto max-w-md px-4 py-16 text-center">
        <TimerOff className="mx-auto mb-3 h-12 w-12 text-muted-foreground" />
        <h1 className="text-xl font-bold">결제 시간이 만료되었습니다</h1>
        <p className="mt-2 text-sm text-muted-foreground">제한 시간 내 결제가 완료되지 않아 선점이 해제됩니다.</p>
        <div className="mt-5 flex flex-col gap-2">
          <Link href={`/events/${order.eventId}`}><Button className="w-full">공연 상세로</Button></Link>
          <Link href="/search"><Button variant="ghost" className="w-full">다른 공연 보기</Button></Link>
        </div>
      </main>
    );
  }

  return (
    <main className="mx-auto max-w-5xl px-4 py-8">
      <h1 className="text-2xl font-bold">결제</h1>
      <p className="mt-1 text-sm text-muted-foreground">{event?.title ?? "공연"} · 제한 시간 내 결제를 완료해 주세요.</p>

      <div className="mt-6 grid gap-6 lg:grid-cols-[1fr_320px]">
        {/* 결제 수단 */}
        <div className="space-y-4">
          {vbank ? (
            <Card>
              <CardContent className="space-y-3 pt-6">
                <p className="flex items-center gap-1.5 font-semibold"><Landmark className="h-4 w-4 text-primary" /> 입금 대기</p>
                <p className="text-sm text-muted-foreground">아래 가상계좌로 입금하면 예매가 확정됩니다.</p>
                <div className="rounded-md bg-muted/40 p-4 text-sm">
                  <p>가상계좌 <b className="text-base">{vbank.vbankAccount}</b></p>
                  <p className="mt-1 text-muted-foreground">입금 금액 {order.amount.toLocaleString()}원</p>
                  {vbank.depositDeadline && (
                    <p className="text-muted-foreground">입금 기한 {new Date(vbank.depositDeadline).toLocaleString()}</p>
                  )}
                </div>
                <Button className="w-full" onClick={confirmDeposit}>입금 확인(개발용 트리거)</Button>
                <p className="text-xs text-muted-foreground">실서비스에서는 PG 웹훅으로 자동 확인됩니다.</p>
              </CardContent>
            </Card>
          ) : (
            <Card>
              <CardContent className="pt-6">
                <div className="grid grid-cols-3 gap-2">
                  {([
                    { m: "card", label: "카드", icon: CreditCard },
                    { m: "easy", label: "간편결제", icon: Smartphone },
                    { m: "vbank", label: "무통장입금", icon: Landmark },
                  ] as const).map(({ m, label, icon: Icon }) => (
                    <button key={m} onClick={() => setMethod(m)}
                      className={`flex flex-col items-center gap-1 rounded-lg border py-4 text-sm ${
                        method === m ? "border-primary bg-primary/5 font-semibold text-primary" : "border-border text-muted-foreground"}`}>
                      <Icon className="h-5 w-5" /> {label}
                    </button>
                  ))}
                </div>

                {method === "easy" && (
                  <div className="mt-4">
                    <p className="mb-2 text-sm font-medium">간편결제 수단</p>
                    <div className="grid grid-cols-2 gap-2">
                      {PROVIDERS.map((p) => (
                        <button key={p.id} onClick={() => setProvider(p.id)}
                          className={`rounded-md border py-2 text-sm ${
                            provider === p.id ? "border-primary bg-primary/5 font-medium text-primary" : "border-border"}`}>
                          {p.label}
                        </button>
                      ))}
                    </div>
                  </div>
                )}
                {method === "vbank" && (
                  <p className="mt-4 text-sm text-muted-foreground">가상계좌를 발급받아 입금하면 예매가 확정됩니다.</p>
                )}

                <label className="mt-4 flex items-center gap-2 text-xs text-muted-foreground">
                  <input type="checkbox" defaultChecked /> 결제 진행 및 개인정보 제공에 동의합니다.
                </label>
              </CardContent>
            </Card>
          )}
        </div>

        {/* 요약 + 타이머 + 결제 */}
        <aside className="space-y-4">
          <Card>
            <CardContent className="pt-5">
              <div className="flex items-center justify-between">
                <span className="flex items-center gap-1.5 text-sm text-muted-foreground"><Clock className="h-4 w-4" /> 결제 제한 시간</span>
                <span className="text-xl font-bold text-primary">{remain !== null ? mmss(remain) : "--:--"}</span>
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardContent className="space-y-2 pt-5 text-sm">
              <p className="font-medium">예매 정보</p>
              <p className="line-clamp-2">{event?.title ?? "-"}</p>
              {grades.map(([g, n]) => (
                <div key={g} className="flex justify-between text-xs text-muted-foreground">
                  <span>{g}석</span><span>{n}매</span>
                </div>
              ))}
              <div className="flex justify-between border-t border-border pt-2 font-semibold">
                <span>총 결제 금액</span>
                <span className="text-primary">{order.amount.toLocaleString()}원</span>
              </div>
            </CardContent>
          </Card>

          {failMsg && (
            <p className="rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-center text-xs font-medium text-destructive">{failMsg}</p>
          )}
          {!vbank && (
            <Button className="w-full" disabled={submitting} onClick={pay}>
              {submitting ? "결제 처리 중…" : `${order.amount.toLocaleString()}원 결제하기`}
            </Button>
          )}
          <Button variant="ghost" className="w-full" onClick={() => router.back()}>← 이전으로</Button>
        </aside>
      </div>
    </main>
  );
}
