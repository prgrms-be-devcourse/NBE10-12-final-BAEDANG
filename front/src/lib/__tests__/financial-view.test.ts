import { describe, expect, it } from "vitest";
import type { StockFinancialPeriod } from "../api";
import {
  chartPeriods,
  formatCalculatedPer,
  recentPeriods,
  type FinancialRange,
} from "../financial-view";

function period(statementYearMonth: string, eps: string | null = "5000"): StockFinancialPeriod {
  return {
    statementYearMonth,
    balanceSheet: {
      currentAssets: "0",
      fixedAssets: "0",
      totalAssets: "0",
      currentLiabilities: "0",
      fixedLiabilities: "0",
      totalLiabilities: "0",
      capitalStock: "0",
      capitalSurplus: "0",
      retainedEarnings: "0",
      totalEquity: "0",
    },
    incomeStatement: {
      sales: "1000000",
      operatingProfit: "100000",
      netIncome: "80000",
    },
    ratios: {
      salesGrowthRate: null,
      operatingProfitGrowthRate: null,
      netIncomeGrowthRate: null,
      roe: "10",
      eps,
      salesPerShare: null,
      bps: "50000",
      reserveRatio: null,
      debtRatio: "30",
      netProfitMargin: "8",
      operatingProfitMargin: "10",
    },
  };
}

describe("recentPeriods", () => {
  const periods = ["202606", "202512", "202412", "202312", "202212", "202112", "202012"].map((month) => period(month));

  it.each([
    ["annual", 3],
    ["quarterly", 6],
  ] as const)("%s는 최신 데이터부터 %i개만 기본 노출한다", (range: FinancialRange, count) => {
    expect(recentPeriods(periods, range).map((item) => item.statementYearMonth)).toEqual(
      periods.slice(0, count).map((item) => item.statementYearMonth),
    );
  });

  it("데이터가 기본 범위보다 적으면 있는 만큼만 반환한다", () => {
    expect(recentPeriods(periods.slice(0, 2), "annual")).toHaveLength(2);
  });

  it("원본 배열을 변경하지 않는다", () => {
    const original = [...periods];
    recentPeriods(periods, "annual");
    expect(periods).toEqual(original);
  });
});

describe("chartPeriods", () => {
  it("최신 3개를 오래된 시점부터 읽도록 뒤집는다", () => {
    const periods = ["202606", "202512", "202412", "202312"].map((month) => period(month));

    expect(chartPeriods(periods, "annual").map((item) => item.statementYearMonth)).toEqual([
      "202412",
      "202512",
      "202606",
    ]);
  });
});

describe("formatCalculatedPer", () => {
  it("백엔드 계산 PER을 배 단위로 표시한다", () => {
    expect(formatCalculatedPer("14.27", "17687")).toBe("14.27배");
  });

  it("음수 EPS면 적자로 표시한다", () => {
    expect(formatCalculatedPer(null, "-100")).toBe("적자");
  });

  it("PER 또는 EPS가 없으면 대시로 표시한다", () => {
    expect(formatCalculatedPer(null, null)).toBe("-");
  });
});
