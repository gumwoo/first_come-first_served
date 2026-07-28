import type { Metadata } from "next";
import "./globals.css";
import { Providers } from "./providers";
import { AuthBootstrap } from "@/features/auth/components/AuthBootstrap";

export const metadata: Metadata = {
  title: "FlowTicket",
  description: "선착순 티켓팅 시스템",
};

// 루트는 문서 셸(Providers·세션 복원)만 담당한다.
// 고객용 헤더/푸터는 (site) 그룹 레이아웃이, 운영 콘솔은 admin 레이아웃이 각자 씌운다.
export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="ko">
      <body className="bg-background text-foreground">
        <Providers>
          <AuthBootstrap />
          {children}
        </Providers>
      </body>
    </html>
  );
}
