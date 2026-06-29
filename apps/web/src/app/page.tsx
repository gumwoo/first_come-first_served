"use client";

import { usePopular, useEvents } from "@/features/event/hooks/useEvents";
import { EventCard } from "@/features/event/components/EventCard";

export default function Home() {
  const popular = usePopular();
  const all = useEvents({ size: 12 });

  return (
    <main className="mx-auto max-w-6xl px-4 py-8">
      {/* 히어로 */}
      <section className="mb-10 rounded-lg bg-gradient-to-r from-primary/90 to-primary p-10 text-primary-foreground">
        <h1 className="text-3xl font-bold">FLOW SUMMER LIVE</h1>
        <p className="mt-2 opacity-90">지금 가장 뜨거운 공연을 선착순으로 예매하세요.</p>
      </section>

      {/* 인기 TOP */}
      <section className="mb-10">
        <h2 className="mb-4 text-xl font-bold">인기 공연 TOP</h2>
        {popular.isLoading && <p className="text-sm text-muted-foreground">불러오는 중…</p>}
        {popular.data && popular.data.length === 0 && (
          <p className="text-sm text-muted-foreground">표시할 인기 공연이 없습니다.</p>
        )}
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">
          {popular.data?.map((e) => <EventCard key={e.id} event={e} />)}
        </div>
      </section>

      {/* 전체 공연 */}
      <section>
        <h2 className="mb-4 text-xl font-bold">전체 공연</h2>
        {all.isLoading && <p className="text-sm text-muted-foreground">불러오는 중…</p>}
        {all.data && all.data.items.length === 0 && (
          <p className="text-sm text-muted-foreground">등록된 공연이 없습니다. (KOPIS 동기화 필요)</p>
        )}
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">
          {all.data?.items.map((e) => <EventCard key={e.id} event={e} />)}
        </div>
      </section>
    </main>
  );
}
