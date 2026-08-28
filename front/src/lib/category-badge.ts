import type { StockCategory } from "./mock-data";

/**
 * 종목 분류 배지 색상 — design_handoff README: "분류 배지: 개별주 --purple* ·
 * ETF --accentSoft/--accentText · 배당주 --green*". 랭킹 테이블, 종목 상세
 * 타이틀, 검색 결과 등 카테고리 배지가 나오는 모든 곳에서 이 색을 공유한다.
 */
export const CATEGORY_BADGE_STYLE: Record<StockCategory, { background: string; color: string }> = {
  개별주: { background: "var(--purpleBg)", color: "var(--purpleText)" },
  ETF: { background: "var(--accentSoft)", color: "var(--accentText)" },
  배당주: { background: "var(--greenBg)", color: "var(--greenText)" },
};
