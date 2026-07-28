"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { LayoutDashboard, Receipt, CalendarCog, AlertTriangle, Bell, Ticket, ArrowLeft, type LucideIcon } from "lucide-react";
import { AdminGate } from "@/features/admin/components/AdminGate";
import { useAuthStore } from "@/features/auth/store/authStore";
import { useLogout } from "@/features/auth/hooks/useAuth";

type NavItem = { href: string; label: string; icon: LucideIcon; exact?: boolean };
const NAV: NavItem[] = [
  { href: "/admin", label: "대시보드", icon: LayoutDashboard, exact: true },
  { href: "/admin/orders", label: "주문 조회", icon: Receipt },
  { href: "/admin/events", label: "공연 관리", icon: CalendarCog },
  { href: "/admin/dlq", label: "DLQ", icon: AlertTriangle },
  { href: "/admin/alerts", label: "알림", icon: Bell },
];

/** 운영 콘솔 셸(S07). 고객용 헤더/푸터 대신 자체 사이드바. /admin 이하 공통 권한 게이트. */
export default function AdminLayout({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const user = useAuthStore((s) => s.user);
  const logout = useLogout();

  const active = (href: string, exact?: boolean) =>
    exact ? pathname === href : pathname.startsWith(href);

  return (
    <AdminGate>
      <div className="flex min-h-screen bg-muted/20">
        <aside className="sticky top-0 flex h-screen w-56 shrink-0 flex-col border-r border-border bg-background">
          <div className="flex items-center gap-2 px-5 py-4">
            <span className="flex h-7 w-7 items-center justify-center rounded-md bg-primary text-primary-foreground">
              <Ticket className="h-4 w-4" />
            </span>
            <div className="leading-tight">
              <p className="text-sm font-semibold">FlowTicket</p>
              <p className="text-[11px] text-muted-foreground">운영 콘솔</p>
            </div>
          </div>

          <nav className="flex flex-1 flex-col gap-0.5 px-3 py-2">
            {NAV.map((n) => {
              const on = active(n.href, n.exact);
              const Icon = n.icon;
              return (
                <Link
                  key={n.href}
                  href={n.href}
                  className={`flex items-center gap-2.5 rounded-md px-3 py-2 text-sm transition-colors ${
                    on
                      ? "bg-primary/10 font-medium text-primary"
                      : "text-muted-foreground hover:bg-muted hover:text-foreground"
                  }`}
                >
                  <Icon className="h-4 w-4" />
                  {n.label}
                </Link>
              );
            })}
          </nav>

          <div className="border-t border-border px-3 py-3">
            <Link
              href="/"
              className="mb-2 flex items-center gap-2 rounded-md px-3 py-1.5 text-xs text-muted-foreground hover:bg-muted hover:text-foreground"
            >
              <ArrowLeft className="h-3.5 w-3.5" /> 사이트로 돌아가기
            </Link>
            <div className="flex items-center gap-2 px-3 py-1">
              <span className="flex h-7 w-7 items-center justify-center rounded-full bg-muted text-xs font-medium">
                {user?.name?.[0] ?? "관"}
              </span>
              <div className="min-w-0 flex-1">
                <p className="truncate text-xs font-medium">{user?.name ?? "관리자"}</p>
                <p className="truncate text-[11px] text-muted-foreground">{user?.email ?? ""}</p>
              </div>
              <button
                onClick={() => logout.mutate()}
                disabled={logout.isPending}
                className="text-[11px] text-muted-foreground hover:text-foreground disabled:opacity-50"
              >
                로그아웃
              </button>
            </div>
          </div>
        </aside>

        <main className="min-w-0 flex-1">{children}</main>
      </div>
    </AdminGate>
  );
}
