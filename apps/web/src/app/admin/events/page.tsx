"use client";

import { useEffect, useState } from "react";
import { Plus, Pencil } from "lucide-react";
import { useAdminEvents, useSaveAdminEvent } from "@/features/admin/hooks/useAdmin";
import { PageHeader, EventStatusPill, eventStatusLabel } from "@/features/admin/components/ui";
import * as adminApi from "@/features/admin/api/admin";
import type { AdminEventSummary, EventInput } from "@/features/admin/api/admin";
import { useAuthStore } from "@/features/auth/store/authStore";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { Dialog } from "@/components/ui/dialog";

const STATUSES = ["DRAFT", "SCHEDULED", "ON_SALE", "PAUSED", "SOLD_OUT", "CLOSED"] as const;

const won = (n: number | null) => (n == null ? "-" : `${n.toLocaleString()}원`);

export default function AdminEventsPage() {
  return <EventsManager />;
}

type EditTarget = { id: number | null } | null;

function EventsManager() {
  const [page, setPage] = useState(0);
  const [editing, setEditing] = useState<EditTarget>(null);
  const { data, isLoading, isError } = useAdminEvents(page);

  const totalPages = data ? Math.max(1, Math.ceil(data.total / data.size)) : 1;

  return (
    <main className="mx-auto max-w-6xl space-y-6 px-6 py-8">
      <PageHeader
        title="공연 관리"
        desc="공연을 등록하고 상태·정보를 수정합니다."
        actions={<Button onClick={() => setEditing({ id: null })}><Plus className="mr-1 h-4 w-4" /> 공연 등록</Button>}
      />

      <div className="space-y-4">
        {isLoading && <Skeleton className="h-64 w-full rounded-lg" />}
        {isError && (
          <p className="rounded-lg border border-border py-10 text-center text-sm text-destructive">목록을 불러오지 못했습니다.</p>
        )}
        {data && data.items.length === 0 && (
          <p className="rounded-lg border border-border py-14 text-center text-sm text-muted-foreground">등록된 공연이 없습니다.</p>
        )}

        {data && data.items.length > 0 && (
          <div className="overflow-x-auto rounded-lg border border-border">
            <table className="w-full min-w-[760px] text-sm">
              <thead>
                <tr className="border-b border-border bg-muted/40 text-xs text-muted-foreground">
                  <th className="px-4 py-2.5 text-left font-medium">공연</th>
                  <th className="px-4 py-2.5 text-left font-medium">공연기간</th>
                  <th className="px-4 py-2.5 text-left font-medium">장르</th>
                  <th className="px-4 py-2.5 text-right font-medium">최저가</th>
                  <th className="px-4 py-2.5 text-right font-medium">상태</th>
                  <th className="px-4 py-2.5 text-right font-medium">수정</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((e) => <EventRow key={e.id} event={e} onEdit={() => setEditing({ id: e.id })} />)}
              </tbody>
            </table>
          </div>
        )}

        {data && data.total > data.size && (
          <div className="flex items-center justify-center gap-3">
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
  const period = event.startDate
    ? `${event.startDate}${event.endDate && event.endDate !== event.startDate ? ` ~ ${event.endDate}` : ""}`
    : "-";
  return (
    <tr className="border-b border-border last:border-0 hover:bg-muted/30">
      <td className="max-w-[240px] px-4 py-2.5">
        <p className="truncate font-medium">{event.title}</p>
        <p className="truncate text-xs text-muted-foreground">
          {event.venue ?? "장소 미정"}{event.fromKopis ? " · KOPIS" : " · 수동등록"}
        </p>
      </td>
      <td className="px-4 py-2.5 text-xs text-muted-foreground">{period}</td>
      <td className="px-4 py-2.5 text-muted-foreground">{event.genre ?? "-"}</td>
      <td className="px-4 py-2.5 text-right tabular-nums">{won(event.basePrice)}</td>
      <td className="px-4 py-2.5 text-right"><EventStatusPill status={event.status} /></td>
      <td className="px-4 py-2.5 text-right">
        <Button variant="ghost" size="sm" onClick={onEdit}><Pencil className="h-4 w-4" /></Button>
      </td>
    </tr>
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
                {STATUSES.map((s) => <option key={s} value={s}>{eventStatusLabel(s)} ({s})</option>)}
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
