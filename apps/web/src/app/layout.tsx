import type { Metadata } from "next";
import "./globals.css";

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
      <body className="bg-background text-foreground">{children}</body>
    </html>
  );
}
