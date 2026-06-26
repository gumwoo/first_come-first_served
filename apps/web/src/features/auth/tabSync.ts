// 탭 간 인증 상태 동기화. 한 탭의 로그인/로그아웃을 다른 탭에 즉시 전파.
// 토큰은 싣지 않고 "이벤트"만 보낸다 → 받은 탭은 자신의 httpOnly 쿠키로 스스로 갱신(보안).

export type AuthBroadcast = "login" | "logout";

const CHANNEL = "flowticket-auth";
let channel: BroadcastChannel | null = null;

function getChannel(): BroadcastChannel | null {
  if (typeof window === "undefined" || typeof BroadcastChannel === "undefined") return null;
  if (!channel) channel = new BroadcastChannel(CHANNEL);
  return channel;
}

export function broadcastAuth(event: AuthBroadcast) {
  getChannel()?.postMessage(event);
}

/** 다른 탭의 인증 이벤트 구독. 해제 함수를 반환. */
export function onAuthBroadcast(handler: (e: AuthBroadcast) => void): () => void {
  const ch = getChannel();
  if (!ch) return () => {};
  const listener = (ev: MessageEvent) => handler(ev.data as AuthBroadcast);
  ch.addEventListener("message", listener);
  return () => ch.removeEventListener("message", listener);
}
