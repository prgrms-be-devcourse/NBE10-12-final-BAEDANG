import { D } from "./decimal";
import { FEE_RATE, KR_TAX_RATE, US_TAX_MIN_USD, US_TAX_RATE } from "./mock-data";

/**
 * 매수/매도 금액 미리보기 계산. `back/src/main/java/com/baedang/trading/service/OrderAmountCalculator.java`
 * (김다훈님 구현)와 같은 규칙을 프론트에서 미리보기용으로 재현한 것입니다 — 실제 체결 금액은
 * 항상 백엔드가 다시 계산해서 확정합니다. 여기 값은 화면 미리보기 전용입니다.
 *
 * <p>국내: gross = qty * price, fee = round(gross * FEE_RATE), 매도세 = round(gross * KR_TAX_RATE)
 * 미국: USD 단위로 센트 반올림 후 KRW 환산, 최종 원 단위 HALF_UP (docs/erd.md 규칙)
 *
 * <p>전부 {@link D}(decimal.js)로 계산합니다 — 순수 `number` 곱셈은 이진 부동소수점
 * 오차가 생겨서 수수료·세금처럼 반올림 규칙이 정확해야 하는 금액 계산엔 부적합합니다.
 */
export type OrderAmount = {
  grossAmount: number; // KRW
  fee: number; // KRW
  tax: number; // KRW
  netAmount: number; // KRW — 매수는 차감액, 매도는 입금액
};

export function calculateOrderAmount(params: {
  side: "매수" | "매도";
  quantity: number;
  price: number;
  currency: "KRW" | "USD";
  usdKrwRate: number;
}): OrderAmount {
  const { side, quantity, price, currency, usdKrwRate } = params;
  const qty = new D(quantity);
  const unitPrice = new D(price);

  if (currency === "KRW") {
    const grossAmount = qty.times(unitPrice).toDecimalPlaces(0);
    const fee = grossAmount.times(FEE_RATE).toDecimalPlaces(0);
    const tax = side === "매도" ? grossAmount.times(KR_TAX_RATE).toDecimalPlaces(0) : new D(0);
    const netAmount = side === "매수" ? grossAmount.plus(fee) : grossAmount.minus(fee).minus(tax);
    return {
      grossAmount: grossAmount.toNumber(),
      fee: fee.toNumber(),
      tax: tax.toNumber(),
      netAmount: netAmount.toNumber(),
    };
  }

  // 미국 — USD로 먼저 센트 단위 반올림한 뒤 KRW로 환산, 최종 원 단위 반올림.
  const rate = new D(usdKrwRate);
  const grossUsd = qty.times(unitPrice).toDecimalPlaces(2);
  const grossAmount = grossUsd.times(rate).toDecimalPlaces(0);
  const feeUsd = grossUsd.times(FEE_RATE).toDecimalPlaces(2);
  const fee = feeUsd.times(rate).toDecimalPlaces(0);

  let tax = new D(0);
  if (side === "매도") {
    const secFeeUsd = D.max(grossUsd.times(US_TAX_RATE).toDecimalPlaces(2), US_TAX_MIN_USD);
    tax = secFeeUsd.times(rate).toDecimalPlaces(0);
  }

  const netAmount = side === "매수" ? grossAmount.plus(fee) : grossAmount.minus(fee).minus(tax);
  return {
    grossAmount: grossAmount.toNumber(),
    fee: fee.toNumber(),
    tax: tax.toNumber(),
    netAmount: netAmount.toNumber(),
  };
}
