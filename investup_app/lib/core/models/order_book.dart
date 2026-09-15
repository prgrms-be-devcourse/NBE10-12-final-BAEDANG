import 'json_read.dart';

/// GET /stocks/{symbol}/orderbook 응답.
class OrderBook {
  const OrderBook({
    required this.symbol,
    required this.asks,
    required this.bids,
    required this.virtual,
    this.marketCountry,
    this.basePrice,
    this.currency,
    this.quoteAt,
    this.description,
  });

  final String symbol;
  final String? marketCountry;
  final String? basePrice;
  final String? currency;
  final DateTime? quoteAt;

  /// true면 현재가 기반 가상 호가다. description에 안내 문구가 들어있다.
  final bool virtual;
  final String? description;

  /// 매도 호가(asks)와 매수 호가(bids). 각 최대 10단계.
  final List<OrderBookLevel> asks;
  final List<OrderBookLevel> bids;

  factory OrderBook.fromJson(Map<String, Object?> json) => OrderBook(
    symbol: json.requireString('symbol'),
    marketCountry: json.stringOrNull('marketCountry'),
    basePrice: json.stringOrNull('basePrice'),
    currency: json.stringOrNull('currency'),
    quoteAt: json.dateTimeOrNull('quoteAt'),
    virtual: json.requireBool('virtual'),
    description: json.stringOrNull('description'),
    asks: json
        .requireObjectList('asks')
        .map(OrderBookLevel.fromJson)
        .toList(growable: false),
    bids: json
        .requireObjectList('bids')
        .map(OrderBookLevel.fromJson)
        .toList(growable: false),
  );
}

/// 호가 한 단계.
class OrderBookLevel {
  const OrderBookLevel({
    required this.level,
    required this.price,
    required this.quantity,
  });

  final int level;
  final String price;
  final String quantity;

  factory OrderBookLevel.fromJson(Map<String, Object?> json) => OrderBookLevel(
    level: json.requireInt('level'),
    price: json.requireString('price'),
    quantity: json.requireString('quantity'),
  );
}
