"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "@/lib/apiClient";
import { useAuthStore } from "@/features/auth/store/authStore";
import * as queueApi from "@/features/queue/api/queue";

export type QueuePhase = "loading" | "waiting" | "admitted" | "expired" | "error";

/**
 * 다음 상태 조회까지 클라이언트가 지키는 최소 간격(ms). 서버가 준 `retryAfterMs`가 이보다 짧거나 없으면 이 값을 쓴다
 * (구버전 서버 응답에는 retryAfterMs가 없다).
 */
const POLL_MIN_MS = Number(process.env.NEXT_PUBLIC_QUEUE_POLL_INTERVAL_MS) || 2000;

/** 같은 시각에 몰리지 않게 대기 시간에 더하는 무작위 폭(대기 시간의 비율). 음수는 없다 — 서버가 정한 최소 대기를 지킨다. */
const JITTER_RATIO = 0.2;

/** 조회가 실패(네트워크·5xx)할 때 늘려 가는 간격의 상한(ms). 실패해도 폴링은 멈추지 않는다 — 폴링이 유일한 경로다. */
const ERROR_BACKOFF_MAX_MS = 30_000;

/** 다음 조회까지 기다릴 시간: max(최소 간격, 서버 최소 대기) + [0, 20%) 무작위. */
export function nextPollDelay(retryAfterMs: number | undefined, random: () => number = Math.random): number {
  const base = Math.max(POLL_MIN_MS, retryAfterMs ?? 0);
  return base + Math.floor(random() * base * JITTER_RATIO);
}

/**
 * 대기열 진입 + 상태 폴링(ADR-023 §2 — 대기 상태 전달은 폴링이 주 경로, 대기열 SSE는 제거).
 *
 * 다음 조회 시각은 서버가 정한다: 진입·상태 응답의 `retryAfterMs`(순번이 앞이면 2초, 뒤면 최대 30초)는
 * "이보다 일찍 다시 묻지 마라"는 최소 대기다. 여기에 jitter를 더해 대기자 전원이 같은 시각에 묻지 않게 한다.
 * 입장(ADMITTED)·만료(EXPIRED)는 되돌아가지 않는 종료 상태라 확정되면 폴링을 멈춘다.
 */
export function useQueue(eventId: number) {
  const accessToken = useAuthStore((s) => s.accessToken);
  const [phase, setPhase] = useState<QueuePhase>("loading");
  const [rank, setRank] = useState(0);
  const [total, setTotal] = useState(0);
  const [eta, setEta] = useState(0);
  const [queueToken, setQueueToken] = useState<string | null>(null);
  const initialRank = useRef<number | null>(null);

  useEffect(() => {
    if (!Number.isFinite(eventId)) return;
    let poll: ReturnType<typeof setTimeout> | null = null;
    let cancelled = false;
    let terminal = false;
    let errorDelay = 0; // 연속 실패 시 늘려 가는 간격(성공하면 0으로)

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

    // 예약은 항상 하나만 유지한다(겹치면 같은 사용자가 두 배로 묻는다).
    const schedule = (delayMs: number, token: string) => {
      if (terminal || cancelled) return;
      if (poll) clearTimeout(poll);
      poll = setTimeout(() => {
        poll = null;
        void refresh(token);
      }, delayMs);
    };

    const refresh = async (token: string) => {
      if (terminal || cancelled) return;
      try {
        const s = await queueApi.getQueueStatus(token);
        errorDelay = 0;
        apply(s);
        schedule(nextPollDelay(s.retryAfterMs), token);
      } catch (err) {
        if (err instanceof ApiError && err.code === "QUEUE_EXPIRED") return settle("expired");
        // 일시 오류: 간격을 두 배씩(상한 30초) 늘리며 계속 묻는다.
        errorDelay = Math.min(Math.max(errorDelay * 2, POLL_MIN_MS), ERROR_BACKOFF_MAX_MS);
        schedule(nextPollDelay(errorDelay), token);
      }
    };

    (async () => {
      try {
        const t = await queueApi.issueQueueToken(eventId, accessToken);
        if (cancelled) return;
        setQueueToken(t.token);
        apply(t);
        schedule(nextPollDelay(t.retryAfterMs), t.token);
      } catch {
        if (!cancelled) setPhase("error");
      }
    })();

    return () => {
      cancelled = true;
      if (poll) clearTimeout(poll);
    };
  }, [eventId, accessToken]);

  const progress =
    initialRank.current && initialRank.current > 0
      ? Math.min(100, Math.max(0, Math.round((1 - rank / initialRank.current) * 100)))
      : 0;

  /** 나가기: 대기열에서 실제로 이탈(슬롯 정리). best-effort. */
  const leave = useCallback(async () => {
    if (!queueToken) return;
    try {
      await queueApi.leaveQueue(queueToken, accessToken);
    } catch {
      /* 이미 만료/정리됐을 수 있음: 무시 */
    }
  }, [queueToken, accessToken]);

  return { phase, rank, total, eta, progress, queueToken, leave };
}
