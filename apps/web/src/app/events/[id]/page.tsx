"use client";

import { useParams, useRouter } from "next/navigation";
import { useEvent } from "@/features/event/hooks/useEvents";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";

export default function EventDetailPage() {
  const id = Number(useParams().id);
  const router = useRouter();
  const { data: event, isLoading, isError } = useEvent(id);

  if (isLoading) return <main className="mx-auto max-w-5xl p-8 text-muted-foreground">불러오는 중…</main>;
  if (isError || !event) return <main className="mx-auto max-w-5xl p-8 text-destructive">공연을 찾을 수 없습니다.</main>;

  const soldOut = event.status === "SOLD_OUT";

  return (
    <main className="mx-auto max-w-5xl px-4 py-8">
      <div className="grid gap-8 md:grid-cols-[300px_1fr]">
        <div className="aspect-[3/4] w-full overflow-hidden rounded-lg bg-muted">
          {event.posterUrl && (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={event.posterUrl} alt={event.title} className="h-full w-full object-cover" />
          )}
        </div>

        <div>
          <h1 className="text-2xl font-bold">{event.title}</h1>
          <dl className="mt-4 space-y-2 text-sm">
            <Row label="장소" value={event.venue} />
            <Row label="장르" value={event.genre} />
            <Row label="기간" value={[event.startDate, event.endDate].filter(Boolean).join(" ~ ")} />
            <Row label="관람시간" value={event.runningTime} />
            <Row label="관람연령" value={event.ageLimit} />
            <Row label="가격" value={event.basePrice ? `${event.basePrice.toLocaleString()}원~` : "미정"} />
          </dl>

          <div className="mt-6">
            <Button
              className="w-full md:w-auto"
              disabled={soldOut}
              onClick={() => router.push(`/events/${event.id}/queue`)}
            >
              {soldOut ? "매진" : "예매하기"}
            </Button>
          </div>

          <Card className="mt-6 bg-muted/30">
            <CardContent className="space-y-2 pt-6 text-sm text-muted-foreground">
              <p>· 예매하기를 누르면 대기열을 거쳐 좌석 선택으로 이동합니다.</p>
              <p>· 선착순 예매이며 재고 소진 시 마감됩니다.</p>
            </CardContent>
          </Card>
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
