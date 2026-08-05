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

    // 연결이 끊긴 사이의 이벤트는 다시 오지 않는다. 브라우저가 재연결해도 그동안
    // 발생한 order.paid 를 놓치면 화면이 "결제 대기"에 머문 채 영영 갱신되지 않는다
    // (종료 상태라 후속 이벤트가 없다). 그래서 재연결 시점에 현재 상태를 다시 읽어
    // 공백을 메운다 — SSE는 트리거이고 진실원은 서버다(ADR-008).
    let opened = false;
    es.onopen = () => {
      if (opened) refresh(); // 최초 연결은 위 refresh()가 이미 처리했다
      opened = true;
    };
    // onerror에서 할 일은 없다. EventSource가 스스로 재연결하고, 복구는 onopen이 한다.
    es.onerror = () => {};

    return () => es.close();
  }, [orderId, refresh]);

  return { order, loading, error, refresh };
}
