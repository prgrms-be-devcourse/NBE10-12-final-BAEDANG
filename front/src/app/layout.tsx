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
        {/*
          하이드레이션 전 깜빡임(FOUC) 방지용 차단 스크립트.
          localStorage에 저장된 테마와 시스템 다크모드 설정이 다를 때, 브라우저가
          먼저 시스템 설정대로 CSS를 그렸다가(예: 검정 배경) React가 마운트된 뒤에야
          저장된 테마로 바꾸면 화면이 "검정 -> 라이트" 식으로 깜빡여 보인다.
          최초 페인트 전에 동기적으로 실행돼 data-theme을 곧바로 확정해서 막는다.
        */}
        <script
          dangerouslySetInnerHTML={{
            __html: `(function(){try{var s=localStorage.getItem('trading-theme');var t=s||(window.matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light');document.documentElement.setAttribute('data-theme',t);}catch(e){}})();`,
          }}
        />
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
