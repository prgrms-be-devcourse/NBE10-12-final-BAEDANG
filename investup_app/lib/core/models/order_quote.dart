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
