"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { AdminGate } from "@/features/admin/components/AdminGate";
import { useAuthStore } from "@/features/auth/store/authStore";
import { useLogout } from "@/features/auth/hooks/useAuth";

type NavItem = { href: string; label: string; exact?: boolean };
const NAV: NavItem[] = [
  { href: "/admin", label: "대시보드", exact: true },
  { href: "/admin/orders", label: "주문 조회" },
  { href: "/admin/events", label: "공연 관리" },
  { href: "/admin/dlq", label: "DLQ" },
  { href: "/admin/alerts", label: "알림" },
];

/** 운영 콘솔 셸(S07). 무채색·텍스트 우선 사이드바(활성=좌측 바). 공통 권한 게이트. */
export default function AdminLayout({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const user = useAuthStore((s) => s.user);
  const logout = useLogout();

  const on = (href: string, exact?: boolean) => (exact ? pathname === href : pathname.startsWith(href));

  return (
    <AdminGate>
      <div className="flex min-h-screen">
        <aside className="sticky top-0 flex h-screen w-44 shrink-0 flex-col border-r border-border">
          <div className="px-4 py-4">
            <p className="text-sm font-medium tracking-tight">FlowTicket</p>
            <p className="text-[11px] text-muted-foreground">admin</p>
          </div>

          <nav className="flex flex-1 flex-col">
            {NAV.map((n) => {
              const active = on(n.href, n.exact);
              return (
                <Link
                  key={n.href}
                  href={n.href}
                  className={`border-l-2 px-4 py-1.5 text-[13px] transition-colors ${
                    active
                      ? "border-foreground font-medium text-foreground"
                      : "border-transparent text-muted-foreground hover:text-foreground"
                  }`}
                >
                  {n.label}
                </Link>
              );
            })}
          </nav>

          <div className="flex items-center justify-between border-t border-border px-4 py-3">
            <span className="truncate text-xs text-muted-foreground">{user?.name ?? "관리자"}</span>
            <button
              onClick={() => logout.mutate()}
              disabled={logout.isPending}
              className="text-xs text-muted-foreground hover:text-foreground disabled:opacity-50"
            >
              로그아웃
            </button>
          </div>
          <Link href="/" className="border-t border-border px-4 py-2 text-[11px] text-muted-foreground hover:text-foreground">
            ← 사이트로
          </Link>
        </aside>

        <main className="min-w-0 flex-1">{children}</main>
      </div>
    </AdminGate>
  );
}
