import { D } from "./decimal";
import { FEE_RATE, KR_TAX_RATE, US_TAX_MIN_USD, US_TAX_RATE } from "./mock-data";

/**
 * 매수/매도 금액 미리보기 계산. `back/src/main/java/com/baedang/trading/service/OrderAmountCalculator.java`
 * (김다훈님 구현)와 같은 규칙을 프론트에서 미리보기용으로 재현한 것입니다 — 실제 체결 금액은
 * 항상 백엔드가 다시 계산해서 확정합니다. 여기 값은 화면 미리보기 전용입니다.
 *
 * <p>국내: gross = qty * price, fee = round(gross * FEE_RATE), 매도세 = round(gross * KR_TAX_RATE)
 * 미국: 주당 USD 가격을 센트 반올림한 뒤 수량·환율을 적용하고, 원화 거래대금에서 수수료 계산
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
  /** 백엔드 응답은 금액을 문자열로 내려주므로(정밀도 보존) 문자열도 그대로 받는다. */
  price: number | string;
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

  // 미국 — 백엔드와 동일하게 주당 가격을 센트로 먼저 확정한 뒤 수량과 환율을 적용한다.
  const rate = new D(usdKrwRate);
  const roundedUnitPrice = unitPrice.toDecimalPlaces(2);
  const grossUsd = roundedUnitPrice.times(qty);
  const grossAmount = grossUsd.times(rate).toDecimalPlaces(0);
  const fee = grossAmount.times(FEE_RATE).toDecimalPlaces(0);

  let tax = new D(0);
  if (side === "매도") {
    const secFeeUsd = D.max(grossUsd.times(US_TAX_RATE), US_TAX_MIN_USD).toDecimalPlaces(2);
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
