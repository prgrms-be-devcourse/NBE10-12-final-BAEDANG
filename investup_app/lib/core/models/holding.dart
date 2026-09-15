import 'json_read.dart';

/// 보유 종목 한 건. 랭킹에서 빠진 종목은 시세가 없어 lastPrice가 null이다.
class HoldingItem {
  const HoldingItem({
    required this.symbol,
    required this.name,
    required this.currency,
    required this.quantity,
    this.avgBuyPrice,
    this.avgExchangeRate,
    this.lastPrice,
    this.evaluationAmount,
    this.unrealizedPnl,
    this.unrealizedPnlRate,
    this.realtime = false,
  });

  final String symbol;
  final String name;
  final String currency;
  final String quantity;

  /// 매수 시점 평균단가(종목 통화). 원화 환산엔 avgExchangeRate를 쓴다.
  final String? avgBuyPrice;

  /// 평균 매수 시 적용 환율. USD 종목의 평균단가 원화 환산용.
  final String? avgExchangeRate;
  final String? lastPrice;

  /// 원화로 환산까지 끝난 평가금액(백엔드 계산) — 추가 환산이 필요 없다.
  final String? evaluationAmount;
  final String? unrealizedPnl;
  final String? unrealizedPnlRate;
  final bool realtime;

  bool get isUsd => currency == 'USD';

  factory HoldingItem.fromJson(Map<String, Object?> json) => HoldingItem(
    symbol: json.requireString('symbol'),
    name: json.requireString('name'),
    currency: json.requireString('currency'),
    quantity: json.requireString('quantity'),
    avgBuyPrice: json.stringOrNull('avgBuyPrice'),
    avgExchangeRate: json.stringOrNull('avgExchangeRate'),
    lastPrice: json.stringOrNull('lastPrice'),
    evaluationAmount: json.stringOrNull('evaluationAmount'),
    unrealizedPnl: json.stringOrNull('unrealizedPnl'),
    unrealizedPnlRate: json.stringOrNull('unrealizedPnlRate'),
    realtime: json.boolOrNull('realtime') ?? false,
  );
}

/// GET /accounts/me/holdings 응답.
class Holdings {
  const Holdings({required this.items, this.asOf});

  final List<HoldingItem> items;
  final DateTime? asOf;

  factory Holdings.fromJson(Map<String, Object?> json) => Holdings(
    items: json
        .requireObjectList('items')
        .map(HoldingItem.fromJson)
        .toList(growable: false),
    asOf: json.dateTimeOrNull('asOf'),
  );
}
