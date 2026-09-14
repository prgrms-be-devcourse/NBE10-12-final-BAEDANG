import 'json_read.dart';

/// GET /orders/quote/market 응답. 주문 전 예상 체결 결과다.
class MarketOrderQuote {
  const MarketOrderQuote({
    required this.symbol,
    required this.side,
    required this.quantity,
    required this.executable,
    this.executedPrice,
    this.exchangeRate,
    this.grossAmount,
    this.fee,
    this.tax,
    this.netAmount,
    this.availableCash,
    this.quoteAt,
    this.reason,
  });

  final String symbol;
  final String side;
  final String quantity;
  final String? executedPrice;
  final String? exchangeRate;
  final String? grossAmount;
  final String? fee;
  final String? tax;

  /// 수수료·세금 반영 뒤 실제로 오가는 원화 금액.
  final String? netAmount;

  /// 주문 가능 예수금.
  final String? availableCash;
  final DateTime? quoteAt;

  /// false면 reason에 불가 사유 코드가 있다.
  final bool executable;
  final String? reason;

  factory MarketOrderQuote.fromJson(Map<String, Object?> json) =>
      MarketOrderQuote(
        symbol: json.requireString('symbol'),
        side: json.requireString('side'),
        quantity: json.requireString('quantity'),
        executable: json.requireBool('executable'),
        executedPrice: json.stringOrNull('executedPrice'),
        exchangeRate: json.stringOrNull('exchangeRate'),
        grossAmount: json.stringOrNull('grossAmount'),
        fee: json.stringOrNull('fee'),
        tax: json.stringOrNull('tax'),
        netAmount: json.stringOrNull('netAmount'),
        availableCash: json.stringOrNull('availableCash'),
        quoteAt: json.dateTimeOrNull('quoteAt'),
        reason: json.stringOrNull('reason'),
      );
}

/// GET /orders/quote/limit 응답. 지정가 접수 가능 여부와 예약 예상액이다.
class LimitOrderQuote {
  const LimitOrderQuote({
    required this.acceptable,
    this.requestedLimitPrice,
    this.requestedLimitCurrency,
    this.limitPrice,
    this.availableCash,
    this.availableQuantity,
    this.expiresAt,
    this.reason,
    this.grossAmount,
    this.fee,
    this.tax,
    this.netAmount,
    this.reservedCash,
  });

  /// false면 reason에 불가 사유 코드가 있다.
  final bool acceptable;
  final String? requestedLimitPrice;
  final String? requestedLimitCurrency;
  final String? limitPrice;
  final String? availableCash;
  final String? availableQuantity;
  final DateTime? expiresAt;
  final String? reason;

  /// limitEstimate 블록.
  final String? grossAmount;
  final String? fee;
  final String? tax;
  final String? netAmount;
  final String? reservedCash;

  factory LimitOrderQuote.fromJson(Map<String, Object?> json) {
    final estimate = json.objectOrNull('limitEstimate');
    return LimitOrderQuote(
      acceptable: json.requireBool('acceptable'),
      requestedLimitPrice: json.stringOrNull('requestedLimitPrice'),
      requestedLimitCurrency: json.stringOrNull('requestedLimitCurrency'),
      limitPrice: json.stringOrNull('limitPrice'),
      availableCash: json.stringOrNull('availableCash'),
      availableQuantity: json.stringOrNull('availableQuantity'),
      expiresAt: json.dateTimeOrNull('expiresAt'),
      reason: json.stringOrNull('reason'),
      grossAmount: estimate?.stringOrNull('grossAmount'),
      fee: estimate?.stringOrNull('fee'),
      tax: estimate?.stringOrNull('tax'),
      netAmount: estimate?.stringOrNull('netAmount'),
      reservedCash: estimate?.stringOrNull('reservedCash'),
    );
  }
}

/// POST /orders/market 응답. 시장가 주문은 PENDING 없이 FILLED/REJECTED로 끝난다.
class MarketOrderResult {
  const MarketOrderResult({
    required this.orderId,
    required this.status,
    required this.symbol,
    required this.side,
    required this.quantity,
    this.executedPrice,
    this.grossAmount,
    this.fee,
    this.tax,
    this.netAmount,
    this.orderedAt,
    this.cashBalanceAfter,
  });

  final int orderId;
  final String status;
  final String symbol;
  final String side;
  final String quantity;
  final String? executedPrice;
  final String? grossAmount;
  final String? fee;
  final String? tax;
  final String? netAmount;
  final DateTime? orderedAt;
  final String? cashBalanceAfter;

  bool get filled => status == 'FILLED';

  factory MarketOrderResult.fromJson(Map<String, Object?> json) =>
      MarketOrderResult(
        orderId: json.requireInt('orderId'),
        status: json.requireString('status'),
        symbol: json.requireString('symbol'),
        side: json.requireString('side'),
        quantity: json.requireString('quantity'),
        executedPrice: json.stringOrNull('executedPrice'),
        grossAmount: json.stringOrNull('grossAmount'),
        fee: json.stringOrNull('fee'),
        tax: json.stringOrNull('tax'),
        netAmount: json.stringOrNull('netAmount'),
        orderedAt: json.dateTimeOrNull('orderedAt'),
        cashBalanceAfter: json
            .objectOrNull('account')
            ?.stringOrNull('cashBalanceAfter'),
      );
}
