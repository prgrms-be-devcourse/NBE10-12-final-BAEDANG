import type { StockCategory } from "./api";

/** 랭킹 테이블, 종목 상세, 검색 결과 등에서 실제로 보여주는 배지 3종. */
export type CategoryLabel = "개별주" | "ETF" | "배당주";

/**
 * 종목 분류 배지 색상 — design_handoff README: "분류 배지: 개별주 --purple* ·
 * ETF --accentSoft/--accentText · 배당주 --green*". 랭킹 테이블, 종목 상세
 * 타이틀, 검색 결과 등 카테고리 배지가 나오는 모든 곳에서 이 색을 공유한다.
 */
export const CATEGORY_BADGE_STYLE: Record<CategoryLabel, { background: string; color: string }> = {
  개별주: { background: "var(--purpleBg)", color: "var(--purpleText)" },
  ETF: { background: "var(--accentSoft)", color: "var(--accentText)" },
  배당주: { background: "var(--greenBg)", color: "var(--greenText)" },
};

/**
 * 백엔드 `StockCategory`(INDIVIDUAL/PREFERRED/ETF/ETN)와 배당 여부를 화면 배지 3종으로 접는다.
 * 배당 여부는 유형과 독립된 값이라(개별주에도 ETF에도 붙을 수 있음 — `StockCategory.java` 참고),
 * 배당이면 유형과 무관하게 "배당주"를 우선 표시한다. PREFERRED(우선주)는 전용 배지가 없어
 * "개별주"로 묶는다.
 */
export function categoryLabel(category: StockCategory, isDividend?: boolean | null): CategoryLabel {
  if (isDividend) return "배당주";
  if (category === "ETF" || category === "ETN") return "ETF";
  return "개별주";
}
