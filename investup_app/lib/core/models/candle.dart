import 'json_read.dart';

/// GET /stocks/{symbol}/candles 응답.
class CandleSeries {
  const CandleSeries({
    required this.symbol,
    required this.interval,
    required this.range,
    required this.items,
    this.currency,
  });

  final String symbol;
  final String interval;
  final String range;
  final String? currency;
  final List<Candle> items;

  factory CandleSeries.fromJson(Map<String, Object?> json) => CandleSeries(
    symbol: json.requireString('symbol'),
    interval: json.requireString('interval'),
    range: json.requireString('range'),
    currency: json.stringOrNull('currency'),
    items: json
        .requireObjectList('items')
        .map(Candle.fromJson)
        .toList(growable: false),
  );
}

/// 캔들 한 개. OHLC·거래량은 정밀도를 위해 문자열로 보관한다.
class Candle {
  const Candle({
    required this.at,
    required this.open,
    required this.high,
    required this.low,
    required this.close,
    required this.volume,
  });

  final DateTime? at;
  final String open;
  final String high;
  final String low;
  final String close;
  final String volume;

  factory Candle.fromJson(Map<String, Object?> json) => Candle(
    at: json.dateTimeOrNull('at'),
    open: json.requireString('open'),
    high: json.requireString('high'),
    low: json.requireString('low'),
    close: json.requireString('close'),
    volume: json.requireString('volume'),
  );
}
