import { D } from "./decimal";
import type Decimal from "decimal.js";

/** 포맷 함수가 받을 수 있는 숫자 타입 (문자열, 숫자, Decimal 인스턴스, null, undefined) */
export type NumericValue = number | string | Decimal | null | undefined;

/**
 * 비정상 입력(null, undefined, "", NaN, Infinity 등)을 걸러내고 안전한 Decimal 객체를 반환합니다.
 * 유효하지 않은 입력 시 null을 반환하여 렌더 중 예외 발생(크래시)을 방지합니다.
 */
export function toDecimal(value: NumericValue): Decimal | null {
  if (value === null || value === undefined || value === "") return null;
  try {
    const d = new D(value);
    return d.isFinite() ? d : null;
  } catch {
    return null;
  }
}

export function formatNumber(value: NumericValue, fallback = "-"): string {
  const d = toDecimal(value);
  if (!d) return fallback;
  const parts = d.toDecimalPlaces(0).toFixed(0).split(".");
  parts[0] = parts[0].replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  return parts.join(".");
}

/** 1,240,000,000,000 → "1.24조", 10,000,000,000 → "100억", -500,000,000 → "-5억" 같은 한국식 축약 표기. */
export function formatKoreanAmount(value: NumericValue, fallback = "-"): string {
  const d = toDecimal(value);
  if (!d) return fallback;
  const abs = d.abs();
  const sign = d.lt(0) ? "-" : "";
  const eok = new D(100_000_000);
  const jo = new D(1_000_000_000_000);

  if (abs.gte(jo)) return `${sign}${abs.dividedBy(jo).toFixed(2)}조`;
  if (abs.gte(eok)) return `${sign}${formatNumber(abs.dividedBy(eok).toDecimalPlaces(0))}억`;
  return formatNumber(d, fallback);
}

export function formatSigned(value: NumericValue, fallback = "-"): string {
  const d = toDecimal(value);
  if (!d) return fallback;
  const rounded = d.toDecimalPlaces(0);
  const sign = rounded.gt(0) ? "+" : rounded.lt(0) ? "" : "±";
  return `${sign}${formatNumber(rounded)}`;
}

export function formatPercent(rate: NumericValue, fallback = "-"): string {
  const d = toDecimal(rate);
  if (!d) return fallback;
  const sign = d.gt(0) ? "+" : "";
  return `${sign}${d.times(100).toFixed(2)}%`;
}

/** 절댓값을 지정한 소수 자릿수까지 HALF_UP으로 반올림합니다. */
export function formatAbsolute(value: NumericValue, decimalPlaces = 2, fallback = "-"): string {
  const d = toDecimal(value);
  if (!d) return fallback;
  return d.abs().toFixed(decimalPlaces);
}

export function formatUsd(value: NumericValue, fallback = "-"): string {
  const d = toDecimal(value);
  if (!d) return fallback;
  return `$${d.toFixed(2)}`;
}
