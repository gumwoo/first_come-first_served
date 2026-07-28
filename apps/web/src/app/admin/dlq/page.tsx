"use client";

import { useState } from "react";
import { RotateCcw, Trash2 } from "lucide-react";
import { useAdminDlq, useDlqAction } from "@/features/admin/hooks/useAdmin";
import type { DlqMessage } from "@/features/admin/api/admin";
import { PageHeader, DlqStatusPill, dateTime } from "@/features/admin/components/ui";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";

export default function AdminDlqPage() {
  const [page, setPage] = useState(0);
  const dlq = useAdminDlq("all", page);
  const action = useDlqAction();
  const totalPages = dlq.data ? Math.max(1, Math.ceil(dlq.data.total / dlq.data.size)) : 1;

  return (
    <div className="mx-auto max-w-6xl space-y-5 px-6 py-8">
      <PageHeader title="DLQ (실패 메시지)" desc="컨슈머 재시도가 소진된 메시지입니다. 원인 확인 후 재시도하거나 폐기합니다." />

      {dlq.isLoading ? (
        [0, 1].map((i) => <Skeleton key={i} className="h-16 w-full rounded-lg" />)
      ) : dlq.isError ? (
        <p className="rounded-lg border border-border py-10 text-center text-sm text-destructive">DLQ를 불러오지 못했습니다.</p>
      ) : dlq.data && dlq.data.items.length === 0 ? (
        <p className="rounded-lg border border-border py-14 text-center text-sm text-muted-foreground">
          실패 메시지가 없습니다.
        </p>
      ) : dlq.data ? (
        <div className="space-y-2">
          {dlq.data.items.map((m) => (
            <DlqRow key={m.id} msg={m} busy={action.isPending}
                    onRetry={() => action.mutate({ id: m.id, action: "retry" })}
                    onDiscard={() => action.mutate({ id: m.id, action: "discard" })} />
          ))}
        </div>
      ) : null}

      {dlq.data && dlq.data.total > dlq.data.size && (
        <div className="flex items-center justify-center gap-3">
          <Button variant="ghost" size="sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>이전</Button>
          <span className="text-sm text-muted-foreground">{page + 1} / {totalPages}</span>
          <Button variant="ghost" size="sm" disabled={page + 1 >= totalPages} onClick={() => setPage((p) => p + 1)}>다음</Button>
        </div>
      )}
    </div>
  );
}

function DlqRow({ msg, busy, onRetry, onDiscard }: {
  msg: DlqMessage; busy: boolean; onRetry: () => void; onDiscard: () => void;
}) {
  const done = msg.status === "RETRIED" || msg.status === "DISCARDED";
  return (
    <div className="flex flex-col gap-2 rounded-lg border border-border px-4 py-3 md:flex-row md:items-center md:justify-between">
      <div className="min-w-0 space-y-1">
        <div className="flex items-center gap-2">
          <DlqStatusPill status={msg.status} />
          <span className="font-mono text-xs text-muted-foreground">{msg.topic}</span>
          <span className="text-xs text-muted-foreground">{dateTime(msg.createdAt)}</span>
        </div>
        <p className="truncate font-mono text-xs">{msg.payload}</p>
        {msg.errorMessage && <p className="truncate text-xs text-destructive">{msg.errorMessage}</p>}
      </div>
      {!done && (
        <div className="flex shrink-0 gap-2">
          <Button size="sm" variant="outline" disabled={busy} onClick={onRetry}>
            <RotateCcw className="mr-1 h-3.5 w-3.5" /> 재시도
          </Button>
          <Button size="sm" variant="ghost" disabled={busy} onClick={onDiscard}>
            <Trash2 className="mr-1 h-3.5 w-3.5" /> 폐기
          </Button>
        </div>
      )}
    </div>
  );
}
