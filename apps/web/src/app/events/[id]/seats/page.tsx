"use client";

import { useEffect, useMemo, useState } from "react";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import { Clock, Users, CalendarClock, CheckCircle2, MapPin, Info } from "lucide-react";
import { useSeats } from "@/features/seat/hooks/useSeats";
import * as seatApi from "@/features/seat/api/seat";
import { createOrder } from "@/features/order/api/order";
import { useEvent } from "@/features/event/hooks/useEvents";
import { useAuthStore } from "@/features/auth/store/authStore";
import { ApiError } from "@/lib/apiClient";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";

const MAX_PER_USER = 4;
const SELECT_SECONDS = 300; // 좌석 선택 제한(5분)
const PER_ROW = 10; // 표시용 열 분할 단위(백엔드엔 열 데이터가 없어 seatCol을 프론트에서 나눔)

// 등급 표시 순서(무대 앞=최고가 → 뒤=최저가)와 구역 밴드 색상.
const GRADE_ORDER: Record<string, number> = { VIP: 0, R: 1, S: 2, A: 3 };
const GRADE_BAND: Record<string, string> = {
  VIP: "border-purple-200 bg-purple-50",
  R: "border-rose-200 bg-rose-50",
  S: "border-emerald-200 bg-emerald-50",
  A: "border-sky-200 bg-sky-50",
};

