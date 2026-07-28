"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { TimerOff } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";

export default function SeatExpiredPage() {
  const id = Number(useParams().id);
  return (
    <main className="mx-auto max-w-md px-4 py-16">
      <Card>
        <CardContent className="p-8 text-center">
          <TimerOff className="mx-auto mb-3 h-12 w-12 text-muted-foreground" />
          <h1 className="text-xl font-bold">좌석 선택 시간이 만료되었습니다</h1>
          <p className="mt-2 text-sm text-muted-foreground">
            제한 시간 내 선택이 완료되지 않아 선택이 초기화되었습니다.
            좌석을 다시 선택하려면 대기열에 재진입해 주세요.
          </p>
          <div className="mt-5 flex flex-col gap-2">
            <Link href={`/events/${id}/queue`}><Button className="w-full">대기열 다시 진입</Button></Link>
            <Link href={`/events/${id}`}><Button variant="outline" className="w-full">공연 상세로</Button></Link>
            <Link href="/search"><Button variant="ghost" className="w-full">다른 공연 보기</Button></Link>
          </div>
        </CardContent>
      </Card>
    </main>
  );
}
