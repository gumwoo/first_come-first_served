"use client";

import Link from "next/link";
import { Suspense, useState } from "react";
import { useSearchParams } from "next/navigation";
import { Heart } from "lucide-react";
import { useSearch } from "@/features/event/hooks/useEvents";
import type { EventSummary } from "@/features/event/api/event";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";

const GENRES = ["전체", "대중음악", "연극", "뮤지컬", "클래식", "콘서트"];
const STATUSES = ["전체", "예매중", "오픈예정", "매진"];

export default function SearchPage() {
  return (
    <Suspense fallback={<main className="mx-auto max-w-6xl px-4 py-8 text-muted-foreground">불러오는 중…</main>}>
      <SearchInner />
    </Suspense>
  );
}

function SearchInner() {
  const initial = useSearchParams().get("q") ?? "";
  const [keyword, setKeyword] = useState(initial);
  const [query, setQuery] = useState(initial);
  const result = useSearch(query);
  const items = result.data?.items ?? [];

  return (
    <main className="mx-auto max-w-6xl px-4 py-8">
      <h1 className="text-2xl font-bold">검색 결과</h1>
      <p className="mt-1 text-sm text-muted-foreground">원하는 공연을 찾아 예매하세요.</p>

      {/* 검색 + 필터 바 */}
      <form className="mt-4 flex gap-2" onSubmit={(e) => { e.preventDefault(); setQuery(keyword); }}>
        <Input value={keyword} onChange={(e) => setKeyword(e.target.value)} placeholder="공연명, 아티스트, 장소 검색" />
        <Button type="submit" className="shrink-0">검색</Button>
      </form>
      <div className="mt-3 flex flex-wrap gap-2">
        {[["장르", GENRES], ["상태", STATUSES]].map(([label, opts]) => (
          <select key={label as string} className="h-9 rounded-md border border-input bg-background px-2 text-sm text-muted-foreground">
            {(opts as string[]).map((o) => <option key={o}>{label as string}: {o}</option>)}
          </select>
        ))}
        <select className="h-9 rounded-md border border-input bg-background px-2 text-sm text-muted-foreground">
          <option>정렬: 최신순</option><option>정렬: 인기순</option>
        </select>
      </div>

      <div className="mt-6 grid gap-6 md:grid-cols-[1fr_240px]">
        {/* 결과 리스트(리스트형) */}
        <div className="space-y-3">
          {query.length === 0 && <p className="text-sm text-muted-foreground">검색어를 입력하세요.</p>}
          {result.isLoading && <p className="text-sm text-muted-foreground">검색 중…</p>}
          {result.data && items.length === 0 && (
            <p className="text-sm text-muted-foreground">&quot;{query}&quot; 검색 결과가 없습니다.</p>
          )}
          {items.map((e) => <SearchRow key={e.id} event={e} />)}
        </div>

        {/* 사이드: 연관/인기 검색어 (UI) */}
        <aside className="space-y-4">
          <Card className="bg-muted/30">
            <CardContent className="pt-5 text-sm">
              <p className="mb-2 font-medium">연관 검색어</p>
              <div className="flex flex-wrap gap-1.5 text-xs text-muted-foreground">
                {["콘서트", "페스티벌", "여름", "FLOW"].map((t) => <span key={t} className="rounded bg-muted px-2 py-0.5">#{t}</span>)}
              </div>
            </CardContent>
          </Card>
          <Card className="bg-muted/30">
            <CardContent className="pt-5 text-sm">
              <p className="mb-2 font-medium">인기 검색어</p>
              <ol className="space-y-1 text-xs text-muted-foreground">
                {["2026 FLOW SUMMER LIVE", "재즈 페스티벌", "어쿠스틱 나이트"].map((t, i) => (
                  <li key={t}><span className="mr-1.5 font-semibold text-primary">{i + 1}</span>{t}</li>
                ))}
              </ol>
            </CardContent>
          </Card>
        </aside>
      </div>
    </main>
  );
}

const STATUS_LABEL: Record<string, string> = {
  ON_SALE: "예매중", SCHEDULED: "오픈예정", SOLD_OUT: "매진", PAUSED: "일시중단", CLOSED: "종료", DRAFT: "준비중",
};

function SearchRow({ event }: { event: EventSummary }) {
  const soldOut = event.status === "SOLD_OUT";
  return (
    <Card>
      <CardContent className="flex gap-4 p-4">
        <Link href={`/events/${event.id}`} className="h-24 w-20 shrink-0 overflow-hidden rounded bg-muted">
          {event.posterUrl && (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={event.posterUrl} alt={event.title} className="h-full w-full object-cover" />
          )}
        </Link>
        <div className="min-w-0 flex-1">
          <Link href={`/events/${event.id}`} className="line-clamp-1 font-medium hover:text-primary">{event.title}</Link>
          <p className="mt-1 line-clamp-1 text-sm text-muted-foreground">{event.venue ?? "-"}</p>
          <p className="text-xs text-muted-foreground">{event.startDate ?? ""}</p>
          <p className="mt-1 text-sm font-semibold">{event.basePrice ? `${event.basePrice.toLocaleString()}원~` : "가격 미정"}</p>
        </div>
        <div className="flex flex-col items-end justify-between">
          <button aria-label="관심" className="text-muted-foreground hover:text-destructive"><Heart className="h-5 w-5" /></button>
          <Link href={`/events/${event.id}`}>
            <Button size="sm" variant={soldOut ? "outline" : "default"} disabled={soldOut}>
              {soldOut ? "매진" : "예매하기"}
            </Button>
          </Link>
        </div>
      </CardContent>
    </Card>
  );
}
