"use client";

import { useEffect } from "react";
import { refresh, getMe } from "@/features/auth/api/auth";
import { useAuthStore } from "@/features/auth/store/authStore";
import { setTokenRefresher } from "@/lib/apiClient";

/**
 * 앱 로드 시 httpOnly refresh 쿠키로 Access를 silent 재발급(로그인 유지) +
 * apiClient에 401 자동 재발급용 refresher 등록(세션 중 만료 대응).
 */
export function AuthBootstrap() {
  const { setAccessToken, setUser } = useAuthStore();

  useEffect(() => {
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

    // 로드 시 1회 silent 복원 + 프로필
    refresh()
      .then(async (r) => {
        setAccessToken(r.accessToken);
        try {
          setUser(await getMe(r.accessToken));
        } catch {
          /* ignore */
        }
      })
      .catch(() => {
        /* 미로그인 또는 만료 — 무시 */
      });

    return () => setTokenRefresher(null);
  }, [setAccessToken, setUser]);

  return null;
}
