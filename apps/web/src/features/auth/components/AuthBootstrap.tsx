"use client";

import { useEffect } from "react";
import { refresh } from "@/features/auth/api/auth";
import { useAuthStore } from "@/features/auth/store/authStore";

/** 앱 로드 시 httpOnly refresh 쿠키로 Access를 silent 재발급(로그인 상태 유지). */
export function AuthBootstrap() {
  const setAccessToken = useAuthStore((s) => s.setAccessToken);
  useEffect(() => {
    refresh()
      .then((r) => setAccessToken(r.accessToken))
      .catch(() => {
        /* 미로그인 또는 만료 — 무시 */
      });
  }, [setAccessToken]);
  return null;
}
