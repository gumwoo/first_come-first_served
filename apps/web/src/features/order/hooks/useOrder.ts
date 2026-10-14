"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import * as orderApi from "@/features/order/api/order";

/** 티켓 만료로 브라우저가 재연결을 포기한 뒤 다시 붙기까지의 대기. */
const SSE_RETRY_DELAY_MS = 2000;

/**
 * PENDING 동안의 재조회 간격. SSE가 붙어 있어도 이벤트 하나가 빠질 수 있다(파드의 Redis 구독 연결 순간 단절,
 * 전송 실패 — kafka-removal-design §4). 종료 이벤트라 다시 오지 않으므로, 진행 중 상태에서만 서버 상태로 되맞춘다.
 * 정원 100명 × 5초 간격이면 공연당 최대 약 20 req/s(계산), 주문 하나에 만료(약 5분)까지 최대 약 60회.
 */
const PENDING_POLL_MS = 5000;

/** 탭으로 돌아왔을 때 재조회할 진행 중 상태. VBANK_WAITING은 입금까지 길 수 있어 주기 재조회 없이 복귀 때만. */
const REFRESH_ON_RETURN = new Set(["PENDING", "VBANK_WAITING"]);

/** visibilitychange와 focus가 연달아 오면 한 번만 재조회한다. */
const RETURN_REFRESH_DEDUP_MS = 1000;

