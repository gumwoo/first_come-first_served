import { Header } from "@/components/layout/Header";
import { Footer } from "@/components/layout/Footer";

/** 고객용 사이트 셸 — 공통 헤더/푸터. 운영 콘솔(/admin)은 이 셸을 쓰지 않는다. */
export default function SiteLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex min-h-screen flex-col">
      <Header />
      <div className="flex-1">{children}</div>
      <Footer />
    </div>
  );
}
