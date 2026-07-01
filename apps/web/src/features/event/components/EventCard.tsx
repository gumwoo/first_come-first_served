import Link from "next/link";
import type { EventSummary } from "@/features/event/api/event";
import { Card } from "@/components/ui/card";
import { EventBadge } from "@/features/event/components/EventBadge";

export function EventCard({ event }: { event: EventSummary }) {
  return (
    <Link href={`/events/${event.id}`} className="block">
      <Card className="overflow-hidden transition-shadow hover:shadow-md">
        <div className="relative aspect-[3/4] w-full bg-muted">
          {event.posterUrl && (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={event.posterUrl} alt={event.title} className="h-full w-full object-cover" />
          )}
          <div className="absolute left-2 top-2">
            <EventBadge event={event} />
          </div>
        </div>
        <div className="space-y-1 p-3">
          <p className="line-clamp-1 font-medium">{event.title}</p>
          <p className="line-clamp-1 text-xs text-muted-foreground">
            {[event.region, event.venue].filter(Boolean).join(" · ") || "-"}
          </p>
          <p className="pt-1 text-xs text-muted-foreground">{event.startDate ?? ""}</p>
        </div>
      </Card>
    </Link>
  );
}
