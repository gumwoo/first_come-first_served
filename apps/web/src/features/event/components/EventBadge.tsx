import type { EventSummary } from "@/features/event/api/event";
import { Badge } from "@/components/ui/badge";

const STATUS_LABEL: Record<string, string> = {
  ON_SALE: "예매중", SCHEDULED: "오픈예정", SOLD_OUT: "매진",
  PAUSED: "일시중단", CLOSED: "종료", DRAFT: "준비중",
};

/** 공연 시작일까지 남은 일수. 파싱 불가/없으면 null. */
function ddays(startDate: string | null): number | null {
  if (!startDate) return null;
  const start = new Date(startDate);
  if (Number.isNaN(start.getTime())) return null;
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  start.setHours(0, 0, 0, 0);
  return Math.round((start.getTime() - today.getTime()) / 86_400_000);
}

/** 상태 + 임박도(D-day)를 하나의 배지로. 상태 우선, ON_SALE은 임박 시 D-day 강조. */
export function EventBadge({ event }: { event: EventSummary }) {
  if (event.status === "SOLD_OUT") return <Badge variant="destructive">매진</Badge>;
  if (event.status === "SCHEDULED") return <Badge variant="muted">오픈예정</Badge>;

  if (event.status === "ON_SALE") {
    const d = ddays(event.startDate);
    if (d !== null && d >= 0 && d <= 7) {
      return <Badge variant="destructive">{d === 0 ? "D-DAY" : `임박 D-${d}`}</Badge>;
    }
    return <Badge variant="default">예매중</Badge>;
  }
  return <Badge variant="outline">{STATUS_LABEL[event.status] ?? event.status}</Badge>;
}
