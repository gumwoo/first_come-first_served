"use client";

import Link from "next/link";
import { Suspense, useMemo, useState } from "react";
import { useSearchParams } from "next/navigation";
import { Heart } from "lucide-react";
import { useSearch, usePopularKeywords } from "@/features/event/hooks/useEvents";
import type { EventSummary } from "@/features/event/api/event";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";

// label(표시) ↔ value(API 파라미터). value=""이면 필터 없음(전체).
const GENRES: [string, string][] = [
  ["전체", ""], ["대중음악", "대중음악"], ["연극", "연극"],
  ["뮤지컬", "뮤지컬"], ["클래식", "서양음악(클래식)"], ["무용", "무용"],
];
const STATUSES: [string, string][] = [
  ["전체", ""], ["예매중", "ON_SALE"], ["오픈예정", "SCHEDULED"], ["매진", "SOLD_OUT"],
];
// 지역은 KOPIS area(시도 전체명)에 contains 매칭 → 짧은 라벨로 충분.
const REGIONS: [string, string][] = [
  ["전체", ""], ["서울", "서울"], ["경기", "경기"], ["인천", "인천"],
  ["부산", "부산"], ["대구", "대구"], ["대전", "대전"], ["광주", "광주"],
];

export default function SearchPage() {
  return (
    <Suspense fallback={<main className="mx-auto max-w-6xl px-4 py-8 text-muted-foreground">불러오는 중…</main>}>
      <SearchInner />
    </Suspense>
  );
}

function SearchInner() {
  const params = useSearchParams();
  const initial = params.get("q") ?? "";
  const [keyword, setKeyword] = useState(initial);
  const [query, setQuery] = useState(initial);
  const [genre, setGenre] = useState(params.get("genre") ?? ""); // 메인 카테고리 탭에서 유입
  const [region, setRegion] = useState("");
  const [status, setStatus] = useState("");
  const popularKeywords = usePopularKeywords();
  const [page, setPage] = useState(0);
  const [sort, setSort] = useState<"asc" | "desc">("asc");
  const result = useSearch(query, { genre, region, status, page });
  const total = result.data?.total ?? 0;
  const size = result.data?.size ?? 20;
  const totalPages = Math.max(1, Math.ceil(total / size));
  // 현재 페이지 결과를 공연일 기준 정렬(클라이언트). null 일자는 뒤로.
  const items = useMemo(() => {
    const list = [...(result.data?.items ?? [])];
    return list.sort((a, b) => {
      const x = a.startDate ?? "", y = b.startDate ?? "";
      if (!x) return 1;
      if (!y) return -1;
      return sort === "asc" ? x.localeCompare(y) : y.localeCompare(x);
    });
  }, [result.data, sort]);

  return (
    <main className="mx-auto max-w-6xl px-4 py-8">
      <h1 className="text-2xl font-bold">검색 결과</h1>
      <p className="mt-1 text-sm text-muted-foreground">원하는 공연을 찾아 예매하세요.</p>

      {/* 검색 + 필터 바 */}
      <form className="mt-4 flex gap-2" onSubmit={(e) => { e.preventDefault(); setPage(0); setQuery(keyword); }}>
        <Input value={keyword} onChange={(e) => setKeyword(e.target.value)} placeholder="공연명, 아티스트, 장소 검색" />
        <Button type="submit" className="shrink-0">검색</Button>
      </form>
      <div className="mt-3 flex flex-wrap gap-2">
        <select aria-label="장르" value={genre} onChange={(e) => { setPage(0); setGenre(e.target.value); }}
          className="h-9 rounded-md border border-input bg-background px-2 text-sm">
          {GENRES.map(([label, value]) => <option key={label} value={value}>장르: {label}</option>)}
        </select>
        <select aria-label="지역" value={region} onChange={(e) => { setPage(0); setRegion(e.target.value); }}
          className="h-9 rounded-md border border-input bg-background px-2 text-sm">
          {REGIONS.map(([label, value]) => <option key={label} value={value}>지역: {label}</option>)}
        </select>
        <select aria-label="상태" value={status} onChange={(e) => { setPage(0); setStatus(e.target.value); }}
          className="h-9 rounded-md border border-input bg-background px-2 text-sm">
          {STATUSES.map(([label, value]) => <option key={label} value={value}>상태: {label}</option>)}
        </select>
        <select aria-label="정렬" value={sort} onChange={(e) => setSort(e.target.value as "asc" | "desc")}
          className="h-9 rounded-md border border-input bg-background px-2 text-sm">
          <option value="asc">정렬: 공연일 빠른순</option>
          <option value="desc">정렬: 공연일 늦은순</option>
        </select>
      </div>

      <div className="mt-6 grid gap-6 md:grid-cols-[1fr_240px]">
        {/* 결과 리스트(리스트형) */}
        <div className="space-y-3">
          {query.length === 0 && !genre && !region && !status && (
            <p className="text-sm text-muted-foreground">검색어를 입력하거나 필터를 선택하세요.</p>
          )}
          {result.isLoading && <p className="text-sm text-muted-foreground">검색 중…</p>}
          {result.data && items.length === 0 && (
            <p className="text-sm text-muted-foreground">조건에 맞는 검색 결과가 없습니다.</p>
          )}
          {items.map((e) => <SearchRow key={e.id} event={e} />)}

          {/* 페이지네이션 */}
          {total > size && (
            <div className="flex items-center justify-center gap-3 pt-4">
              <Button variant="outline" size="sm" disabled={page <= 0} onClick={() => setPage((p) => p - 1)}>이전</Button>
              <span className="text-sm text-muted-foreground">{page + 1} / {totalPages}</span>
              <Button variant="outline" size="sm" disabled={page + 1 >= totalPages} onClick={() => setPage((p) => p + 1)}>다음</Button>
            </div>
          )}
        </div>

        {/* 사이드: 연관(목업)/인기(실연동) 검색어 */}
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
                {(popularKeywords.data ?? []).map((t, i) => (
                  <li key={t}>
                    <button onClick={() => { setKeyword(t); setQuery(t); setPage(0); }}
                      className="text-left hover:text-primary">
                      <span className="mr-1.5 font-semibold text-primary">{i + 1}</span>{t}
                    </button>
                  </li>
                ))}
                {(popularKeywords.data ?? []).length === 0 && (
                  <li className="text-muted-foreground">아직 인기 검색어가 없습니다.</li>
                )}
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
          <p className="mt-1 line-clamp-1 text-sm text-muted-foreground">{[event.region, event.venue].filter(Boolean).join(" · ") || "-"}</p>
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
