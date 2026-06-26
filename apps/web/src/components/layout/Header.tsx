import Link from "next/link";
import { Search, User } from "lucide-react";

/** 글로벌 헤더 (layout.md). 모든 화면 공통. */
export function Header() {
  return (
    <header className="border-b border-border bg-background">
      <div className="mx-auto flex h-14 max-w-6xl items-center gap-4 px-4">
        <Link href="/" className="text-lg font-bold text-primary">
          FlowTicket
        </Link>

        <div className="relative hidden flex-1 md:block">
          <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <input
            placeholder="공연명, 아티스트, 장소 검색"
            className="h-9 w-full rounded-md border border-input bg-muted/40 pl-9 pr-3 text-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          />
        </div>

        <nav className="ml-auto flex items-center gap-4 text-sm">
          <Link href="/" className="text-muted-foreground hover:text-foreground">
            이벤트
          </Link>
          <Link href="/" className="text-muted-foreground hover:text-foreground">
            예매안내
          </Link>
          <Link href="/login" className="text-muted-foreground hover:text-foreground">
            로그인
          </Link>
          <Link href="/login" aria-label="내 정보" className="text-muted-foreground hover:text-foreground">
            <User className="h-5 w-5" />
          </Link>
        </nav>
      </div>
    </header>
  );
}
