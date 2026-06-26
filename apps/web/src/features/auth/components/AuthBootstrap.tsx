"use client";

import { useEffect } from "react";
import { refresh, getMe } from "@/features/auth/api/auth";
import { useAuthStore } from "@/features/auth/store/authStore";
import { setTokenRefresher } from "@/lib/apiClient";
import { onAuthBroadcast } from "@/features/auth/tabSync";

/**
 * 앱 로드 시 silent 재발급 + apiClient 401 refresher 등록 +
 * 탭 간 인증 이벤트 구독(다른 탭 로그인/로그아웃을 즉시 반영).
 */
export function AuthBootstrap() {
  const { setAccessToken, setUser } = useAuthStore();

  useEffect(() => {
    // httpOnly refresh 쿠키로 Access + 프로필 복원
    const restore = async () => {
      try {
        const r = await refresh();
        setAccessToken(r.accessToken);
        try {
          setUser(await getMe(r.accessToken));
        } catch {
          /* ignore */
        }
      } catch {
        setAccessToken(null);
        setUser(null);
      }
    };

    // 401 발생 시 apiClient가 호출할 토큰 재발급기
    setTokenRefresher(async () => {
      try {
        const r = await refresh();
        setAccessToken(r.accessToken);
        return r.accessToken;
      } catch {
        setAccessToken(null);
        setUser(null);
        return null;
      }
    });

    restore();

    // 다른 탭의 로그인/로그아웃을 즉시 반영
    const unsub = onAuthBroadcast((e) => {
      if (e === "logout") {
        setAccessToken(null);
        setUser(null);
      } else if (e === "login") {
        restore(); // 자기 쿠키로 스스로 복원(토큰은 전파받지 않음)
      }
    });

    return () => {
      setTokenRefresher(null);
      unsub();
    };
  }, [setAccessToken, setUser]);

  return null;
}
