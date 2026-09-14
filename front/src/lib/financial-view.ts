import type { StockFinancialPeriod } from "./api";

export type FinancialRange = "annual" | "quarterly";

const PREVIEW_LIMIT: Record<FinancialRange, number> = {
  annual: 3,
  quarterly: 6,
};

export function recentPeriods(periods: StockFinancialPeriod[], range: FinancialRange): StockFinancialPeriod[] {
  return periods.slice(0, PREVIEW_LIMIT[range]);
}

export function chartPeriods(periods: StockFinancialPeriod[], range: FinancialRange): StockFinancialPeriod[] {
  return [...recentPeriods(periods, range)].reverse();
}

export function formatCalculatedPer(
  calculatedPer: string | null | undefined,
  latestAnnualEps: string | null | undefined,
): string {
  const per = toFiniteNumber(calculatedPer);
  if (per !== null && per > 0) return `${per.toFixed(2)}배`;

  const eps = toFiniteNumber(latestAnnualEps);
  return eps !== null && eps < 0 ? "적자" : "-";
}

function toFiniteNumber(value: string | null | undefined): number | null {
  if (value == null || value.trim() === "") return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}
