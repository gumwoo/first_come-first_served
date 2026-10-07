import { api } from "@/lib/apiClient";

/** retryAfterMs: 다음 상태 조회까지 최소 대기(ms) — 서버가 순번으로 정한다. 구버전 서버 응답에는 없다. */
export type QueueToken = { token: string; status: string; rank: number; total: number; retryAfterMs?: number };
export type QueueStatus = { rank: number; total: number; etaSeconds: number; status: string; retryAfterMs?: number };

/** 대기 진입(회원). accessToken이 null이어도 apiClient가 401→refresh 재시도. */
export const issueQueueToken = (eventId: number, token: string | null) =>
  api<QueueToken>(`/events/${eventId}/queue/token`, { method: "POST", token });

/** 상태 폴링(토큰). 대기 상태 전달의 주 경로(ADR-023 §2). */
export const getQueueStatus = (queueToken: string) =>
  api<QueueStatus>(`/queue/status?token=${encodeURIComponent(queueToken)}`);

/** 대기열 이탈(나가기): 대기/입장 슬롯 정리. */
export const leaveQueue = (queueToken: string, token: string | null) =>
  api<null>(`/queue/token?token=${encodeURIComponent(queueToken)}`, { method: "DELETE", token });
