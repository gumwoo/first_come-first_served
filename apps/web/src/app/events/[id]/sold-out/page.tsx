"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { Ticket, MapPin, Calendar, Users, Clock, RefreshCw, Bell } from "lucide-react";
import { useEvent, usePopular } from "@/features/event/hooks/useEvents";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";

export default function SoldOutPage() {
  const id = Number(useParams().id);
  const { data: event } = useEvent(id);
  const { data: popular } = usePopular();
  const recos = (popular ?? []).filter((e) => e.id !== id).slice(0, 2);

  return (
    <main className="mx-auto max-w-6xl px-4 py-8">
      <div className="grid gap-6 lg:grid-cols-[1fr_320px]">
        {/* 본문 */}
        <Card>
          <CardContent className="p-8">
            <div className="flex items-center gap-4">
              <div className="flex h-14 w-14 items-center justify-center rounded-full bg-muted">
                <Ticket className="h-6 w-6 text-muted-foreground" />
              </div>
              <div>
                <Badge variant="muted">매진</Badge>
                <h1 className="mt-1 text-3xl font-bold">매진되었습니다</h1>
              </div>
            </div>
            <p className="mt-3 text-sm text-muted-foreground">
              실시간 재고 처리 결과 <b className="text-destructive">모든 좌석이 판매 완료</b>되었습니다.
              많은 관심에 감사드리며, 다른 공연을 확인해 보세요.
            </p>

            {/* 공연 카드 */}
            <div className="mt-6 flex gap-4 rounded-lg border border-border p-4">
              <div className="h-32 w-24 shrink-0 overflow-hidden rounded bg-muted">
                {event?.posterUrl && (
                  // eslint-disable-next-line @next/next/no-img-element
                  <img src={event.posterUrl} alt="" className="h-full w-full object-cover" />
                )}
              </div>
              <div className="flex-1 space-y-1.5 text-sm">
                <p className="text-base font-bold">{event?.title ?? "-"}</p>
                <p className="flex items-center gap-1.5 text-muted-foreground"><MapPin className="h-4 w-4" /> {event?.venue ?? "-"}</p>
                <p className="flex items-center gap-1.5 text-muted-foreground"><Calendar className="h-4 w-4" /> {event?.startDate ?? "-"}</p>
                <p className="flex items-center gap-1.5"><Clock className="h-4 w-4 text-muted-foreground" /> 판매 상태 <Badge variant="destructive">매진</Badge></p>
              </div>
            </div>

            {/* 상태 3분할 */}
            <div className="mt-4 grid grid-cols-3 divide-x divide-border rounded-lg border border-border py-3 text-center text-sm">
              <div><Users className="mx-auto mb-1 h-4 w-4 text-muted-foreground" />잔여 좌석<br /><b>0석</b></div>
              <div><Clock className="mx-auto mb-1 h-4 w-4 text-muted-foreground" />대기열 상태<br /><b>대기열 종료</b></div>
              <div><RefreshCw className="mx-auto mb-1 h-4 w-4 text-muted-foreground" />재고 동기화<br /><b>완료</b></div>
            </div>

            <div className="mt-4 flex flex-wrap gap-2">
              <Link href="/search"><Button>다른 공연 보기</Button></Link>
              <Link href={`/events/${id}`}><Button variant="outline">이벤트 상세로 돌아가기</Button></Link>
              <Link href="/"><Button variant="outline">메인으로</Button></Link>
            </div>
          </CardContent>
        </Card>

        {/* 사이드: 안내 + 추천 */}
        <aside className="space-y-4">
          <Card className="bg-muted/30">
            <CardContent className="pt-5 text-sm">
              <p className="mb-2 font-medium">안내 사항</p>
              <ul className="space-y-1.5 text-xs text-muted-foreground">
                <li>· 결제 실패와 무관하게, 남은 좌석이 없을 때 매진으로 표시됩니다.</li>
                <li>· 예매 취소로 좌석이 오픈되면 재오픈될 수 있습니다.</li>
                <li>· 새로고침해도 좌석이 추가되지 않을 수 있습니다.</li>
              </ul>
            </CardContent>
          </Card>
          <Card className="bg-muted/30">
            <CardContent className="pt-5 text-sm">
              <p className="mb-1 flex items-center gap-1.5 font-medium"><Bell className="h-4 w-4 text-primary" /> 취소표/재오픈 안내</p>
              <p className="text-xs text-muted-foreground">취소로 좌석이 오픈되면 좌석 선택 단계로 이동됩니다.</p>
            </CardContent>
          </Card>
          {recos.length > 0 && (
            <Card className="bg-muted/30">
              <CardContent className="pt-5 text-sm">
                <p className="mb-2 font-medium">이런 공연은 어때요?</p>
                <div className="space-y-2">
                  {recos.map((e) => (
                    <Link key={e.id} href={`/events/${e.id}`} className="flex items-center gap-2 hover:text-primary">
                      <div className="h-12 w-9 shrink-0 overflow-hidden rounded bg-muted">
                        {e.posterUrl && (
                          // eslint-disable-next-line @next/next/no-img-element
                          <img src={e.posterUrl} alt="" className="h-full w-full object-cover" />
                        )}
                      </div>
                      <span className="line-clamp-2 text-xs">{e.title}</span>
                    </Link>
                  ))}
                </div>
              </CardContent>
            </Card>
          )}
        </aside>
      </div>
    </main>
  );
}
