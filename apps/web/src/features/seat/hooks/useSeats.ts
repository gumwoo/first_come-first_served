"use client";

import { useCallback, useEffect, useState } from "react";
import * as seatApi from "@/features/seat/api/seat";

/** 좌석맵 조회 + SSE(seat.hold.expired 시 재고 복구 반영). */
export function useSeats(eventId: number) {
  const [map, setMap] = useState<seatApi.SeatMap | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  const refresh = useCallback(async () => {
    try {
      setMap(await seatApi.getSeats(eventId));
    } catch {
      setError(true);
    } finally {
      setLoading(false);
    }
  }, [eventId]);

  useEffect(() => {
    if (!Number.isFinite(eventId)) return;
    refresh();
    const es = new EventSource(seatApi.seatSseUrl(eventId));
    es.addEventListener("seat.hold.expired", () => refresh()); // 좌석 풀리면 재조회
    es.onerror = () => {
      /* 재조회로 커버 */
    };
    return () => es.close();
  }, [eventId, refresh]);

  return { map, loading, error, refresh };
}
