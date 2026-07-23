"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { ArrowLeft, Plus, Pencil } from "lucide-react";
import { useAdminEvents, useSaveAdminEvent } from "@/features/admin/hooks/useAdmin";
import { AdminGate } from "@/features/admin/components/AdminGate";
import * as adminApi from "@/features/admin/api/admin";
import type { AdminEventSummary, EventInput } from "@/features/admin/api/admin";
import { useAuthStore } from "@/features/auth/store/authStore";
import { Card, CardContent } from "@/components/ui/card";
import { Badge, type BadgeProps } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { Dialog } from "@/components/ui/dialog";

const STATUSES = ["DRAFT", "SCHEDULED", "ON_SALE", "PAUSED", "SOLD_OUT", "CLOSED"] as const;

const STATUS_LABEL: Record<string, { label: string; variant: BadgeProps["variant"] }> = {
  DRAFT: { label: "초안", variant: "muted" },
  SCHEDULED: { label: "예정", variant: "outline" },
  ON_SALE: { label: "판매중", variant: "default" },
  PAUSED: { label: "일시중지", variant: "muted" },
  SOLD_OUT: { label: "매진", variant: "destructive" },
  CLOSED: { label: "종료", variant: "muted" },
};

const won = (n: number | null) => (n == null ? "-" : `${n.toLocaleString()}원`);
const statusOf = (s: string) => STATUS_LABEL[s] ?? { label: s, variant: "muted" as const };

export default function AdminEventsPage() {
  return (
    <AdminGate>
      <EventsManager />
    </AdminGate>
  );
}

type EditTarget = { id: number | null } | null;

function EventsManager() {
  const [page, setPage] = useState(0);
  const [editing, setEditing] = useState<EditTarget>(null);
  const { data, isLoading, isError } = useAdminEvents(page);

  const totalPages = data ? Math.max(1, Math.ceil(data.total / data.size)) : 1;

  return (
    <main className="mx-auto max-w-6xl px-4 py-8">
      <Link href="/admin" className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft className="h-4 w-4" /> 운영 콘솔
      </Link>

      <div className="mt-2 flex items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold">공연 관리</h1>
          <p className="mt-1 text-sm text-muted-foreground">공연을 등록하고 상태·정보를 수정합니다.</p>
        </div>
        <Button onClick={() => setEditing({ id: null })}><Plus className="mr-1 h-4 w-4" /> 공연 등록</Button>
      </div>

      <div className="mt-6 space-y-3">
        {isLoading && [0, 1, 2, 3].map((i) => <Skeleton key={i} className="h-14 w-full rounded-lg" />)}
        {isError && (
          <Card><CardContent className="py-10 text-center text-sm text-destructive">목록을 불러오지 못했습니다.</CardContent></Card>
        )}
        {data && data.items.length === 0 && (
          <Card><CardContent className="py-14 text-center text-sm text-muted-foreground">등록된 공연이 없습니다.</CardContent></Card>
        )}

        {data && data.items.length > 0 && (
          <div className="overflow-hidden rounded-lg border border-border">
            <div className="hidden grid-cols-[1fr_140px_100px_110px_120px_80px] gap-3 border-b border-border bg-muted/40 px-4 py-2 text-xs font-medium text-muted-foreground md:grid">
              <span>공연</span><span>공연기간</span><span>장르</span><span className="text-right">최저가</span><span className="text-right">상태</span><span className="text-right">수정</span>
            </div>
            {data.items.map((e) => <EventRow key={e.id} event={e} onEdit={() => setEditing({ id: e.id })} />)}
          </div>
        )}

        {data && data.total > data.size && (
          <div className="flex items-center justify-center gap-3 pt-1">
            <Button variant="ghost" size="sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>이전</Button>
            <span className="text-sm text-muted-foreground">{page + 1} / {totalPages}</span>
            <Button variant="ghost" size="sm" disabled={page + 1 >= totalPages} onClick={() => setPage((p) => p + 1)}>다음</Button>
          </div>
        )}
      </div>

      {editing && <EventDialog target={editing} onClose={() => setEditing(null)} />}
    </main>
  );
}

