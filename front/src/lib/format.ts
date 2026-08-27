import { D } from "./decimal";
import type Decimal from "decimal.js";

/** 포맷 함수가 받을 수 있는 숫자 타입 (문자열, 숫자, Decimal 인스턴스) */
export type NumericValue = number | string | Decimal;

export function formatNumber(value: NumericValue): string {
  const d = new D(value).toDecimalPlaces(0);
  const parts = d.toFixed(0).split(".");
  parts[0] = parts[0].replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  return parts.join(".");
}

/** 1,240,000,000,000 → "1.24조", 10,000,000,000 → "100억" 같은 한국식 축약 표기. 거래대금 표시용. */
export function formatKoreanAmount(value: NumericValue): string {
  const d = new D(value);
  const eok = new D(100_000_000);
  const jo = new D(1_000_000_000_000);

  if (d.gte(jo)) return `${d.dividedBy(jo).toFixed(2)}조`;
  if (d.gte(eok)) return `${formatNumber(d.dividedBy(eok).toDecimalPlaces(0))}억`;
  return formatNumber(d);
}

export function formatSigned(value: NumericValue): string {
  const d = new D(value);
  const sign = d.gt(0) ? "+" : d.lt(0) ? "" : "±";
  return `${sign}${formatNumber(d)}`;
}

export function formatPercent(rate: NumericValue): string {
  const d = new D(rate);
  const sign = d.gt(0) ? "+" : "";
  return `${sign}${d.times(100).toFixed(2)}%`;
}

export function formatUsd(value: NumericValue): string {
  const d = new D(value);
  return `$${d.toFixed(2)}`;
}
