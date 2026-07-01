"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Search } from "lucide-react";
import { usePopular, useEvents, useRealtimeRanking } from "@/features/event/hooks/useEvents";
import { EventCard } from "@/features/event/components/EventCard";
import { HeroCarousel } from "@/features/event/components/HeroCarousel";
import type { EventSummary } from "@/features/event/api/event";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";

// label(표시) ↔ genre 값. 탭은 메인에서 그 자리 필터(이동 X).
const CATEGORIES: [string, string][] = [
  ["전체", ""], ["대중음악", "대중음악"], ["연극", "연극"],
  ["뮤지컬", "뮤지컬"], ["클래식", "서양음악(클래식)"], ["무용", "무용"],
];

export default function Home() {
  const router = useRouter();
  const popular = usePopular();
  const all = useEvents({ size: 24 });
  const realtime = useRealtimeRanking();
  const [category, setCategory] = useState(""); // "" = 전체
  const [keyword, setKeyword] = useState("");

  const items = all.data?.items ?? [];
  const heroItems = (popular.data ?? items).slice(0, 5);
  const upcoming = items.filter((e) => e.status === "SCHEDULED").slice(0, 8);
  const ranking = (realtime.data ?? []).slice(0, 5);
  // 탭은 그 자리 필터(전체 공연 섹션). 카테고리 선택 시 인기/오픈예정(전 장르)은 숨김.
  const filtered = category === "" ? items : items.filter((e) => e.genre === category);
  const browsing = category !== "";

  // 명시적 검색(검색 버튼/Enter)일 때만 검색 결과 페이지로 이동.
  const submitSearch = (e: React.FormEvent) => {
    e.preventDefault();
    const p = new URLSearchParams();
    if (keyword.trim()) p.set("q", keyword.trim());
    if (category) p.set("genre", category);
    router.push(`/search?${p.toString()}`);
  };

  return (
    <main className="mx-auto max-w-6xl px-4 py-8">
      {/* 히어로 슬라이더(대표 공연 자동 회전) */}
      <HeroCarousel items={heroItems} />

      {/* 검색창 — 검색 버튼/Enter일 때만 검색 결과 페이지로 이동 */}
      <form className="mb-4 flex gap-2" onSubmit={submitSearch}>
        <Input value={keyword} onChange={(e) => setKeyword(e.target.value)} placeholder="공연명, 아티스트, 장소 검색" />
        <Button type="submit" className="shrink-0 gap-1"><Search className="h-4 w-4" /> 검색</Button>
      </form>

      {/* 카테고리 탭(그 자리 필터, 이동 없음) */}
      <div className="mb-6 flex gap-1 overflow-x-auto border-b border-border">
        {CATEGORIES.map(([label, value]) => (
          <button key={label} onClick={() => setCategory(value)}
            className={`whitespace-nowrap px-3 py-2 text-sm ${category === value ? "border-b-2 border-primary font-semibold" : "text-muted-foreground hover:text-foreground"}`}>
            {label}
          </button>
        ))}
      </div>

      <div className="grid gap-8 lg:grid-cols-[1fr_260px]">
        <div className="space-y-10">
          {/* 인기/오픈예정은 전 장르 큐레이션 → 카테고리 둘러보는 중엔 숨김 */}
          {!browsing && (
            <>
              <Section title="인기 공연 TOP" loading={popular.isLoading} empty={popular.data?.length === 0} emptyMsg="인기 공연이 없습니다.">
                <Grid items={popular.data ?? []} />
              </Section>
              <Section title="오픈 예정 공연" loading={all.isLoading} empty={upcoming.length === 0} emptyMsg="오픈 예정 공연이 없습니다.">
                <Grid items={upcoming} />
              </Section>
            </>
          )}

          {/* 전체/카테고리 목록(뷰 모드 적용) */}
          <Section
            title={browsing ? `${CATEGORIES.find(([, v]) => v === category)?.[0] ?? category} 공연` : "전체 공연"}
            loading={all.isLoading}
            empty={filtered.length === 0}
            emptyMsg={items.length === 0 ? "등록된 공연이 없습니다. (KOPIS 동기화 필요)" : "해당 카테고리 공연이 없습니다."}
          >
            <Grid items={filtered} />
          </Section>
        </div>

        {/* 실시간 랭킹 */}
        <aside>
          <Card className="bg-muted/30">
            <CardContent className="pt-5">
              <p className="mb-3 font-bold">실시간 랭킹</p>
              <ol className="space-y-2 text-sm">
                {ranking.map((e, i) => (
                  <li key={e.id}>
                    <Link href={`/events/${e.id}`} className="flex gap-2 hover:text-primary">
                      <span className="font-bold text-primary">{i + 1}</span>
                      <span className="line-clamp-1">{e.title}</span>
                    </Link>
                  </li>
                ))}
                {ranking.length === 0 && <li className="text-xs text-muted-foreground">데이터 없음</li>}
              </ol>
            </CardContent>
          </Card>
        </aside>
      </div>
    </main>
  );
}

function Section({ title, loading, empty, emptyMsg, children }: {
  title: string; loading: boolean; empty: boolean; emptyMsg: string; children: React.ReactNode;
}) {
  return (
    <section>
      <h2 className="mb-4 text-xl font-bold">{title}</h2>
      {loading && <p className="text-sm text-muted-foreground">불러오는 중…</p>}
      {!loading && empty && <p className="text-sm text-muted-foreground">{emptyMsg}</p>}
      {children}
    </section>
  );
}

function Grid({ items }: { items: EventSummary[] }) {
  return (
    <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">
      {items.map((e) => <EventCard key={e.id} event={e} />)}
    </div>
  );
}

