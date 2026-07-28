"use client";

import { useEffect, useState } from "react";
import { useAlerts, useUpdateAlert } from "@/features/admin/hooks/useAdmin";
import { PageHeader } from "@/features/admin/components/ui";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";

export default function AdminAlertsPage() {
  const alerts = useAlerts();
  const update = useUpdateAlert();
  const [value, setValue] = useState("");

  useEffect(() => {
    if (alerts.data) setValue(String(alerts.data.dlqPendingThreshold));
  }, [alerts.data]);

  const save = () => {
    const n = Number(value);
    if (Number.isFinite(n) && n >= 0) update.mutate(n);
  };

  const a = alerts.data;
  const dirty = a && value !== String(a.dlqPendingThreshold);

  return (
    <div className="mx-auto max-w-2xl space-y-6 px-6 py-8">
      <PageHeader title="알림" desc="운영 경고 임계치를 설정합니다." />

      {alerts.isLoading || !a ? (
        <Skeleton className="h-40 w-full rounded-lg" />
      ) : (
        <>
          {a.breached && (
            <div className="rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700 dark:border-rose-500/30 dark:bg-rose-500/10 dark:text-rose-400">
              현재 DLQ 적체 {a.dlqPending}건이 임계치({a.dlqPendingThreshold})를 넘었습니다.
            </div>
          )}

          <div className="rounded-lg border border-border">
            <div className="flex items-center justify-between border-b border-border px-5 py-4">
              <div>
                <p className="text-sm font-medium">DLQ 적체 경고</p>
                <p className="mt-0.5 text-xs text-muted-foreground">미처리(PENDING) 실패 메시지가 이 값 이상이면 경고합니다.</p>
              </div>
              <span className="text-sm text-muted-foreground">현재 적체 <span className="font-medium text-foreground">{a.dlqPending}건</span></span>
            </div>
            <div className="flex items-end gap-3 px-5 py-4">
              <div className="space-y-1">
                <Label htmlFor="th" className="text-xs text-muted-foreground">경고 임계치</Label>
                <Input id="th" type="number" min={0} value={value} onChange={(e) => setValue(e.target.value)} className="h-9 w-32" />
              </div>
              <Button onClick={save} disabled={update.isPending || !dirty}>
                {update.isPending ? "저장 중…" : "저장"}
              </Button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}
