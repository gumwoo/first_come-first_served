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
    // 다른 사용자의 선점/해제/만료를 실시간 반영(재고 최신화).
    es.addEventListener("seat.held", () => refresh());
    es.addEventListener("seat.hold.released", () => refresh());
    es.addEventListener("seat.hold.expired", () => refresh());
    es.onerror = () => {
      /* 재조회로 커버 */
    };
    return () => es.close();
  }, [eventId, refresh]);

  return { map, loading, error, refresh };
}
