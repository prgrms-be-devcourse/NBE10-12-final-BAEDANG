import type { Metadata } from "next";
import { AuthProvider } from "@/components/AuthProvider";
import { ExchangeRateProvider } from "@/components/ExchangeRateProvider";
import { ThemeProvider } from "@/components/ThemeProvider";
import { PageBackground } from "@/components/PageBackground";
import { Nav } from "@/components/Nav";
import "./globals.css";

export const metadata: Metadata = {
  title: "모의 주식 트레이딩 서비스",
  description: "주식 초보자를 위한 모의 주식 트레이딩 서비스",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="ko" suppressHydrationWarning>
      <head>
        <link rel="stylesheet" href="https://cdn.jsdelivr.net/gh/orioncactus/pretendard@v1.3.9/dist/web/static/pretendard.css" />
      </head>
      <body className="min-h-screen antialiased" style={{ color: "var(--ink)" }}>
        <ThemeProvider>
          <AuthProvider>
            <ExchangeRateProvider>
              <PageBackground>
                <Nav />
                <main className="mx-auto max-w-[1180px] px-6 pb-16">{children}</main>
              </PageBackground>
            </ExchangeRateProvider>
          </AuthProvider>
        </ThemeProvider>
      </body>
    </html>
  );
}
