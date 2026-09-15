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

/**
 * 이 예산(`availableCash`, 원)으로 매수할 수 있는 최대 수량. 수수료 공식을 여기서
 * 다시 구현하지 않고, 나눗셈으로 대략 추정한 뒤 {@link calculateOrderAmount}로
 * ±1주 오차를 보정한다 — 원 단위 반올림 때문에 나눗셈만으로는 경계값에서 한 주
 * 어긋날 수 있어서다. 매수 화면에서 "얼마까지 살 수 있는지" 입력 상한을 정할 때
 * 쓴다(매도는 보유 수량이 상한이라 이 함수가 필요 없다).
 */
export function maxAffordableQuantity(params: {
  price: number | string;
  currency: "KRW" | "USD";
  usdKrwRate: number;
  availableCash: number;
}): number {
  const { price, currency, usdKrwRate, availableCash } = params;
  const unitPrice = new D(price);
  if (availableCash <= 0 || unitPrice.lessThanOrEqualTo(0)) return 0;

  const unitCostKrw = currency === "USD" ? unitPrice.times(usdKrwRate) : unitPrice;
  const estimatedCostPerShare = unitCostKrw.times(new D(1).plus(FEE_RATE));
  let qty = Math.max(0, new D(availableCash).dividedBy(estimatedCostPerShare).floor().toNumber());

  const affordable = (q: number) =>
    calculateOrderAmount({ side: "매수", quantity: q, price, currency, usdKrwRate }).netAmount <= availableCash;

  // 경계 보정은 설계상 ±1~2주 수준이라 실무에선 반복이 1~2회로 끝나지만,
  // (코드 리뷰, PR #124, SOL4R1S님) 혹시 모를 비정상 입력값으로 추정값이
  // 크게 어긋나는 최악의 경우에도 calculateOrderAmount를 무한정 다시 호출하지
  // 않도록 반복 횟수를 제한한다 — 넘으면 그 시점까지 보정한 값을 그대로 쓴다.
  const MAX_CORRECTION_STEPS = 10;
  for (let steps = 0; qty > 0 && !affordable(qty) && steps < MAX_CORRECTION_STEPS; steps++) qty--;
  for (let steps = 0; affordable(qty + 1) && steps < MAX_CORRECTION_STEPS; steps++) qty++;
  return qty;
}
