import { api } from "@/lib/apiClient";

export type QueueToken = { token: string; status: string; rank: number; total: number };
export type QueueStatus = { rank: number; total: number; etaSeconds: number; status: string };

/** 대기 진입(회원). accessToken이 null이어도 apiClient가 401→refresh 재시도. */
export const issueQueueToken = (eventId: number, token: string | null) =>
  api<QueueToken>(`/events/${eventId}/queue/token`, { method: "POST", token });

/** 상태 폴링(토큰). SSE 폴백. */
export const getQueueStatus = (queueToken: string) =>
  api<QueueStatus>(`/queue/status?token=${encodeURIComponent(queueToken)}`);

/** SSE 스트림 URL(/api 프록시 경유, 토큰이 접근 비밀값). */
export const queueSseUrl = (queueToken: string) => `/api/sse/queue/${queueToken}`;
