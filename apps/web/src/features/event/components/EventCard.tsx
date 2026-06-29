import Link from "next/link";
import type { EventSummary } from "@/features/event/api/event";
import { Card } from "@/components/ui/card";

const STATUS_LABEL: Record<string, string> = {
  ON_SALE: "예매중",
  SCHEDULED: "오픈예정",
  SOLD_OUT: "매진",
  PAUSED: "일시중단",
  CLOSED: "종료",
  DRAFT: "준비중",
};

export function EventCard({ event }: { event: EventSummary }) {
  return (
    <Link href={`/events/${event.id}`} className="block">
      <Card className="overflow-hidden transition-shadow hover:shadow-md">
        <div className="aspect-[3/4] w-full bg-muted">
          {event.posterUrl && (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={event.posterUrl} alt={event.title} className="h-full w-full object-cover" />
          )}
        </div>
        <div className="space-y-1 p-3">
          <p className="line-clamp-1 font-medium">{event.title}</p>
          <p className="line-clamp-1 text-xs text-muted-foreground">{event.venue ?? "-"}</p>
          <div className="flex items-center justify-between pt-1 text-xs">
            <span className="text-muted-foreground">{event.startDate ?? ""}</span>
            <span className="rounded bg-muted px-1.5 py-0.5">{STATUS_LABEL[event.status] ?? event.status}</span>
          </div>
        </div>
      </Card>
    </Link>
  );
}
