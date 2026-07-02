"use client";

import { useEffect, useRef, useState } from "react";
import { ApiError } from "@/lib/apiClient";
import { useAuthStore } from "@/features/auth/store/authStore";
import * as queueApi from "@/features/queue/api/queue";

export type QueuePhase = "loading" | "waiting" | "admitted" | "expired" | "error";

/**
 * 대기열 진입 + 실시간(SSE) + 폴링 폴백. 상태를 phase로 노출.
 * admitted 시 redirect(좌석) 경로, waiting 시 rank/total/eta/progress.
 */
export function useQueue(eventId: number) {
  const accessToken = useAuthStore((s) => s.accessToken);
  const [phase, setPhase] = useState<QueuePhase>("loading");
  const [rank, setRank] = useState(0);
  const [total, setTotal] = useState(0);
  const [eta, setEta] = useState(0);
  const [redirect, setRedirect] = useState<string | null>(null);
  const initialRank = useRef<number | null>(null);

  useEffect(() => {
    if (!Number.isFinite(eventId)) return;
    let es: EventSource | null = null;
    let poll: ReturnType<typeof setInterval> | null = null;
    let cancelled = false;

    const apply = (s: { status: string; rank: number; total: number; etaSeconds?: number }) => {
      if (cancelled) return;
      if (s.status === "ADMITTED") return setPhase("admitted");
      if (s.status === "EXPIRED") return setPhase("expired");
      setPhase("waiting");
      setRank(s.rank);
      setTotal(s.total);
      if (s.etaSeconds != null) setEta(s.etaSeconds);
      if (initialRank.current == null && s.rank > 0) initialRank.current = s.rank;
    };

    (async () => {
      try {
        const t = await queueApi.issueQueueToken(eventId, accessToken);
        if (cancelled) return;
        apply(t);

        es = new EventSource(queueApi.queueSseUrl(t.token));
        es.addEventListener("queue.admitted", (e) => {
          try {
            const d = JSON.parse((e as MessageEvent).data);
            setRedirect(d.redirect ?? null);
          } catch {
            /* 데이터 없으면 무시 */
          }
          setPhase("admitted");
        });
        es.addEventListener("queue.expired", () => setPhase("expired"));
        // onerror는 폴링이 커버하므로 무시(재연결은 브라우저가 시도)

        poll = setInterval(async () => {
          try {
            apply(await queueApi.getQueueStatus(t.token));
          } catch (err) {
            if (err instanceof ApiError && err.code === "QUEUE_EXPIRED") {
              if (!cancelled) setPhase("expired");
            }
          }
        }, 2000);
      } catch {
        if (!cancelled) setPhase("error");
      }
    })();

    return () => {
      cancelled = true;
      es?.close();
      if (poll) clearInterval(poll);
    };
  }, [eventId, accessToken]);

  const progress =
    initialRank.current && initialRank.current > 0
      ? Math.min(100, Math.max(0, Math.round((1 - rank / initialRank.current) * 100)))
      : 0;

  return { phase, rank, total, eta, progress, redirect };
}
