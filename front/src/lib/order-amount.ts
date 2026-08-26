import { FEE_RATE, KR_TAX_RATE, US_TAX_MIN_USD, US_TAX_RATE } from "./mock-data";

/**
 * 매수/매도 금액 미리보기 계산. `back/src/main/java/com/baedang/trading/service/OrderAmountCalculator.java`
 * (김다훈님 구현)와 같은 규칙을 프론트에서 미리보기용으로 재현한 것입니다 — 실제 체결 금액은
 * 항상 백엔드가 다시 계산해서 확정합니다. 여기 값은 화면 미리보기 전용입니다.
 *
 * 국내: gross = qty * price, fee = round(gross * FEE_RATE), 매도세 = round(gross * KR_TAX_RATE)
 * 미국: USD 단위로 센트 반올림 후 KRW 환산, 최종 원 단위 HALF_UP (docs/erd.md 규칙)
 */
export type OrderAmount = {
  grossAmount: number; // KRW
  fee: number; // KRW
  tax: number; // KRW
  netAmount: number; // KRW — 매수는 차감액, 매도는 입금액
};

function round2(n: number): number {
  return Math.round(n * 100) / 100;
}

export function calculateOrderAmount(params: {
  side: "매수" | "매도";
  quantity: number;
  price: number;
  currency: "KRW" | "USD";
  usdKrwRate: number;
}): OrderAmount {
  const { side, quantity, price, currency, usdKrwRate } = params;

  if (currency === "KRW") {
    const grossAmount = Math.round(quantity * price);
    const fee = Math.round(grossAmount * FEE_RATE);
    const tax = side === "매도" ? Math.round(grossAmount * KR_TAX_RATE) : 0;
    const netAmount = side === "매수" ? grossAmount + fee : grossAmount - fee - tax;
    return { grossAmount, fee, tax, netAmount };
  }

  // 미국 — USD로 먼저 센트 단위 반올림한 뒤 KRW로 환산, 최종 원 단위 반올림.
  const grossUsd = round2(quantity * price);
  const grossAmount = Math.round(grossUsd * usdKrwRate);
  const feeUsd = round2(grossUsd * FEE_RATE);
  const fee = Math.round(feeUsd * usdKrwRate);
  let tax = 0;
  if (side === "매도") {
    const secFeeUsd = Math.max(round2(grossUsd * US_TAX_RATE), US_TAX_MIN_USD);
    tax = Math.round(secFeeUsd * usdKrwRate);
  }
  const netAmount = side === "매수" ? grossAmount + fee : grossAmount - fee - tax;
  return { grossAmount, fee, tax, netAmount };
}
