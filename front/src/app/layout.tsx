import type { Metadata } from "next";
import { AuthProvider } from "@/components/AuthProvider";
import { ExchangeRateProvider } from "@/components/ExchangeRateProvider";
import { Nav } from "@/components/Nav";
import "./globals.css";

export const metadata: Metadata = {
  title: "모의 주식 트레이딩 서비스",
  description: "주식 초보자를 위한 모의 주식 트레이딩 서비스 — 1주차 MVP",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="ko" className="h-full">
      <body className="min-h-full bg-gray-100 text-gray-900 antialiased">
        <AuthProvider>
          <ExchangeRateProvider>
            <div className="mx-auto min-h-screen max-w-[1180px] bg-white shadow-sm">
              <Nav />
              <main>{children}</main>
            </div>
          </ExchangeRateProvider>
        </AuthProvider>
      </body>
    </html>
  );
}
