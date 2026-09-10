/**
 * 지정가 입력창의 타이핑 필터. 백엔드는 초과 정밀도를 반올림하지 않고 그대로
 * 거절하므로(docs/api-spec.md LIMIT order lifecycle: "Trailing zeros are allowed;
 * excess precision and exponent notation are rejected, never silently rounded"),
 * 잘못된 자릿수가 애초에 입력되지 않도록 타이핑 단계에서 막는다.
 *
 * - KRW: 정수만 허용(소수점 자체를 제거).
 * - USD: 소수점은 최대 1개, 소수부는 최대 2자리(센트 단위)까지만 허용한다.
 */
export function sanitizeLimitPriceInput(raw: string, currency: "KRW" | "USD"): string {
  const digitsAndDot = raw.replace(/[^0-9.]/g, "");

  if (currency === "KRW") return digitsAndDot.replace(/\./g, "");

  // 첫 번째 "."만 소수점으로 인정하고, 그 뒤에 등장하는 "."은 전부 무시한다.
  const firstDotIndex = digitsAndDot.indexOf(".");
  if (firstDotIndex === -1) return digitsAndDot;
  const intPart = digitsAndDot.slice(0, firstDotIndex);
  const fracPart = digitsAndDot.slice(firstDotIndex + 1).replace(/\./g, "");
  return `${intPart}.${fracPart.slice(0, 2)}`;
}
