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

    // 재연결 공백 동안의 선점/해제는 다시 오지 않는다. 좌석맵이 낡은 채로 남으면
    // 이미 팔린 자리를 선택 가능한 것처럼 보여준다(선택 후 409로 튕긴다).
    // 정합성은 서버의 조건부 UPDATE가 지키므로 초과판매로 이어지지는 않지만,
    // 화면을 최신으로 되돌리려면 재연결 시 다시 읽어야 한다.
    let opened = false;
    es.onopen = () => {
      if (opened) refresh(); // 최초 연결은 위 refresh()가 이미 처리했다
      opened = true;
    };
    es.onerror = () => {};

    return () => es.close();
  }, [eventId, refresh]);

  return { map, loading, error, refresh };
}