function EventRow({ event, onEdit }: { event: AdminEventSummary; onEdit: () => void }) {
  const s = statusOf(event.status);
  const period = event.startDate ? `${event.startDate}${event.endDate && event.endDate !== event.startDate ? ` ~ ${event.endDate}` : ""}` : "-";
  return (
    <div className="grid gap-3 border-b border-border px-4 py-3 text-sm last:border-0 md:grid-cols-[1fr_140px_100px_110px_120px_80px] md:items-center">
      <div className="min-w-0">
        <p className="truncate font-medium">{event.title}</p>
        <p className="truncate text-xs text-muted-foreground">
          {event.venue ?? "장소 미정"}{event.fromKopis ? " · KOPIS" : " · 수동등록"}
        </p>
      </div>
      <span className="text-xs text-muted-foreground">{period}</span>
      <span className="text-muted-foreground">{event.genre ?? "-"}</span>
      <span className="font-medium md:text-right">{won(event.basePrice)}</span>
      <span className="md:text-right"><Badge variant={s.variant}>{s.label}</Badge></span>
      <span className="md:text-right">
        <Button variant="ghost" size="sm" onClick={onEdit}><Pencil className="h-4 w-4" /></Button>
      </span>
    </div>
  );
}

function EventDialog({ target, onClose }: { target: { id: number | null }; onClose: () => void }) {
  const token = useAuthStore((s) => s.accessToken);
  const isNew = target.id == null;
  const save = useSaveAdminEvent();

  // 편집이면 상세를 로드해 폼 프리필. 신규면 빈 폼.
  const [form, setForm] = useState<EventInput>({ status: "SCHEDULED" });
  const [loaded, setLoaded] = useState(isNew);
  const [error, setError] = useState<string | null>(null);

  // 편집이면 상세를 로드해 폼 프리필(신규는 skip).
  useEffect(() => {
    if (isNew || target.id == null) return;
    let alive = true;
    adminApi.getAdminEvent(target.id, token).then((d) => {
      if (!alive) return;
      setForm({
        title: d.title, venue: d.venue, genre: d.genre, region: d.region, posterUrl: d.posterUrl,
        startDate: d.startDate, endDate: d.endDate, runningTime: d.runningTime, ageLimit: d.ageLimit,
        status: d.status, basePrice: d.basePrice,
      });
      setLoaded(true);
    }).catch(() => { if (alive) setError("공연 정보를 불러오지 못했습니다."); });
    return () => { alive = false; };
  }, [isNew, target.id, token]);

  const set = (k: keyof EventInput) => (v: string) =>
    setForm((f) => ({ ...f, [k]: v === "" ? null : v }));

  const submit = () => {
    setError(null);
    if (!form.title || form.title.trim() === "") {
      setError("공연명을 입력해 주세요.");
      return;
    }
    const body: EventInput = {
      ...form,
      basePrice: form.basePrice != null && String(form.basePrice) !== "" ? Number(form.basePrice) : null,
    };
    save.mutate(
      { id: target.id, body },
      { onSuccess: onClose, onError: (e: unknown) => setError(e instanceof Error ? e.message : "저장 실패") }
    );
  };

  return (
    <Dialog
      open
      onClose={onClose}
      title={isNew ? "공연 등록" : "공연 수정"}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>취소</Button>
          <Button onClick={submit} disabled={save.isPending || !loaded}>{save.isPending ? "저장 중…" : "저장"}</Button>
        </>
      }
    >
      {!loaded ? (
        <Skeleton className="h-64 w-full" />
      ) : (
        <div className="space-y-3 text-foreground">
          <Field label="공연명 *"><Input value={form.title ?? ""} onChange={(e) => set("title")(e.target.value)} placeholder="예: 뮤지컬 캣츠" /></Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label="장소"><Input value={form.venue ?? ""} onChange={(e) => set("venue")(e.target.value)} /></Field>
            <Field label="장르"><Input value={form.genre ?? ""} onChange={(e) => set("genre")(e.target.value)} /></Field>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <Field label="시작일"><Input type="date" value={form.startDate ?? ""} onChange={(e) => set("startDate")(e.target.value)} /></Field>
            <Field label="종료일"><Input type="date" value={form.endDate ?? ""} onChange={(e) => set("endDate")(e.target.value)} /></Field>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <Field label="최저가(원)"><Input type="number" value={form.basePrice ?? ""} onChange={(e) => set("basePrice")(e.target.value)} /></Field>
            <Field label="상태">
              <select
                value={form.status ?? "SCHEDULED"}
                onChange={(e) => set("status")(e.target.value)}
                className="h-9 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                {STATUSES.map((s) => <option key={s} value={s}>{statusOf(s).label} ({s})</option>)}
              </select>
            </Field>
          </div>
          <Field label="포스터 URL"><Input value={form.posterUrl ?? ""} onChange={(e) => set("posterUrl")(e.target.value)} placeholder="https://…" /></Field>

          {error && <p className="text-sm text-destructive">{error}</p>}
        </div>
      )}
    </Dialog>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="space-y-1">
      <Label className="text-xs text-muted-foreground">{label}</Label>
      {children}
    </div>
  );
}
