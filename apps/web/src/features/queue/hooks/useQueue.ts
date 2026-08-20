"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "@/lib/apiClient";
import { useAuthStore } from "@/features/auth/store/authStore";
import * as queueApi from "@/features/queue/api/queue";

export type QueuePhase = "loading" | "waiting" | "admitted" | "expired" | "error";

/**
 * 대기열 상태 폴링의 **최소** 주기(ms). SSE가 아예 열리지 않는 환경(일부 프록시)에서는 이것이
 * 유일한 채널이므로 완전히 없앨 수는 없다([[ADR-015]] ③).
 *
 * <p>⚠️ E2E는 이 값을 600000(10분)으로 준다. **그 값은 운영 후보가 아니다** —
 * `admit-ttl`(300초)보다도 길어 운영에서는 성립하지 않는다. 폴링을 테스트 시간 밖으로
 * 밀어내 <b>재연결 시 onopen 재조회만으로 복구되는지를 격리 검증</b>하기 위한 값이다.
 * 폴링이 살아 있으면 그게 먼저 복구해버려 onopen 경로를 증명할 수 없다.
 */
const POLL_MIN_MS = Number(process.env.NEXT_PUBLIC_QUEUE_POLL_INTERVAL_MS) || 2000;

/**
 * SSE가 건강할 때 폴링이 늘어날 수 있는 **상한**(ms).
 *
 * <p>대기 인원이 늘면 폴링 부하가 그대로 따라 늘어난다 — 2초 고정이면 대기 1만 명이
 * 초당 5,000건이다. 선착순에서는 부하가 몰릴수록 대기자도 많아지므로, **부하가 스스로를
 * 키우는 되먹임**이 된다.
 *
 * <p>{@code POLL_MIN_MS}와의 최댓값을 취하는 이유: E2E가 최소 주기를 10분으로 밀어 놓았을 때
 * 상한이 그보다 작으면 백오프가 오히려 주기를 **줄여** 폴링을 테스트 창 안으로 되돌린다.
 */
const POLL_MAX_MS = Math.max(
  POLL_MIN_MS,
  Number(process.env.NEXT_PUBLIC_QUEUE_POLL_MAX_MS) || 15000
);

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
    let poll: ReturnType<typeof setTimeout> | null = null;
    // SSE 구독이 성립해 있는가. 폴링을 늘려도 되는지를 이 값 하나로 판단한다.
    let sseHealthy = false;
    let pollDelay = POLL_MIN_MS;
    let cancelled = false;

    // admitted·expired는 되돌아가지 않는 종료 상태다. 확정되면 진행 중인 폴링 응답을
    // 전부 무시하고 폴링 자체도 멈춘다 — 그러지 않으면 SSE로 admitted를 받은 직후
    // 이미 떠 있던 폴링이 WAITING을 들고 도착해 phase를 waiting으로 되돌린다.
    // (폴링 순번(latest)만으로는 못 막는다. SSE 이벤트는 순번을 올리지 않기 때문이다.)
    let terminal = false;

    const settle = (next: "admitted" | "expired") => {
      terminal = true;
      if (poll) {
        clearTimeout(poll);
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
        es.onopen = () => {
          sseHealthy = true;
          refresh();
        };
        // 재연결은 여전히 브라우저가 하고 복구는 onopen이 한다 — 그 설계는 그대로다.
        // 여기서 하는 일은 **폴링을 다시 촘촘하게 되돌리는 것**뿐이다. 연결이 끊긴 구간이
        // 정확히 이벤트를 놓치는 구간이므로, 그동안에는 안전망이 촘촘해야 한다.
        es.onerror = () => {
          sseHealthy = false;
          pollDelay = POLL_MIN_MS;
        };

        // 최종 안전망. setInterval이 아니라 매번 다시 예약하는 이유는 **주기가 변하기 때문**이다.
        //
        // SSE가 성립해 있는 동안에는 주기를 배로 늘린다(상한 POLL_MAX_MS). 재연결 공백은
        // onopen 재조회가 닫으므로(#238), 그 구간의 복구를 폴링에 의존하지 않아도 된다.
        // SSE가 한 번도 열리지 않았거나 끊긴 동안에는 최소 주기를 유지한다 — 그때는 폴링이
        // 유일한 복구 경로다.
        //
        // ⚠️ 대가: SSE가 **열려 있는데도** 이벤트가 유실되는 경우의 복구가 최대 POLL_MAX_MS까지
        // 늦어진다. 연결이 살아 있으면 서버가 그 연결로 보내므로(QueueSseRegistry.deliverLocal)
        // 흔한 경우는 아니지만, 0은 아니다.
        const schedulePoll = () => {
          if (terminal || cancelled) return;
          poll = setTimeout(async () => {
            await refresh();
            if (sseHealthy) pollDelay = Math.min(pollDelay * 2, POLL_MAX_MS);
            schedulePoll();
          }, pollDelay);
        };
        if (!terminal) {
          schedulePoll();
        }
      } catch {
        if (!cancelled) setPhase("error");
      }
    })();

    return () => {
      cancelled = true;
      es?.close();
      if (poll) clearTimeout(poll);
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
