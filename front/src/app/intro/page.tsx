import type { Metadata } from "next";
import { IntroScreen } from "@/components/IntroScreen";

export const metadata: Metadata = {
  title: "InvestUP — 실수는 가볍게, 투자 감각은 제대로",
  description: "모의 투자금 5,000만원으로 나만의 투자 연습을 시작해보세요.",
};

/**
 * 홈페이지("/") 첫 방문 시 `proxy.ts`가 이리로 돌려보내는 서비스 소개 화면.
 * 사이트 공통 헤더(`Nav`)와 본문 폭 제한 없이 전체 화면을 그대로 쓴다
 * (`SiteChrome`이 이 경로에서는 그 둘을 렌더링하지 않는다).
 */
export default function IntroPage() {
  return <IntroScreen />;
}
