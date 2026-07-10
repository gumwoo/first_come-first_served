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
    es.onerror = () => {
      /* 폴링/재조회로 커버 */
    };
    return () => es.close();
  }, [orderId, refresh]);

  return { order, loading, error, refresh };
}
