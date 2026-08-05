"use client";

import { useCallback, useEffect, useRef, useState } from "react";
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
  const [queueToken, setQueueToken] = useState<string | null>(null);
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
        setQueueToken(t.token);
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
        // 여기만 onopen 재조회가 없다 — 아래 2초 폴링이 재연결 공백을 이미 메우기 때문이다.
        // (useOrder·useSeats에는 폴링이 없어 onopen에서 직접 다시 읽는다.)
        es.onerror = () => {};

        // 응답이 2초를 넘으면 폴링끼리 겹친다. 낡은 응답이 뒤늦게 도착하면 apply가
        // phase를 "waiting"으로 되돌려 admitted를 취소시킨다(다음 폴링이 2초 뒤 고쳐주지만
        // 그 사이 화면이 튄다). 순번으로 마지막 요청의 응답만 반영한다 — useOrder와 같은 이유.
        let latest = 0;
        poll = setInterval(async () => {
          const seq = ++latest;
          try {
            const s = await queueApi.getQueueStatus(t.token);
            if (seq === latest) apply(s);
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

  /** 나가기 — 대기열에서 실제로 이탈(슬롯 정리). best-effort. */
  const leave = useCallback(async () => {
    if (!queueToken) return;
    try {
      await queueApi.leaveQueue(queueToken, accessToken);
    } catch {
      /* 이미 만료/정리됐을 수 있음 — 무시 */
    }
  }, [queueToken, accessToken]);

  return { phase, rank, total, eta, progress, redirect, queueToken, leave };
}
