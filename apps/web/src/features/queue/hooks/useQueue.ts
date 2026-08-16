"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "@/lib/apiClient";
import { useAuthStore } from "@/features/auth/store/authStore";
import * as queueApi from "@/features/queue/api/queue";

export type QueuePhase = "loading" | "waiting" | "admitted" | "expired" | "error";

/**
 * 대기열 상태 폴링 주기(ms). **환경별로 조정 가능한 운영 파라미터**다 —
 * SSE가 아예 열리지 않는 환경(일부 프록시)을 위한 최종 안전망이라 완전히 없앨 수는 없고,
 * 얼마나 자주 돌지는 부하와 반응성의 트레이드오프라 환경마다 다를 수 있다([[ADR-015]] ③).
 *
 * <p>현재 기본값 2000ms는 **운영에서 아직 바꾸지 않았다.** ③의 최종 주기는
 * `/queue/status`의 요청당 비용과 사용자 반응성을 재고 나서 정한다.
 *
 * <p>⚠️ E2E는 이 값을 600000(10분)으로 준다. **그 값은 운영 후보가 아니다** —
 * `admit-ttl`(300초)보다도 길어 운영에서는 성립하지 않는다. 폴링을 테스트 시간 밖으로
 * 밀어내 <b>재연결 시 onopen 재조회만으로 복구되는지를 격리 검증</b>하기 위한 값이다.
 * 폴링이 살아 있으면 그게 먼저 복구해버려 onopen 경로를 증명할 수 없다.
 */
const POLL_INTERVAL_MS = Number(process.env.NEXT_PUBLIC_QUEUE_POLL_INTERVAL_MS) || 2000;

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

    // admitted·expired는 되돌아가지 않는 종료 상태다. 확정되면 진행 중인 폴링 응답을
    // 전부 무시하고 폴링 자체도 멈춘다 — 그러지 않으면 SSE로 admitted를 받은 직후
    // 이미 떠 있던 폴링이 WAITING을 들고 도착해 phase를 waiting으로 되돌린다.
    // (폴링 순번(latest)만으로는 못 막는다. SSE 이벤트는 순번을 올리지 않기 때문이다.)
    let terminal = false;

    const settle = (next: "admitted" | "expired") => {
      terminal = true;
      if (poll) {
        clearInterval(poll);
        poll = null;
      }
      if (!cancelled) setPhase(next);
    };

    const apply = (s: { status: string; rank: number; total: number; etaSeconds?: number }) => {
      if (cancelled || terminal) return;
      if (s.status === "ADMITTED") return settle("admitted");
      if (s.status === "EXPIRED") return settle("expired");
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

        // 상태 재조회 — 폴링과 onopen 재동기화가 **같은 순번 가드를 공유한다.**
        // 따로 두면 재연결 직후의 조회가 느릴 때 그 사이 도착한 최신 폴링 응답을 덮어쓴다.
        // 성공·실패 양쪽에 같은 검사를 둔다 — 한쪽만 막으면 낡은 오류 응답이 최신 성공을 덮는다.
        let latest = 0;
        const refresh = async () => {
          if (terminal) return;
          const seq = ++latest;
          try {
            const s = await queueApi.getQueueStatus(t.token);
            if (terminal || seq !== latest) return;
            apply(s);
          } catch (err) {
            if (terminal || seq !== latest) return;
            if (err instanceof ApiError && err.code === "QUEUE_EXPIRED") settle("expired");
          }
        };

        es = new EventSource(queueApi.queueSseUrl(t.token));
        es.addEventListener("queue.admitted", (e) => {
          try {
            const d = JSON.parse((e as MessageEvent).data);
            setRedirect(d.redirect ?? null);
          } catch {
            /* 데이터 없으면 무시 */
          }
          settle("admitted");
        });
        es.addEventListener("queue.expired", () => settle("expired"));
        // 구독이 성립한 시점마다 다시 읽는다 — useOrder·useSeats와 같은 규칙이다(ADR-008).
        //
        // SSE 이벤트는 재전송되지 않는다: 서버는 그 순간 연결이 없으면 그냥 버린다
        // (QueueSseRegistry.deliverLocal). 따라서 연결이 끊긴 사이 승격되면
        // queue.admitted 이벤트 자체는 놓칠 수 있다.
        //
        // 현재는 2초 폴링이 /queue/status를 다시 읽어 이 공백을 복구한다.
        // onopen 재조회는 이 복구를 SSE 재연결 경로에도 만들어,
        // 이후 폴링 주기를 늘릴 수 있게 하는 선행 조건이다([[ADR-015]]).
        es.onopen = () => refresh();
        // onerror에서 할 일은 없다. EventSource가 스스로 재연결하고, 복구는 onopen이 한다.
        es.onerror = () => {};

        // 최종 안전망 — SSE가 아예 열리지 않는 환경(일부 프록시)에서는 이것만 남는다.
        // 주기는 위 POLL_INTERVAL_MS 참고(운영 기본 2000ms, ADR-015 ③에서 측정 후 조정).
        if (!terminal) {
          poll = setInterval(refresh, POLL_INTERVAL_MS);
        }
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
