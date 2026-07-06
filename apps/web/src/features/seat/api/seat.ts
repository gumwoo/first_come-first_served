import { api } from "@/lib/apiClient";

export type GradeInfo = { grade: string; price: number; total: number; available: number };
export type SeatInfo = {
  id: number;
  grade: string;
  zone: string | null;
  seatRow: string | null;
  seatCol: number | null;
  status: string; // AVAILABLE / HELD / SOLD
};
export type SeatMap = { eventId: number; grades: GradeInfo[]; seats: SeatInfo[] };

export type HoldResult = { holdId: number; seatIds: number[]; total: number; expiresAt: string };

export const getSeats = (eventId: number) => api<SeatMap>(`/events/${eventId}/seats`);

/** 선점(회원 + 대기열 입장 토큰). */
export const holdSeats = (
  eventId: number,
  seatIds: number[],
  queueToken: string,
  token: string | null
) =>
  api<HoldResult>(`/events/${eventId}/seats/hold`, {
    method: "POST",
    token,
    body: { seatIds, queueToken },
  });

export const releaseHold = (holdId: number, token: string | null) =>
  api<null>(`/seats/hold/${holdId}`, { method: "DELETE", token });

/** 좌석맵 실시간 SSE(이벤트별). seat.hold.expired 시 재고 복구 반영. */
export const seatSseUrl = (eventId: number) => `/api/sse/events/${eventId}/seats`;
