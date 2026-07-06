"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { Clock, Users, CalendarClock, CheckCircle2, AlertTriangle } from "lucide-react";
import { useQueue } from "@/features/queue/hooks/useQueue";
import { useEvent } from "@/features/event/hooks/useEvents";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";

function fmtEta(sec: number): string {
  if (sec <= 0) return "잠시 후";
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return `약 ${m > 0 ? `${m}분 ` : ""}${s}초`;
}

export default function QueuePage() {
  const id = Number(useParams().id);
  const router = useRouter();
  const { phase, rank, total, eta, progress, queueToken, leave } = useQueue(id);
  const seatsHref = `/events/${id}/seats${queueToken ? `?qt=${queueToken}` : ""}`;
  const { data: event } = useEvent(id);

  const exitQueue = async () => {
    await leave();
    router.push(`/events/${id}`);
  };

  if (phase === "loading") {
    return <main className="mx-auto max-w-5xl p-10 text-center text-muted-foreground">대기열에 진입하는 중…</main>;
  }

  if (phase === "error") {
    return (
      <main className="mx-auto max-w-md p-10 text-center">
        <AlertTriangle className="mx-auto mb-3 h-10 w-10 text-destructive" />
        <p className="font-medium">대기열 진입에 실패했습니다.</p>
        <p className="mt-1 text-sm text-muted-foreground">로그인이 필요하거나 일시적 오류일 수 있습니다.</p>
        <div className="mt-4 flex justify-center gap-2">
          <Link href="/login"><Button variant="outline">로그인</Button></Link>
          <Link href={`/events/${id}`}><Button>공연 상세로</Button></Link>
        </div>
      </main>
    );
  }

  if (phase === "admitted") {
    return (
      <main className="mx-auto max-w-md p-10 text-center">
        <CheckCircle2 className="mx-auto mb-3 h-12 w-12 text-primary" />
        <h1 className="text-xl font-bold">입장 완료!</h1>
        <p className="mt-2 text-sm text-muted-foreground">
          지금 좌석을 선택할 수 있어요. 입장 제한 시간 내 진행해주세요.
        </p>
        <Button className="mt-4" onClick={() => router.push(seatsHref)}>좌석 선택으로</Button>
      </main>
    );
  }

  if (phase === "expired") {
    return (
      <main className="mx-auto max-w-md p-10 text-center">
        <AlertTriangle className="mx-auto mb-3 h-10 w-10 text-muted-foreground" />
        <h1 className="text-xl font-bold">대기 세션이 만료되었습니다</h1>
        <p className="mt-2 text-sm text-muted-foreground">대기 시간이 지나 세션이 종료되었어요. 다시 시도해주세요.</p>
        <div className="mt-4 flex justify-center gap-2">
          <Button onClick={() => router.refresh()}>다시 대기 진입</Button>
          <Link href={`/events/${id}`}><Button variant="outline">공연 상세로</Button></Link>
        </div>
      </main>
    );
  }

  // waiting
  return (
    <main className="mx-auto max-w-5xl px-4 py-8">
      <div className="text-center">
        <h1 className="text-2xl font-bold">예매 대기열</h1>
        <p className="mt-1 text-sm text-muted-foreground">{event?.title ?? "공연"}</p>
        <p className="text-xs text-muted-foreground">고객님의 현재 대기열을 안내해드립니다.</p>
      </div>

      <div className="mt-6 grid gap-6 md:grid-cols-[1fr_260px]">
        {/* 대기 카드 */}
        <Card>
          <CardContent className="space-y-6 p-8 text-center">
            <span className="inline-block rounded bg-muted px-2 py-0.5 text-xs">대기 중</span>
            <div>
              <p className="text-sm text-muted-foreground">현재 대기 순번</p>
              <p className="text-5xl font-extrabold tracking-tight text-primary">
                {rank.toLocaleString()}번
              </p>
            </div>

            <div className="flex justify-center gap-8 text-sm">
              <div className="flex items-center gap-1.5">
                <Users className="h-4 w-4 text-muted-foreground" />
                내 앞 대기 <b>{Math.max(0, rank - 1).toLocaleString()}명</b>
              </div>
              <div className="flex items-center gap-1.5">
                <Clock className="h-4 w-4 text-muted-foreground" />
                예상 대기시간 <b>{fmtEta(eta)}</b>
              </div>
            </div>

            {/* 진행 바 */}
            <div className="h-2 w-full overflow-hidden rounded-full bg-muted">
              <div className="h-full bg-primary transition-all" style={{ width: `${progress}%` }} />
            </div>
            <p className="text-xs text-muted-foreground">전체 대기 {total.toLocaleString()}명 기준</p>

            {/* 안내 */}
            <div className="rounded-md bg-primary/5 p-3 text-left text-sm text-muted-foreground">
              고객님의 자리가 밀리지 않도록 <b>이 화면을 유지</b>해주세요. 순번은 자동으로
              줄어들며, 입장 허용 시 좌석 선택으로 이동합니다.
            </div>

            <div className="flex justify-center gap-2">
              <Button variant="outline" onClick={exitQueue}>예매 취소하고 나가기</Button>
              <Button variant="ghost" onClick={exitQueue}>← 이전으로</Button>
            </div>
          </CardContent>
        </Card>

        {/* 사이드: 안내 + 이벤트 정보 */}
        <aside className="space-y-4">
          <Card className="bg-muted/30">
            <CardContent className="pt-5 text-sm">
              <p className="mb-2 font-medium">대기 안내</p>
              <ul className="space-y-1.5 text-xs text-muted-foreground">
                <li>· 대기 순번은 자동으로 줄어듭니다.</li>
                <li>· 새로고침하지 않아도 실시간 갱신됩니다.</li>
                <li>· 입장 후 제한 시간 내 좌석 선택을 진행해주세요.</li>
              </ul>
            </CardContent>
          </Card>
          <Card className="bg-muted/30">
            <CardContent className="space-y-2 pt-5 text-sm">
              <p className="flex items-center gap-1.5 font-medium">
                <CalendarClock className="h-4 w-4 text-primary" /> 이벤트 정보
              </p>
              <p className="line-clamp-2 font-medium">{event?.title ?? "-"}</p>
              <p className="text-xs text-muted-foreground">장소 {event?.venue ?? "-"}</p>
              <p className="text-xs text-muted-foreground">일시 {event?.startDate ?? "-"}</p>
            </CardContent>
          </Card>
        </aside>
      </div>
    </main>
  );
}
