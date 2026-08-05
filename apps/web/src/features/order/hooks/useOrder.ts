"use client";

import { useCallback, useEffect, useState } from "react";
import * as orderApi from "@/features/order/api/order";

/** 주문 조회 + SSE(order.paid / payment.vbank.deposited / order.failed 시 재조회). */
export function useOrder(orderId: number, token: string | null) {
  const [order, setOrder] = useState<orderApi.Order | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  const refresh = useCallback(async () => {
    try {
      setOrder(await orderApi.getOrder(orderId, token));
    } catch {
      setError(true);
    } finally {
      setLoading(false);
    }
  }, [orderId, token]);

  useEffect(() => {
    if (!Number.isFinite(orderId)) return;
    refresh();
    const es = new EventSource(orderApi.orderSseUrl(orderId));
    es.addEventListener("order.paid", () => refresh());
    es.addEventListener("payment.vbank.deposited", () => refresh());
    es.addEventListener("order.failed", () => refresh());

    // SSE 이벤트는 재전송되지 않는다. 구독하지 않은 구간에 order.paid 가 지나가면
    // 화면이 "결제 대기"에 머문 채 영영 갱신되지 않는다 — 종료 상태라 후속 이벤트가 없다.
    // 구독 공백은 두 군데에서 생긴다:
    //   ① 위 refresh() 응답 ~ 구독 성립 사이 (특히 PG 리다이렉트 직후 마운트될 때 위험)
    //   ② 연결이 끊겼다 브라우저가 자동 재연결하기까지
    // 둘 다 "구독이 성립한 시점"에 다시 읽으면 닫힌다. 그래서 최초/재연결을 구분하지 않고
    // 모든 onopen에서 재조회한다 — 마운트 시 GET이 한 번 중복되지만 그 대가가 더 싸다.
    // (위 refresh()는 SSE가 아예 열리지 않는 경우의 폴백이라 없앨 수 없다.)
    // SSE는 트리거이고 진실원은 서버다(ADR-008).
    es.onopen = () => refresh();
    // onerror에서 할 일은 없다. EventSource가 스스로 재연결하고, 복구는 onopen이 한다.
    es.onerror = () => {};

    return () => es.close();
  }, [orderId, refresh]);

  return { order, loading, error, refresh };
}
