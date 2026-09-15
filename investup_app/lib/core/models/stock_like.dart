import 'json_read.dart';
import 'market_country.dart';

/// GET /stocks/likes 응답 페이지.
class StockLikePage {
  const StockLikePage({required this.items, this.nextCursor, this.hasNext = false});

  final List<StockLikeItem> items;
  final String? nextCursor;
  final bool hasNext;

  factory StockLikePage.fromJson(Map<String, Object?> json) => StockLikePage(
    items: json
        .requireObjectList('items')
        .map(StockLikeItem.fromJson)
        .toList(growable: false),
    nextCursor: json.stringOrNull('nextCursor'),
    hasNext: json.requireBool('hasNext'),
  );
}

/// 찜한 종목 한 개.
class StockLikeItem {
  const StockLikeItem({
    required this.stockLikeId,
    required this.stockId,
    required this.symbol,
    required this.name,
    required this.marketCountry,
    this.prevClose,
    this.lastPrice,
    this.changeRate,
  });

  final int stockLikeId;
  final int stockId;
  final String symbol;
  final String name;
  final MarketCountry marketCountry;
  final String? prevClose;
  final String? lastPrice;
  final String? changeRate;

  factory StockLikeItem.fromJson(Map<String, Object?> json) => StockLikeItem(
    stockLikeId: json.requireInt('stockLikeId'),
    stockId: json.requireInt('stockId'),
    symbol: json.requireString('symbol'),
    name: json.requireString('name'),
    marketCountry: MarketCountry.fromWire(json.stringOrNull('marketCountry')),
    prevClose: json.stringOrNull('prevClose'),
    lastPrice: json.stringOrNull('lastPrice'),
    changeRate: json.stringOrNull('changeRate'),
  );
}
