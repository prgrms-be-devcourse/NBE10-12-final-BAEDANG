/** 화면 표시용 포맷 유틸. 백엔드가 붙기 전까지는 프론트에서만 쓰는 임시 함수들입니다. */

export function formatNumber(value: number): string {
  return new Intl.NumberFormat("ko-KR").format(Math.round(value));
}

/** 1,240,000,000,000 → "1.24조" 같은 한국식 축약 표기. 거래대금 표시용. */
export function formatKoreanAmount(value: number): string {
  const eok = 100_000_000;
  const jo = 1_000_000_000_000;
  if (value >= jo) return `${(value / jo).toFixed(2)}조`;
  if (value >= eok) return `${Math.round(value / eok)}억`;
  return formatNumber(value);
}

export function formatSigned(value: number): string {
  const sign = value > 0 ? "+" : value < 0 ? "" : "±";
  return `${sign}${formatNumber(value)}`;
}

export function formatPercent(rate: number): string {
  const sign = rate > 0 ? "+" : "";
  return `${sign}${(rate * 100).toFixed(2)}%`;
}

export function formatUsd(value: number): string {
  return `$${value.toFixed(2)}`;
}
