"use client";

import { Suspense, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useSearch } from "@/features/event/hooks/useEvents";
import { EventCard } from "@/features/event/components/EventCard";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";

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

  return (
    <main className="mx-auto max-w-6xl px-4 py-8">
      <h1 className="mb-4 text-2xl font-bold">검색 결과</h1>

      <form
        className="mb-6 flex gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          setQuery(keyword);
        }}
      >
        <Input value={keyword} onChange={(e) => setKeyword(e.target.value)}
          placeholder="공연명으로 검색" />
        <Button type="submit" className="shrink-0">검색</Button>
      </form>

      {query.length === 0 && <p className="text-sm text-muted-foreground">검색어를 입력하세요.</p>}
      {result.isLoading && <p className="text-sm text-muted-foreground">검색 중…</p>}
      {result.data && result.data.items.length === 0 && (
        <p className="text-sm text-muted-foreground">&quot;{query}&quot; 검색 결과가 없습니다.</p>
      )}

      <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">
        {result.data?.items.map((e) => <EventCard key={e.id} event={e} />)}
      </div>
    </main>
  );
}