/** 주문 조회 + SSE(order.paid / payment.vbank.deposited / order.failed 시 재조회). */
export function useOrder(orderId: number, token: string | null) {
  const [order, setOrder] = useState<orderApi.Order | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const latest = useRef(0);

  // refresh()는 여러 곳에서 겹쳐 호출된다. 마운트, onopen, 이벤트 3종, 그리고 반환값을
  // 받은 화면. 먼저 보낸 요청이 먼저 도착한다는 보장이 없으므로(첫 요청은 커넥션을 새로
  // 맺느라 더 느릴 수 있다), 순번을 붙여 가장 마지막에 시작한 요청의 응답만 반영한다.
  // 이게 없으면 낡은 PENDING 응답이 뒤늦게 도착해 PAID를 덮어쓰고, order.paid는 종료
  // 이벤트라 다시 오지 않으므로 화면이 영영 고착된다.
  const refresh = useCallback(async () => {
    const seq = ++latest.current;
    try {
      const next = await orderApi.getOrder(orderId, token);
      if (seq !== latest.current) return;
      setOrder(next);
      setError(false); // 일시적 실패 뒤 성공하면 에러 상태를 푼다
    } catch {
      if (seq !== latest.current) return;
      setError(true);
    } finally {
      if (seq === latest.current) setLoading(false);
    }
  }, [orderId, token]);

  useEffect(() => {
    if (!Number.isFinite(orderId)) return;
    refresh();

    let es: EventSource | null = null;
    let closed = false;
    let retry: ReturnType<typeof setTimeout> | null = null;

    const connect = async () => {
      // 토큰이 없으면 티켓 발급은 반드시 401이다. 그런데 apiClient는 401을 만나면 자동으로
      // silent refresh를 돌린다. 즉 보내봐야 실패하는 요청이 RTR 회전을 하나 더 유발한다.
      // 마운트 직후에는 위 refresh()도 같은 이유로 401을 받을 수 있어, 둘이 동시에 회전을
      // 요청하면 한쪽이 REFRESH_TOKEN_REUSED를 받고 세션이 초기화될 수 있다. 그러면 토큰이
      // null이 되어 이 효과가 다시 돌고, 그 사이 error가 토글되며 화면이 로딩↔에러로 왕복한다.
      // 토큰이 채워지면 이 효과가 다시 실행되므로(deps에 token), 여기서는 그냥 기다린다.
      if (closed || !token) return;
      let ticket: string;
      try {
        // 구독 자격은 티켓으로 받는다. EventSource는 Authorization 헤더를 붙이지 못한다.
        ({ ticket } = await orderApi.issueSseTicket(orderId, token));
      } catch {
        // 티켓을 못 받으면 푸시는 포기한다. 화면은 위 refresh()가 이미 채웠고,
        // 진실원은 서버라 SSE가 없다고 값이 틀리지는 않는다(ADR-008).
        return;
      }
      if (closed) return;

      es = new EventSource(orderApi.orderSseUrl(orderId, ticket));
      es.addEventListener("order.paid", () => refresh());
      es.addEventListener("payment.vbank.deposited", () => refresh());
      es.addEventListener("order.failed", () => refresh());

      // SSE 이벤트는 재전송되지 않는다. 구독하지 않은 구간에 order.paid 가 지나가면
      // 화면이 "결제 대기"에 머문 채 영영 갱신되지 않는다. 종료 상태라 후속 이벤트가 없다.
      // 구독 공백은 두 군데에서 생긴다:
      //   1) 위 refresh() 응답 ~ 구독 성립 사이 (특히 PG 리다이렉트 직후 마운트될 때 위험)
      //   2) 연결이 끊겼다 재연결이 성립하기까지
      // 둘 다 "구독이 성립한 시점"에 다시 읽으면 닫힌다. 그래서 최초/재연결을 구분하지 않고
      // 모든 onopen에서 재조회한다. 마운트 시 GET이 한 번 중복되지만 그 대가가 더 싸다.
      // SSE는 트리거이고 진실원은 서버다(ADR-008).
      es.onopen = () => refresh();

      // 브라우저가 재연결을 포기했을 때만 개입한다.
      // 일시적 단절(ALB idle timeout 등)에서는 readyState가 CONNECTING이고 브라우저가 알아서
      // 다시 붙는다. 그 경로는 그대로 둔다. 반면 티켓이 만료되면 서버가 401을 주고,
      // EventSource는 2xx가 아닌 응답에 재연결을 포기하며 CLOSED가 된다. 그때는 우리가
      // 새 티켓을 받아 다시 열어야 한다. 이 구분이 없으면 둘 중 하나가 깨진다.
      // 항상 개입하면 60초마다 티켓을 새로 받고, 전혀 개입하지 않으면 만료 후 영영 끊긴다.
      es.onerror = () => {
        if (es?.readyState !== EventSource.CLOSED || closed) return;
        es.close();
        es = null;
        retry = setTimeout(connect, SSE_RETRY_DELAY_MS);
      };
    };

    void connect();

    return () => {
      closed = true;
      if (retry) clearTimeout(retry);
      es?.close();
    };
  }, [orderId, token, refresh]);

  // 진행 중 상태의 보완 재조회. SSE를 대신하지 않는다 — 알림이 빠졌을 때 화면이 이전 상태에 멈추지 않게 하는 마지막 장치다.
  // - PENDING: 탭이 보이는 동안 5초마다, 주문 만료 시각까지.
  // - PENDING·VBANK_WAITING: 탭으로 돌아오면 1회.
  // - 그 밖의 상태(PAID·FAILED·EXPIRED 등 종료)가 되면 아무것도 하지 않는다(상태가 바뀌면 이 효과가 정리된다).
  const status = order?.status;
  const expiresAt = order?.expiresAt;
  useEffect(() => {
    if (!status || !REFRESH_ON_RETURN.has(status)) return;
    const expiresAtMs = expiresAt ? new Date(expiresAt).getTime() : NaN;
    let timer: ReturnType<typeof setInterval> | null = null;
    let lastReturnRefresh = 0;

    const stop = () => {
      if (timer) clearInterval(timer);
      timer = null;
    };
    const expired = () => Number.isFinite(expiresAtMs) && Date.now() >= expiresAtMs;
    const start = () => {
      if (status !== "PENDING" || timer || document.visibilityState === "hidden" || expired()) return;
      timer = setInterval(() => {
        if (expired()) {
          stop();
          return;
        }
        refresh();
      }, PENDING_POLL_MS);
    };
    const onReturn = () => {
      if (document.visibilityState === "hidden") {
        stop();
        return;
      }
      const now = Date.now();
      if (now - lastReturnRefresh >= RETURN_REFRESH_DEDUP_MS) {
        lastReturnRefresh = now;
        refresh();
      }
      start();
    };

    start();
    document.addEventListener("visibilitychange", onReturn);
    window.addEventListener("focus", onReturn);
    return () => {
      stop();
      document.removeEventListener("visibilitychange", onReturn);
      window.removeEventListener("focus", onReturn);
    };
  }, [status, expiresAt, refresh]);

  return { order, loading, error, refresh };
}
