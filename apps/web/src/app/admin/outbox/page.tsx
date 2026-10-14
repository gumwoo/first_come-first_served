"use client";

import { useState } from "react";
import { RotateCcw, Trash2 } from "lucide-react";
import { useAdminOutbox, useOutboxAction } from "@/features/admin/hooks/useAdmin";
import type { OutboxEvent } from "@/features/admin/api/admin";
import { OutboxStatus } from "@/types/contracts";
import { PageHeader, OutboxStatusPill, dateTime } from "@/features/admin/components/ui";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";

/** 상태 필터. 기본은 운영자가 개입할 대상인 격리(DEAD)다. */
const FILTERS = [
  { value: "DEAD", label: "격리" },
  { value: "PENDING", label: "미발행" },
  { value: "all", label: "전체" },
] as const satisfies readonly { value: OutboxStatus | "all"; label: string }[];

/**
 * 아웃박스 운영 화면. 재시도로 풀리지 않아 격리(DEAD)된 이벤트를 보고 재발행 대기로 되돌리거나 폐기한다.
 * 두 조치는 DEAD에만 허용된다(서버가 그 밖의 상태는 409로 거절 — OutboxEvent.requireDead).
 * 발행 자체는 릴레이가 한다. 재발행은 PENDING으로 되돌릴 뿐이다.
 */
export default function AdminOutboxPage() {
  const [status, setStatus] = useState<(typeof FILTERS)[number]["value"]>("DEAD");
  const [page, setPage] = useState(0);
  const outbox = useAdminOutbox(status, page);
  const action = useOutboxAction();
  const totalPages = outbox.data ? Math.max(1, Math.ceil(outbox.data.total / outbox.data.size)) : 1;

  return (
    <div className="mx-auto max-w-6xl space-y-5 px-6 py-8">
      <PageHeader
        title="아웃박스"
        desc="결제와 같은 트랜잭션에 쌓인 알림 이벤트입니다. 격리된 이벤트는 원인 확인 후 재발행하거나 폐기합니다."
      />

      <div className="flex gap-1.5">
        {FILTERS.map((f) => (
          <Button
            key={f.value}
            size="sm"
            variant={status === f.value ? "default" : "ghost"}
            onClick={() => {
              setStatus(f.value);
              setPage(0);
            }}
          >
            {f.label}
          </Button>
        ))}
      </div>

      {outbox.isLoading ? (
        [0, 1].map((i) => <Skeleton key={i} className="h-16 w-full rounded-lg" />)
      ) : outbox.isError ? (
        <p className="rounded-lg border border-border py-10 text-center text-sm text-destructive">아웃박스를 불러오지 못했습니다.</p>
      ) : outbox.data && outbox.data.items.length === 0 ? (
        <p className="rounded-lg border border-border py-14 text-center text-sm text-muted-foreground">
          해당 상태의 이벤트가 없습니다.
        </p>
      ) : outbox.data ? (
        <div className="space-y-2">
          {outbox.data.items.map((e) => (
            <OutboxRow key={e.id} event={e} busy={action.isPending}
                       onRequeue={() => action.mutate({ id: e.id, action: "requeue" })}
                       onDiscard={() => action.mutate({ id: e.id, action: "discard" })} />
          ))}
        </div>
      ) : null}

      {outbox.data && outbox.data.total > outbox.data.size && (
        <div className="flex items-center justify-center gap-3">
          <Button variant="ghost" size="sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>이전</Button>
          <span className="text-sm text-muted-foreground">{page + 1} / {totalPages}</span>
          <Button variant="ghost" size="sm" disabled={page + 1 >= totalPages} onClick={() => setPage((p) => p + 1)}>다음</Button>
        </div>
      )}
    </div>
  );
}

function OutboxRow({ event, busy, onRequeue, onDiscard }: {
  event: OutboxEvent; busy: boolean; onRequeue: () => void; onDiscard: () => void;
}) {
  return (
    <div className="flex flex-col gap-2 rounded-lg border border-border px-4 py-3 md:flex-row md:items-center md:justify-between">
      <div className="min-w-0 space-y-1">
        <div className="flex flex-wrap items-center gap-2">
          <OutboxStatusPill status={event.status} />
          <span className="font-mono text-xs">{event.type}</span>
          <span className="text-xs text-muted-foreground">
            {event.aggregateType} #{event.aggregateId} · 시도 {event.attempts}회 · {dateTime(event.createdAt)}
          </span>
        </div>
        <p className="truncate font-mono text-xs text-muted-foreground">{event.payload}</p>
        {event.lastError && <p className="truncate text-xs text-destructive">{event.lastError}</p>}
      </div>
      {event.status === "DEAD" && (
        <div className="flex shrink-0 gap-2">
          <Button size="sm" variant="outline" disabled={busy} onClick={onRequeue}>
            <RotateCcw className="mr-1 h-3.5 w-3.5" /> 재발행
          </Button>
          <Button size="sm" variant="ghost" disabled={busy} onClick={onDiscard}>
            <Trash2 className="mr-1 h-3.5 w-3.5" /> 폐기
          </Button>
        </div>
      )}
    </div>
  );
}
