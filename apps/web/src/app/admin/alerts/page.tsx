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
    if (alerts.data?.outboxDeadThreshold != null) setValue(String(alerts.data.outboxDeadThreshold));
  }, [alerts.data]);

  const a = alerts.data;
  // 아웃박스 임계치가 없는 응답(P2a 이전 API)이면 저장하지 못하므로 입력·저장을 막는다. 지금 공존할 수 있는 API(P2a 이후)에는 항상 있다.
  const supported = a?.outboxDeadThreshold != null;
  const dirty = a && supported && value !== String(a.outboxDeadThreshold);

  const save = () => {
    const n = Number(value);
    if (Number.isFinite(n) && n >= 0 && supported) {
      update.mutate({ outboxDeadThreshold: n });
    }
  };
  return (
    <div className="mx-auto max-w-2xl space-y-6 px-6 py-8">
      <PageHeader title="알림" desc="운영 경고 임계치를 설정합니다." />

      {alerts.isLoading || !a ? (
        <Skeleton className="h-40 w-full rounded-lg" />
      ) : (
        <>
          {a.outboxBreached && (
            <div className="rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700 dark:border-rose-500/30 dark:bg-rose-500/10 dark:text-rose-400">
              현재 격리된 아웃박스 이벤트 {a.outboxDead}건이 임계치({a.outboxDeadThreshold})를 넘었습니다.
            </div>
          )}

          <div className="rounded-lg border border-border">
            <div className="flex items-center justify-between border-b border-border px-5 py-4">
              <div>
                <p className="text-sm font-medium">아웃박스 격리 경고</p>
                <p className="mt-0.5 text-xs text-muted-foreground">재시도로 풀리지 않아 격리(DEAD)된 이벤트가 이 값 이상이면 경고합니다.</p>
              </div>
              <span className="text-sm text-muted-foreground">현재 격리 <span className="font-medium text-foreground">{a.outboxDead}건</span></span>
            </div>
            <div className="flex items-end gap-3 px-5 py-4">
              <div className="space-y-1">
                <Label htmlFor="th" className="text-xs text-muted-foreground">경고 임계치</Label>
                <Input id="th" type="number" min={0} value={value} disabled={!supported} onChange={(e) => setValue(e.target.value)} className="h-9 w-32" />
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
