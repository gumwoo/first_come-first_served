"use client";

import { useEffect, useMemo, useState } from "react";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import { Clock, Users, CalendarClock, CheckCircle2, MapPin, Info } from "lucide-react";
import { useSeats } from "@/features/seat/hooks/useSeats";
import * as seatApi from "@/features/seat/api/seat";
import { useEvent } from "@/features/event/hooks/useEvents";
import { useAuthStore } from "@/features/auth/store/authStore";
import { ApiError } from "@/lib/apiClient";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";

const MAX_PER_USER = 4;
const SELECT_SECONDS = 300; // 좌석 선택 제한(5분)

// 등급 표시 순서(가격 내림차순)와 아레나 구역 색상.
const GRADE_ORDER: Record<string, number> = { VIP: 0, R: 1, S: 2, A: 3 };
const GRADE_ZONE: Record<string, string> = {
  VIP: "bg-purple-200 text-purple-900 border-purple-300",
  R: "bg-rose-200 text-rose-900 border-rose-300",
  S: "bg-emerald-200 text-emerald-900 border-emerald-300",
  A: "bg-sky-200 text-sky-900 border-sky-300",
};

function mmss(s: number) {
  const m = Math.floor(s / 60);
  return `${String(m).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
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
  const [activeGrade, setActiveGrade] = useState<string | null>(null);

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

  // 가격 desc 정렬된 등급
  const grades = useMemo(
    () => (map ? [...map.grades].sort((a, b) => (GRADE_ORDER[a.grade] ?? 9) - (GRADE_ORDER[b.grade] ?? 9)) : []),
    [map]
  );

  // 최초 로드 시 첫 등급 활성화
  useEffect(() => {
    if (!activeGrade && grades.length) setActiveGrade(grades[0].grade);
  }, [grades, activeGrade]);

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

  const total = selected.reduce((sum, sid) => {
    const s = seatById.get(sid);
    return sum + (s ? priceByGrade.get(s.grade) ?? 0 : 0);
  }, 0);

  const activeSeats = useMemo(
    () => (map && activeGrade ? map.seats.filter((s) => s.grade === activeGrade) : []),
    [map, activeGrade]
  );

  const toggle = (s: seatApi.SeatInfo) => {
    if (s.status !== "AVAILABLE") return;
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
    try {
      const result = await seatApi.holdSeats(id, selected, queueToken, accessToken);
      setHeld(result);
    } catch (e) {
      if (e instanceof ApiError && e.code === "SOLD_OUT") {
        router.replace(`/events/${id}/sold-out`);
      } else if (e instanceof ApiError && e.code === "QUEUE_NOT_ADMITTED") {
        router.replace(`/events/${id}/queue`);
      } else {
        await refresh();
        setSelected([]);
      }
    } finally {
      setSubmitting(false);
    }
  };

  if (loading) return <main className="mx-auto max-w-6xl p-10 text-center text-muted-foreground">좌석 정보를 불러오는 중…</main>;
  if (error || !map) return <main className="mx-auto max-w-6xl p-10 text-center text-destructive">좌석 정보를 불러올 수 없습니다.</main>;

  // 선점 완료 상태(결제 S05 전 임시)
  if (held) {
    return (
      <main className="mx-auto max-w-md p-10 text-center">
        <CheckCircle2 className="mx-auto mb-3 h-12 w-12 text-primary" />
        <h1 className="text-xl font-bold">좌석 선점 완료!</h1>
        <p className="mt-2 text-sm text-muted-foreground">
          {held.seatIds.length}석 · 총 {held.total.toLocaleString()}원 · 결제 단계는 준비 중입니다(S05).
        </p>
        <p className="mt-1 text-xs text-muted-foreground">제한 시간 내 미결제 시 선점이 자동 해제됩니다.</p>
        <div className="mt-4 flex justify-center gap-2">
          <Button variant="outline" onClick={async () => { await seatApi.releaseHold(held.holdId, accessToken); router.replace(`/events/${id}/seats?qt=${queueToken}`); }}>
            선점 취소
          </Button>
          <Link href={`/events/${id}`}><Button>공연 상세로</Button></Link>
        </div>
      </main>
    );
  }

  return (
    <main className="mx-auto max-w-6xl px-4 py-8">
      <div className="flex items-end gap-3">
        <h1 className="text-2xl font-bold">좌석 선택</h1>
        <p className="pb-1 text-sm text-muted-foreground">{event?.title ?? "공연"}</p>
      </div>
      <p className="mt-1 flex flex-wrap gap-4 text-sm text-muted-foreground">
        <span>원하는 구역을 선택한 후 좌석을 선택해 주세요.</span>
        <span className="flex items-center gap-1"><Users className="h-4 w-4" /> 1인 최대 {MAX_PER_USER}매</span>
        <span>현재 선택 {selected.length}매</span>
      </p>

      <div className="mt-6 grid gap-6 lg:grid-cols-[1fr_320px]">
        <div className="space-y-5">
          {/* 스타일 아레나맵(구역=등급) */}
          <Card>
            <CardContent className="p-5">
              <div className="mx-auto max-w-lg space-y-2">
                <div className="rounded bg-foreground/90 py-2 text-center text-sm font-semibold text-primary-foreground">STAGE</div>
                <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                  {grades.map((g) => {
                    const on = activeGrade === g.grade;
                    return (
                      <button key={g.grade} onClick={() => setActiveGrade(g.grade)}
                        className={`rounded-lg border py-4 text-center text-sm font-semibold transition ${GRADE_ZONE[g.grade] ?? "bg-muted"} ${on ? "ring-2 ring-primary ring-offset-1" : "opacity-90 hover:opacity-100"}`}>
                        {g.grade}
                        <span className="mt-0.5 block text-[11px] font-normal opacity-80">잔여 {g.available}</span>
                      </button>
                    );
                  })}
                </div>
                <div className="rounded bg-muted py-1.5 text-center text-xs text-muted-foreground">CONSOLE</div>
              </div>
              <div className="mt-4 flex justify-center gap-4 text-xs text-muted-foreground">
                <span className="flex items-center gap-1"><i className="inline-block h-3 w-3 rounded bg-muted" /> 선택 가능</span>
                <span className="flex items-center gap-1"><i className="inline-block h-3 w-3 rounded bg-primary" /> 선택 좌석</span>
                <span className="flex items-center gap-1"><i className="inline-block h-3 w-3 rounded bg-muted/40" /> 판매 완료</span>
              </div>
            </CardContent>
          </Card>

          {/* 선택 구역의 좌석 그리드 */}
          <Card>
            <CardContent className="p-5">
              <div className="mb-3 flex items-center justify-between text-sm">
                <span className="font-semibold">
                  {activeGrade}석 · {(priceByGrade.get(activeGrade ?? "") ?? 0).toLocaleString()}원
                </span>
                <span className="text-muted-foreground">
                  잔여 {grades.find((g) => g.grade === activeGrade)?.available ?? 0} / {grades.find((g) => g.grade === activeGrade)?.total ?? 0}
                </span>
              </div>
              <div className="flex flex-wrap gap-1.5">
                {activeSeats.map((s) => {
                  const sel = selected.includes(s.id);
                  const avail = s.status === "AVAILABLE";
                  return (
                    <button key={s.id} onClick={() => toggle(s)} disabled={!avail}
                      title={`${s.grade} ${s.seatCol}번`}
                      className={`h-8 w-8 rounded text-[11px] transition ${
                        sel ? "bg-primary text-primary-foreground"
                        : avail ? "bg-muted hover:bg-primary/20"
                        : "cursor-not-allowed bg-muted/40 text-muted-foreground/40 line-through"}`}>
                      {s.seatCol}
                    </button>
                  );
                })}
              </div>
              <p className="mt-3 flex items-center gap-1 text-xs text-muted-foreground">
                <Info className="h-3.5 w-3.5" /> 구역/열 배치는 예매 시점에 따라 일부 변경될 수 있습니다.
              </p>
            </CardContent>
          </Card>
        </div>

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

          <Button className="w-full" disabled={selected.length === 0 || submitting} onClick={complete}>
            {submitting ? "처리 중…" : "선택 완료"}
          </Button>
          <Button variant="ghost" className="w-full" onClick={() => router.back()}>← 이전으로</Button>
        </aside>
      </div>
    </main>
  );
}
