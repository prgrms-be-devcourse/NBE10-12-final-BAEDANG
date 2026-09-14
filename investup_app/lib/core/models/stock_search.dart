import 'json_read.dart';
import 'market_country.dart';
import 'ranking.dart';

/// GET /stocks/search 응답 항목.
class StockSearchItem {
  const StockSearchItem({
    required this.symbol,
    required this.name,
    required this.marketCountry,
    required this.category,
    this.englishName,
    this.market,
  });

  final String symbol;
  final String name;
  final String? englishName;
  final String? market;
  final MarketCountry marketCountry;
  final StockCategory category;

  factory StockSearchItem.fromJson(Map<String, Object?> json) =>
      StockSearchItem(
        symbol: json.requireString('symbol'),
        name: json.requireString('name'),
        marketCountry: MarketCountry.fromWire(json.stringOrNull('marketCountry')),
        category: StockCategory.fromWire(json.stringOrNull('category')),
        englishName: json.stringOrNull('englishName'),
        market: json.stringOrNull('market'),
      );
}
