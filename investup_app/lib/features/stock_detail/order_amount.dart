import 'package:decimal/decimal.dart';

/// 주문 금액 미리보기 계산 — `front/src/lib/order-amount.ts`와 같은 규칙을
/// Dart로 옮긴 것. 실제 체결 금액은 항상 백엔드가 다시 계산해서 확정하며
/// 여기 값은 화면 미리보기 전용이다.
///
/// 국내: gross = qty * price, fee = round(gross * 0.01%), 매도세 = round(gross * 0.2%)
/// 미국: 주당 USD 가격을 센트 반올림한 뒤 수량·환율을 적용하고 원화에서 수수료 계산
final _feeRate = Decimal.parse('0.0001'); // 0.01%, 매수·매도 공통
final _krTaxRate = Decimal.parse('0.002'); // 국내 매도세 0.2%
final _usTaxRate = Decimal.parse('0.0000206'); // 미국 SEC Fee
final _usTaxMinUsd = Decimal.parse('0.01');

/// 미리보기 금액 묶음 — 전부 원화 정수.
class OrderAmount {
  const OrderAmount({
    required this.grossAmount,
    required this.fee,
    required this.tax,
    required this.netAmount,
  });

  final Decimal grossAmount;
  final Decimal fee;
  final Decimal tax;

  /// 매수는 차감액, 매도는 입금액.
  final Decimal netAmount;
}

Decimal _max(Decimal a, Decimal b) => a >= b ? a : b;

/// 매수/매도 금액 미리보기. [price]는 종목 통화 기준 주당 가격.
OrderAmount calculateOrderAmount({
  required String side, // 'BUY' | 'SELL'
  required int quantity,
  required Decimal price,
  required String currency, // 'KRW' | 'USD'
  required Decimal usdKrwRate,
}) {
  final qty = Decimal.parse('$quantity');
  final buy = side == 'BUY';

  if (currency == 'KRW') {
    final gross = (qty * price).round(scale: 0);
    final fee = (gross * _feeRate).round(scale: 0);
    final tax = buy ? Decimal.zero : (gross * _krTaxRate).round(scale: 0);
    final net = buy ? gross + fee : gross - fee - tax;
    return OrderAmount(
      grossAmount: gross,
      fee: fee,
      tax: tax,
      netAmount: net,
    );
  }

  // 미국 — 백엔드와 동일하게 주당 가격을 센트로 먼저 확정한 뒤 수량과 환율을 적용한다.
  final roundedUnitPrice = price.round(scale: 2);
  final grossUsd = roundedUnitPrice * qty;
  final gross = (grossUsd * usdKrwRate).round(scale: 0);
  final fee = (gross * _feeRate).round(scale: 0);

  var tax = Decimal.zero;
  if (!buy) {
    final secFeeUsd = _max(grossUsd * _usTaxRate, _usTaxMinUsd).round(scale: 2);
    tax = (secFeeUsd * usdKrwRate).round(scale: 0);
  }

  final net = buy ? gross + fee : gross - fee - tax;
  return OrderAmount(grossAmount: gross, fee: fee, tax: tax, netAmount: net);
}

/// 이 예산([availableCash], 원)으로 매수할 수 있는 최대 수량.
/// 나눗셈 추정 뒤 [calculateOrderAmount]로 ±1주 오차를 보정한다 —
/// 원 단위 반올림 때문에 경계값에서 한 주 어긋날 수 있어서다.
int maxAffordableQuantity({
  required Decimal price,
  required String currency,
  required Decimal usdKrwRate,
  required Decimal availableCash,
}) {
  if (availableCash <= Decimal.zero || price <= Decimal.zero) return 0;

  final unitCostKrw = currency == 'USD' ? price * usdKrwRate : price;
  final estimatedCostPerShare = unitCostKrw * (Decimal.one + _feeRate);
  // 나눗셈은 무한소수가 될 수 있어 Rational.floor()로 바로 내린다.
  var qty = (availableCash / estimatedCostPerShare).floor().toInt();
  if (qty < 0) return 0;

  bool affordable(int q) =>
      calculateOrderAmount(
        side: 'BUY',
        quantity: q,
        price: price,
        currency: currency,
        usdKrwRate: usdKrwRate,
      ).netAmount <=
      availableCash;

  // 경계 보정은 설계상 ±1~2주 수준 — 최악의 비정상 입력에도 무한 반복하지 않도록
  // 횟수를 제한한다(웹과 동일).
  const maxSteps = 10;
  for (var steps = 0; qty > 0 && !affordable(qty) && steps < maxSteps; steps++) {
    qty--;
  }
  for (var steps = 0; affordable(qty + 1) && steps < maxSteps; steps++) {
    qty++;
  }
  return qty;
}
