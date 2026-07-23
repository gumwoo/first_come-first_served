"use client";

import Link from "next/link";
import { ShieldAlert } from "lucide-react";
import { useIsAdmin } from "@/features/admin/hooks/useAdmin";
import { useAuthStore } from "@/features/auth/store/authStore";
import { Button } from "@/components/ui/button";

/** 관리자 화면 공용 가드. 미로그인/비관리자는 안내 화면만 노출(데이터 접근은 서버 게이트가 최종 판단). */
export function AdminGate({ children }: { children: React.ReactNode }) {
  const user = useAuthStore((s) => s.user);
  const isAdmin = useIsAdmin();

  if (!user) return <Notice title="로그인이 필요합니다" desc="관리자 계정으로 로그인해 주세요." showLogin />;
  if (!isAdmin) return <Notice title="접근 권한이 없습니다" desc="이 페이지는 관리자 전용입니다." />;
  return <>{children}</>;
}

function Notice({ title, desc, showLogin }: { title: string; desc: string; showLogin?: boolean }) {
  return (
    <main className="mx-auto flex max-w-md flex-col items-center gap-3 px-4 py-24 text-center">
      <ShieldAlert className="h-12 w-12 text-muted-foreground" />
      <h1 className="text-xl font-bold">{title}</h1>
      <p className="text-sm text-muted-foreground">{desc}</p>
      {showLogin && <Link href="/login"><Button className="mt-2">로그인</Button></Link>}
    </main>
  );
}