function mmss(s: number) {
  const m = Math.floor(s / 60);
  return `${String(m).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
}

function chunk<T>(arr: T[], size: number): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < arr.length; i += size) out.push(arr.slice(i, i + size));
  return out;
}

export default function SeatSelectPage() {
  const id = Number(useParams().id);
  const router = useRouter();
  const queueToken = useSearchParams().get("qt") ?? "";
  const accessToken = useAuthStore((s) => s.accessToken);
  const { map, loading, error, refresh } = useSeats(id);
  const { data: event } = useEvent(id);

  const [selected, setSelected] = useState<number[]>([]);
  const [held, setHeld] = useState<seatApi.HoldResult | null>(null);
  const [remain, setRemain] = useState(SELECT_SECONDS);
  const [submitting, setSubmitting] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [goingPay, setGoingPay] = useState(false);
  const [heldRemain, setHeldRemain] = useState(0);

  // 선택 제한 타이머
  useEffect(() => {
    if (held) return;
    if (remain <= 0) {
      router.replace(`/events/${id}/seats/expired`);
      return;
    }
    const t = setTimeout(() => setRemain((r) => r - 1), 1000);
    return () => clearTimeout(t);
  }, [remain, held, id, router]);

  // 선점 만료 카운트다운(hold.expiresAt 기준). 0이면 선점 해제 → 만료 화면.
  useEffect(() => {
    if (!held) return;
    const tick = () => {
      const left = Math.floor((new Date(held.expiresAt).getTime() - Date.now()) / 1000);
      setHeldRemain(Math.max(0, left));
      if (left <= 0) router.replace(`/events/${id}/seats/expired`);
    };
    tick();
    const t = setInterval(tick, 1000);
    return () => clearInterval(t);
  }, [held, id, router]);

  // 가격 desc(무대 앞→뒤) 정렬된 등급
  const grades = useMemo(
    () => (map ? [...map.grades].sort((a, b) => (GRADE_ORDER[a.grade] ?? 9) - (GRADE_ORDER[b.grade] ?? 9)) : []),
    [map]
  );

  const priceByGrade = useMemo(() => {
    const m = new Map<string, number>();
    grades.forEach((g) => m.set(g.grade, g.price));
    return m;
  }, [grades]);

  const seatById = useMemo(() => {
    const m = new Map<number, seatApi.SeatInfo>();
    map?.seats.forEach((s) => m.set(s.id, s));
    return m;
  }, [map]);

  // 등급별 좌석을 seatCol 순으로(열 분할용)
  const seatsByGrade = useMemo(() => {
    const m = new Map<string, seatApi.SeatInfo[]>();
    map?.seats.forEach((s) => {
      const list = m.get(s.grade) ?? [];
      list.push(s);
      m.set(s.grade, list);
    });
    m.forEach((list) => list.sort((a, b) => (a.seatCol ?? 0) - (b.seatCol ?? 0)));
    return m;
  }, [map]);

  const total = selected.reduce((sum, sid) => {
    const s = seatById.get(sid);
    return sum + (s ? priceByGrade.get(s.grade) ?? 0 : 0);
  }, 0);

  const toggle = (s: seatApi.SeatInfo) => {
    if (s.status !== "AVAILABLE") return;
    setErrorMsg(null);
    setSelected((cur) =>
      cur.includes(s.id)
        ? cur.filter((x) => x !== s.id)
        : cur.length >= MAX_PER_USER
        ? cur
        : [...cur, s.id]
    );
  };

  const complete = async () => {
    if (selected.length === 0 || submitting) return;
    setSubmitting(true);
    setErrorMsg(null);
    try {
      const result = await seatApi.holdSeats(id, selected, queueToken, accessToken);
      setHeld(result);
    } catch (e) {
      if (e instanceof ApiError && e.code === "SOLD_OUT") {
        router.replace(`/events/${id}/sold-out`);
      } else if (e instanceof ApiError && e.code === "QUEUE_NOT_ADMITTED") {
        router.replace(`/events/${id}/queue`);
      } else if (e instanceof ApiError && e.code === "MAX_PER_USER_EXCEEDED") {
        // 이미 보유한 선점 + 이번 선택이 한도를 넘음 — 선택은 유지하고 안내만.
        setErrorMsg(e.message || `1인 최대 ${MAX_PER_USER}매까지 예매할 수 있습니다.`);
      } else {
        // HOLD_EXPIRED / 이미 선점된 좌석 등 — 최신 재고로 갱신하고 선택 초기화 후 안내.
        setErrorMsg(e instanceof ApiError ? e.message : "선점에 실패했습니다. 다시 시도해 주세요.");
        await refresh();
        setSelected([]);
      }
    } finally {
      setSubmitting(false);
    }
  };

  const cancelHold = async () => {
    if (!held) return;
    try {
      await seatApi.releaseHold(held.holdId, accessToken);
    } catch {
      /* 이미 만료/해제됐을 수 있음 — 무시하고 좌석 선택으로 복귀 */
    }
    // 같은 경로라 router 이동으론 상태가 안 풀림 → 상태를 직접 초기화하고 최신 재고 반영.
    setHeld(null);
    setSelected([]);
    setErrorMsg(null);
    setRemain(SELECT_SECONDS);
    await refresh();
  };

  const goPay = async () => {
    if (!held || goingPay) return;
    setGoingPay(true);
    setErrorMsg(null);
    try {
      const order = await createOrder(held.holdId, accessToken);
      router.push(`/orders/${order.orderId}/pay`);
    } catch {
      setErrorMsg("주문 생성에 실패했습니다. 다시 시도해 주세요.");
      setGoingPay(false);
    }
  };

  if (loading) return <main className="mx-auto max-w-6xl p-10 text-center text-muted-foreground">좌석 정보를 불러오는 중…</main>;
  if (error || !map) return <main className="mx-auto max-w-6xl p-10 text-center text-destructive">좌석 정보를 불러올 수 없습니다.</main>;

  // 선점 완료 → 결제로
  if (held) {
    const heldSeats = held.seatIds.map((sid) => seatById.get(sid)).filter(Boolean) as seatApi.SeatInfo[];
    const urgent = heldRemain <= 60;
    return (
      <main className="mx-auto max-w-md px-4 py-10">
        <Card>
          <CardContent className="pt-6">
            <div className="text-center">
              <CheckCircle2 className="mx-auto mb-2 h-11 w-11 text-primary" />
              <h1 className="text-xl font-bold">좌석 선점 완료!</h1>
              <p className="mt-1 text-sm text-muted-foreground">제한 시간 내 결제를 완료해 주세요.</p>
            </div>

            {/* 결제 제한 카운트다운 — 선착순 핵심 */}
            <div className={`mt-5 flex items-center justify-between rounded-lg border px-4 py-3 ${
              urgent ? "border-destructive/40 bg-destructive/10" : "border-primary/30 bg-primary/5"}`}>
              <span className="flex items-center gap-1.5 text-sm text-muted-foreground">
                <Clock className="h-4 w-4" /> 결제 제한 시간
              </span>
              <span className={`text-2xl font-bold tabular-nums ${urgent ? "text-destructive" : "text-primary"}`}>
                {mmss(heldRemain)}
              </span>
            </div>

            {/* 공연 + 좌석 요약 */}
            <div className="mt-4 flex gap-3">
              <div className="h-24 w-16 shrink-0 overflow-hidden rounded bg-muted">
                {event?.posterUrl && <img src={event.posterUrl} alt="" className="h-full w-full object-cover" />}
              </div>
              <div className="min-w-0 text-sm">
                <p className="font-semibold leading-tight">{event?.title ?? "공연"}</p>
                {event?.venue && (
                  <p className="mt-1 flex items-center gap-1 text-xs text-muted-foreground">
                    <MapPin className="h-3 w-3" /> {event.venue}
                  </p>
                )}
                <p className="mt-1 text-xs text-muted-foreground">선택 좌석 {heldSeats.length}석</p>
              </div>
            </div>

            <div className="mt-3 space-y-1 rounded-md bg-muted/40 p-3 text-sm">
              {heldSeats.map((s) => (
                <div key={s.id} className="flex justify-between text-xs">
                  <span>{s.grade}석 {s.seatCol}번</span>
                  <span>{(priceByGrade.get(s.grade) ?? 0).toLocaleString()}원</span>
                </div>
              ))}
              <div className="flex justify-between border-t border-border pt-2 font-semibold">
                <span>총 결제 금액</span>
                <span className="text-primary">{held.total.toLocaleString()}원</span>
              </div>
            </div>

            <p className="mt-2 text-center text-xs text-muted-foreground">미결제 시 선점이 자동 해제됩니다.</p>
            {errorMsg && <p className="mt-2 text-center text-xs text-destructive">{errorMsg}</p>}

            <div className="mt-4 space-y-2">
              <Button className="w-full" disabled={goingPay} onClick={goPay}>
                {goingPay ? "주문 생성 중…" : `${held.total.toLocaleString()}원 결제하기`}
              </Button>
              <div className="flex justify-center gap-2">
                <Button variant="outline" size="sm" onClick={cancelHold}>선점 취소</Button>
                <Link href={`/events/${id}`}><Button variant="ghost" size="sm">공연 상세로</Button></Link>
              </div>
            </div>
          </CardContent>
        </Card>
      </main>
    );
  }

  const seatBtnClass = (s: seatApi.SeatInfo) => {
    const sel = selected.includes(s.id);
    const avail = s.status === "AVAILABLE";
    return `h-7 w-7 rounded text-[10px] transition ${
      sel ? "bg-primary text-primary-foreground"
      : avail ? "bg-white hover:bg-primary/20 border border-border"
      : "cursor-not-allowed bg-muted/40 text-muted-foreground/40 line-through"}`;
  };

  return (
    <main className="mx-auto max-w-6xl px-4 py-8">
      <div className="flex items-end gap-3">
        <h1 className="text-2xl font-bold">좌석 선택</h1>
        <p className="pb-1 text-sm text-muted-foreground">{event?.title ?? "공연"}</p>
      </div>
      <p className="mt-1 flex flex-wrap gap-4 text-sm text-muted-foreground">
        <span>무대와 가까운 앞줄(VIP)부터 배치돼요. 원하는 좌석을 선택해 주세요.</span>
        <span className="flex items-center gap-1"><Users className="h-4 w-4" /> 1인 최대 {MAX_PER_USER}매</span>
        <span>현재 선택 {selected.length}매</span>
      </p>

      <div className="mt-6 grid gap-6 lg:grid-cols-[1fr_320px]">
        {/* 극장식 좌석맵: STAGE 아래로 VIP→R→S→A, 등급마다 열×번호 */}
        <Card>
          <CardContent className="p-5">
            <div className="mx-auto mb-5 w-2/3 rounded-t-[2rem] bg-foreground/90 py-2.5 text-center text-sm font-semibold tracking-[0.3em] text-primary-foreground">
              STAGE
            </div>

            <div className="space-y-3">
              {grades.map((g) => {
                const rows = chunk(seatsByGrade.get(g.grade) ?? [], PER_ROW);
                return (
                  <div key={g.grade} className={`rounded-lg border p-3 ${GRADE_BAND[g.grade] ?? "bg-muted/30"}`}>
                    <div className="mb-2 flex items-center justify-between text-sm">
                      <span className="font-semibold">{g.grade}석 · {g.price.toLocaleString()}원</span>
                      <span className="text-muted-foreground">잔여 {g.available} / {g.total}</span>
                    </div>
                    <div className="space-y-1.5">
                      {rows.map((row, ri) => (
                        <div key={ri} className="flex items-center justify-center gap-1.5">
                          <span className="w-14 shrink-0 text-right text-[10px] text-muted-foreground">{g.grade} {ri + 1}열</span>
                          {row.map((s) => (
                            <button key={s.id} onClick={() => toggle(s)} disabled={s.status !== "AVAILABLE"}
                              title={`${g.grade} ${ri + 1}열 ${s.seatCol}번`} className={seatBtnClass(s)}>
                              {s.seatCol}
                            </button>
                          ))}
                        </div>
                      ))}
                    </div>
                  </div>
                );
              })}
            </div>

            <div className="mt-4 flex justify-center gap-4 text-xs text-muted-foreground">
              <span className="flex items-center gap-1"><i className="inline-block h-3 w-3 rounded border border-border bg-white" /> 선택 가능</span>
              <span className="flex items-center gap-1"><i className="inline-block h-3 w-3 rounded bg-primary" /> 선택 좌석</span>
              <span className="flex items-center gap-1"><i className="inline-block h-3 w-3 rounded bg-muted/40" /> 판매 완료</span>
            </div>
            <p className="mt-2 flex items-center justify-center gap-1 text-xs text-muted-foreground">
              <Info className="h-3.5 w-3.5" /> 열·번호는 표시용이며 실제 공연장 배치와 다를 수 있습니다.
            </p>
          </CardContent>
        </Card>

        {/* 우측: 예매정보 + 타이머 + 합계 + 안내 */}
        <aside className="space-y-4">
          <Card className="bg-muted/30">
            <CardContent className="pt-5 text-sm">
              <p className="mb-2 flex items-center gap-1.5 font-medium"><CalendarClock className="h-4 w-4 text-primary" /> 예매 정보</p>
              <div className="flex gap-3">
                <div className="h-24 w-[72px] shrink-0 overflow-hidden rounded bg-muted">
                  {event?.posterUrl && (
                    // eslint-disable-next-line @next/next/no-img-element
                    <img src={event.posterUrl} alt="" className="h-full w-full object-cover" />
                  )}
                </div>
                <div className="space-y-1">
                  <p className="line-clamp-2 font-medium">{event?.title ?? "-"}</p>
                  <p className="flex items-center gap-1 text-xs text-muted-foreground"><MapPin className="h-3.5 w-3.5" /> {event?.venue ?? "-"}</p>
                  <p className="text-xs text-muted-foreground">일시 {event?.startDate ?? "-"}</p>
                </div>
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardContent className="pt-5">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-1.5 text-sm text-muted-foreground"><Clock className="h-4 w-4" /> 남은 선택 시간</div>
                <span className="text-xl font-bold text-primary">{mmss(remain)}</span>
              </div>
              <p className="mt-1 text-xs text-muted-foreground">선택 시간이 지나면 좌석이 자동 해제됩니다.</p>
            </CardContent>
          </Card>

          <Card>
            <CardContent className="space-y-2 pt-5 text-sm">
              <p className="font-medium">선택 좌석</p>
              {selected.length === 0 && <p className="text-xs text-muted-foreground">좌석을 선택해 주세요.</p>}
              {selected.map((sid) => {
                const s = seatById.get(sid);
                return s ? (
                  <div key={sid} className="flex justify-between text-xs">
                    <span>{s.grade}석 {s.seatCol}번</span>
                    <span>{(priceByGrade.get(s.grade) ?? 0).toLocaleString()}원</span>
                  </div>
                ) : null;
              })}
              {selected.length > 0 && (
                <div className="flex justify-between text-xs text-muted-foreground">
                  <span>수량</span><span>{selected.length}매</span>
                </div>
              )}
              <div className="flex justify-between border-t border-border pt-2 font-semibold">
                <span>총 결제 금액</span>
                <span className="text-primary">{total.toLocaleString()}원</span>
              </div>
            </CardContent>
          </Card>

          <Card className="bg-primary/5">
            <CardContent className="pt-5 text-xs text-muted-foreground">
              <p className="mb-1.5 flex items-center gap-1.5 font-medium text-primary"><Info className="h-3.5 w-3.5" /> 좌석 선택 안내</p>
              <ul className="space-y-1">
                <li>· 좌석을 선택한 후 &lsquo;선택 완료&rsquo; 버튼을 눌러주세요.</li>
                <li>· 선택 시간이 지나면 좌석이 자동 해제됩니다.</li>
                <li>· 1인 최대 {MAX_PER_USER}매까지 선택할 수 있습니다.</li>
              </ul>
            </CardContent>
          </Card>

          {errorMsg && (
            <p className="rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-center text-xs font-medium text-destructive">
              {errorMsg}
            </p>
          )}
          <Button className="w-full" disabled={selected.length === 0 || submitting} onClick={complete}>
            {submitting ? "처리 중…" : "선택 완료"}
          </Button>
          <Button variant="ghost" className="w-full" onClick={() => router.back()}>← 이전으로</Button>
        </aside>
      </div>
    </main>
  );
}
