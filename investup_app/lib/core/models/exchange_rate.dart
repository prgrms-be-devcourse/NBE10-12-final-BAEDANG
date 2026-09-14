import 'json_read.dart';

/// 최신 환율. 화면 표시용 매매기준율(mid)로 체결용 스프레드 환율과는 다르다.
class ExchangeRateLatest {
  const ExchangeRateLatest({
    required this.baseCurrency,
    required this.quoteCurrency,
    required this.rate,
    this.changeAmount,
    this.changeRate,
    this.validFrom,
  });

  final String baseCurrency;
  final String quoteCurrency;
  final String rate;
  final String? changeAmount;
  final String? changeRate;
  final DateTime? validFrom;

  factory ExchangeRateLatest.fromJson(Map<String, Object?> json) =>
      ExchangeRateLatest(
        baseCurrency: json.requireString('baseCurrency'),
        quoteCurrency: json.requireString('quoteCurrency'),
        rate: json.requireString('rate'),
        changeAmount: json.stringOrNull('changeAmount'),
        changeRate: json.stringOrNull('changeRate'),
        validFrom: json.dateTimeOrNull('validFrom'),
      );
}

/// 환율 추이 그래프의 시계열 한 점.
class ExchangeRateHistoryItem {
  const ExchangeRateHistoryItem({required this.rate, this.validFrom});

  final String rate;
  final DateTime? validFrom;

  factory ExchangeRateHistoryItem.fromJson(Map<String, Object?> json) =>
      ExchangeRateHistoryItem(
        rate: json.requireString('rate'),
        validFrom: json.dateTimeOrNull('validFrom'),
      );
}

class ExchangeRateHistory {
  const ExchangeRateHistory({required this.items});

  final List<ExchangeRateHistoryItem> items;

  factory ExchangeRateHistory.fromJson(Map<String, Object?> json) =>
      ExchangeRateHistory(
        items: json
            .requireObjectList('items')
            .map(ExchangeRateHistoryItem.fromJson)
            .toList(growable: false),
      );
}
