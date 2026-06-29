"use client";

import Link from "next/link";
import { usePopular, useEvents } from "@/features/event/hooks/useEvents";
import { EventCard } from "@/features/event/components/EventCard";
import type { EventSummary } from "@/features/event/api/event";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";

export default function Home() {
  const popular = usePopular();
  const all = useEvents({ size: 24 });

  const items = all.data?.items ?? [];
  const featured = popular.data?.[0] ?? items[0];
  const upcoming = items.filter((e) => e.status === "SCHEDULED").slice(0, 8);
  const ranking = (popular.data ?? items).slice(0, 5);

  return (
    <main className="mx-auto max-w-6xl px-4 py-8">
      {/* 히어로 */}
      <section className="relative mb-10 overflow-hidden rounded-xl bg-foreground text-primary-foreground">
        {featured?.posterUrl && (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={featured.posterUrl} alt="" className="absolute inset-0 h-full w-full object-cover opacity-40" />
        )}
        <div className="relative flex flex-col justify-end gap-2 p-10" style={{ minHeight: 240 }}>
          <p className="text-sm opacity-80">{featured?.genre ?? "지금 가장 핫한 공연"}</p>
          <h1 className="text-3xl font-bold">{featured?.title ?? "FLOW SUMMER LIVE"}</h1>
          <p className="opacity-90">{[featured?.venue, featured?.startDate].filter(Boolean).join(" · ")}</p>
          {featured && (
            <Link href={`/events/${featured.id}`} className="mt-2">
              <Button className="bg-primary">예매하기</Button>
            </Link>
          )}
        </div>
      </section>

      <div className="grid gap-8 lg:grid-cols-[1fr_260px]">
        <div className="space-y-10">
          {/* 인기 공연 TOP */}
          <Section title="인기 공연 TOP" loading={popular.isLoading} empty={popular.data?.length === 0} emptyMsg="인기 공연이 없습니다.">
            <Grid items={popular.data ?? []} />
          </Section>

          {/* 오픈 예정 */}
          <Section title="오픈 예정 공연" loading={all.isLoading} empty={upcoming.length === 0} emptyMsg="오픈 예정 공연이 없습니다.">
            <Grid items={upcoming} />
          </Section>

          {/* 전체 */}
          <Section title="전체 공연" loading={all.isLoading} empty={items.length === 0} emptyMsg="등록된 공연이 없습니다. (KOPIS 동기화 필요)">
            <Grid items={items} />
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
