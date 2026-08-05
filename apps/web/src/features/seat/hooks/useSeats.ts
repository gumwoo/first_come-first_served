"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import * as seatApi from "@/features/seat/api/seat";

/** 좌석맵 조회 + SSE(seat.hold.expired 시 재고 복구 반영). */
export function useSeats(eventId: number) {
  const [map, setMap] = useState<seatApi.SeatMap | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const latest = useRef(0);

  // 좌석 이벤트 3종이 연달아 오면 refresh()가 겹친다. 응답 순서는 보장되지 않으므로
  // 순번으로 가장 마지막 요청의 응답만 반영한다 — 낡은 좌석맵이 최신을 덮으면
  // 이미 팔린 자리를 다시 선택 가능한 것처럼 보여준다(useOrder와 같은 이유).
  const refresh = useCallback(async () => {
    const seq = ++latest.current;
    try {
      const next = await seatApi.getSeats(eventId);
      if (seq !== latest.current) return;
      setMap(next);
      setError(false);
    } catch {
      if (seq !== latest.current) return;
      setError(true);
    } finally {
      if (seq === latest.current) setLoading(false);
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

    // 구독하지 않은 구간의 선점/해제는 다시 오지 않아 좌석맵이 낡은 채로 남는다
    // (이미 팔린 자리를 선택 가능한 것처럼 보여주고, 선택하면 409로 튕긴다).
    // 서버의 조건부 UPDATE가 막으므로 초과판매로 이어지지는 않지만 화면은 되돌려야 한다.
    // 공백은 ① 최초 조회~구독 성립 ② 재연결 대기 두 곳에서 생기므로,
    // 최초/재연결을 구분하지 않고 구독이 성립할 때마다 다시 읽는다(useOrder와 같은 이유).
    es.onopen = () => refresh();
    es.onerror = () => {};

    return () => es.close();
  }, [eventId, refresh]);

  return { map, loading, error, refresh };
}
