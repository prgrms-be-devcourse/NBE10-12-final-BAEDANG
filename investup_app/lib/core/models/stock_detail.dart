import 'json_read.dart';
import 'market_country.dart';
import 'ranking.dart';

/// GET /stocks/{symbol} 응답.
class StockDetail {
  const StockDetail({
    required this.symbol,
    required this.name,
    required this.marketCountry,
    required this.category,
    required this.tradable,
    this.englishName,
    this.market,
    this.currency,
    this.isinCode,
    this.leverageFactor,
    this.isDividend,
    this.price,
    this.info,
    this.warnings = const [],
    this.warningsUnavailable = false,
    this.tradableReason,
  });

  final String symbol;
  final String name;
  final MarketCountry marketCountry;
  final StockCategory category;
  final String? englishName;
  final String? market;
  final String? currency;
  final String? isinCode;
  final String? leverageFactor;
  final bool? isDividend;
  final StockPrice? price;
  final StockInfo? info;
  final List<StockWarning> warnings;

  /// true면 "유의사항 없음"이 아니라 "확인하지 못함"이다.
  final bool warningsUnavailable;

  /// false면 tradableReason에 사유가 있다.
  final bool tradable;
  final String? tradableReason;

  factory StockDetail.fromJson(Map<String, Object?> json) => StockDetail(
    symbol: json.requireString('symbol'),
    name: json.requireString('name'),
    marketCountry: MarketCountry.fromWire(json.stringOrNull('marketCountry')),
    category: StockCategory.fromWire(json.stringOrNull('category')),
    tradable: json.requireBool('tradable'),
    englishName: json.stringOrNull('englishName'),
    market: json.stringOrNull('market'),
    currency: json.stringOrNull('currency'),
    isinCode: json.stringOrNull('isinCode'),
    leverageFactor: json.stringOrNull('leverageFactor'),
    isDividend: json.boolOrNull('isDividend'),
    price: json.objectOrNull('price') == null
        ? null
        : StockPrice.fromJson(json.objectOrNull('price')!),
    info: json.objectOrNull('info') == null
        ? null
        : StockInfo.fromJson(json.objectOrNull('info')!),
    warnings:
        (json['warnings'] is List)
            ? (json['warnings']! as List)
                .whereType<Map>()
                .map(
                  (w) => StockWarning.fromJson(
                    w.map((k, v) => MapEntry(k.toString(), v)),
                  ),
                )
                .toList(growable: false)
            : const [],
    warningsUnavailable: json.stringOrNull('warningsStatus') == 'UNAVAILABLE',
    tradableReason: json.stringOrNull('tradableReason'),
  );
}

/// 현재가 블록. quoteAt은 시세 기준 시각이다.
class StockPrice {
  const StockPrice({
    this.lastPrice,
    this.prevClose,
    this.changeAmount,
    this.changeRate,
    this.upperLimit,
    this.lowerLimit,
    this.quoteAt,
    this.realtime = false,
  });

  final String? lastPrice;
  final String? prevClose;
  final String? changeAmount;
  final String? changeRate;
  final String? upperLimit;
  final String? lowerLimit;
  final DateTime? quoteAt;
  final bool realtime;

  factory StockPrice.fromJson(Map<String, Object?> json) => StockPrice(
    lastPrice: json.stringOrNull('lastPrice'),
    prevClose: json.stringOrNull('prevClose'),
    changeAmount: json.stringOrNull('changeAmount'),
    changeRate: json.stringOrNull('changeRate'),
    upperLimit: json.stringOrNull('upperLimit'),
    lowerLimit: json.stringOrNull('lowerLimit'),
    quoteAt: json.dateTimeOrNull('quoteAt'),
    realtime: json.boolOrNull('realtime') ?? false,
  );
}

/// 종목 기본 정보.
class StockInfo {
  const StockInfo({this.marketCap, this.sharesOutstanding, this.listDate});

  final String? marketCap;
  final String? sharesOutstanding;
  final String? listDate;

  factory StockInfo.fromJson(Map<String, Object?> json) => StockInfo(
    marketCap: json.stringOrNull('marketCap'),
    sharesOutstanding: json.stringOrNull('sharesOutstanding'),
    listDate: json.stringOrNull('listDate'),
  );
}

/// 투자 유의사항 배지.
class StockWarning {
  const StockWarning({required this.type, required this.label});

  final String type;
  final String label;

  factory StockWarning.fromJson(Map<String, Object?> json) =>
      StockWarning(type: json.requireString('type'), label: json.requireString('label'));
}
