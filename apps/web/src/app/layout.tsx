import type { Metadata } from "next";
import "./globals.css";
import { Providers } from "./providers";
import { Header } from "@/components/layout/Header";
import { Footer } from "@/components/layout/Footer";
import { AuthBootstrap } from "@/features/auth/components/AuthBootstrap";

export const metadata: Metadata = {
  title: "FlowTicket",
  description: "선착순 티켓팅 시스템",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="ko">
      <body className="flex min-h-screen flex-col bg-background text-foreground">
        <Providers>
          <AuthBootstrap />
          <Header />
          <div className="flex-1">{children}</div>
          <Footer />
        </Providers>
      </body>
    </html>
  );
}
