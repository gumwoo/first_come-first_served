"use client";

import { useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { Heart, CalendarClock } from "lucide-react";
import { useEvent } from "@/features/event/hooks/useEvents";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";

const TABS = ["공연정보", "판매정보", "유의사항"] as const;

export default function EventDetailPage() {
  const id = Number(useParams().id);
  const router = useRouter();
  const { data: event, isLoading, isError } = useEvent(id);
  const [tab, setTab] = useState<(typeof TABS)[number]>("공연정보");

  if (isLoading) return <main className="mx-auto max-w-5xl p-8 text-muted-foreground">불러오는 중…</main>;
  if (isError || !event) return <main className="mx-auto max-w-5xl p-8 text-destructive">공연을 찾을 수 없습니다.</main>;

  const soldOut = event.status === "SOLD_OUT";

  return (
    <main className="mx-auto max-w-5xl px-4 py-8">
      {/* 상단: 포스터 + 제목/판매정보 + 우측 안내 */}
      <div className="grid gap-6 md:grid-cols-[260px_1fr_260px]">
        <div className="aspect-[3/4] overflow-hidden rounded-lg bg-muted">
          {event.posterUrl && (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={event.posterUrl} alt={event.title} className="h-full w-full object-cover" />
          )}
        </div>

        {/* 제목 + 판매정보 */}
        <div>
          <h1 className="text-2xl font-bold">{event.title}</h1>
          <p className="mt-1 text-sm text-muted-foreground">{event.genre ?? "공연"}</p>
          <dl className="mt-4 space-y-2 text-sm">
            <Row label="장소" value={event.venue} />
            <Row label="공연기간" value={[event.startDate, event.endDate].filter(Boolean).join(" ~ ")} />
            <Row label="관람시간" value={event.runningTime} />
            <Row label="관람연령" value={event.ageLimit} />
          </dl>
          <div className="mt-4 rounded-md bg-muted/40 p-3">
            <span className="text-xs text-muted-foreground">최저가</span>
            <p className="text-lg font-bold text-primary">
              {event.basePrice ? `${event.basePrice.toLocaleString()}원` : "가격 미정"}
            </p>
          </div>
          <div className="mt-4 flex gap-2">
            <Button className="flex-1" disabled={soldOut} onClick={() => router.push(`/events/${event.id}/queue`)}>
              {soldOut ? "매진" : "예매하기"}
            </Button>
            <Button variant="outline" className="gap-1"><Heart className="h-4 w-4" /> 관심</Button>
          </div>
        </div>

        {/* 우측 예매 안내 */}
        <aside>
          <Card className="bg-muted/30">
            <CardContent className="space-y-3 pt-5 text-sm">
              <div className="flex items-center gap-2 font-medium">
                <CalendarClock className="h-4 w-4 text-primary" /> 예매 안내
              </div>
              <ul className="space-y-2 text-muted-foreground">
                <li>· 예매하기 → 대기열 → 좌석 선택 순서로 진행됩니다.</li>
                <li>· 선착순 예매이며 재고 소진 시 마감됩니다.</li>
                <li>· 결제 시간 내 미결제 시 자동 취소됩니다.</li>
              </ul>
            </CardContent>
          </Card>
        </aside>
      </div>

      {/* 탭 */}
      <div className="mt-10">
        <div className="flex gap-1 border-b border-border">
          {TABS.map((t) => (
            <button key={t} onClick={() => setTab(t)}
              className={`px-4 py-2 text-sm ${tab === t ? "border-b-2 border-primary font-semibold" : "text-muted-foreground"}`}>
              {t}
            </button>
          ))}
        </div>
        <div className="py-6 text-sm leading-relaxed text-muted-foreground">
          {tab === "공연정보" && <p>{event.title} 공연 정보입니다. 장소: {event.venue ?? "-"}, 장르: {event.genre ?? "-"}.</p>}
          {tab === "판매정보" && <p>선착순 예매로 진행되며, 1인당 구매 수량 제한이 적용될 수 있습니다.</p>}
          {tab === "유의사항" && <p>예매 후 취소/환불은 환불 규정에 따릅니다. 공연 당일 신분 확인이 필요할 수 있습니다.</p>}
        </div>
      </div>
    </main>
  );
}

function Row({ label, value }: { label: string; value: string | null | undefined }) {
  return (
    <div className="flex gap-3">
      <dt className="w-20 shrink-0 text-muted-foreground">{label}</dt>
      <dd>{value || "-"}</dd>
    </div>
  );
}
