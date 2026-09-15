import 'json_read.dart';

/// 종목 분류. 한국어 표시 문자열은 화면에서 매핑한다.
enum StockCategory {
  individual('INDIVIDUAL'),
  preferred('PREFERRED'),
  etf('ETF'),
  etn('ETN'),

  /// 알 수 없는 값. 파싱 크래시 대신 미지원 상태로 표시한다.
  unknown('');

  const StockCategory(this.wireValue);

  final String wireValue;

  static StockCategory fromWire(String? raw) {
    return switch (raw) {
      'INDIVIDUAL' => StockCategory.individual,
      'PREFERRED' => StockCategory.preferred,
      'ETF' => StockCategory.etf,
      'ETN' => StockCategory.etn,
      _ => StockCategory.unknown,
    };
  }
}

/// GET /stocks/rankings 응답.
class RankingPage {
  const RankingPage({
    required this.items,
    required this.hasNext,
    this.nextCursor,
  });

  final List<RankingItem> items;

  /// 해석하지 않는 불투명 커서. 다음 페이지 요청에 그대로 돌려준다.
  final String? nextCursor;

  final bool hasNext;

  factory RankingPage.fromJson(Map<String, Object?> json) => RankingPage(
    items: json
        .requireObjectList('items')
        .map(RankingItem.fromJson)
        .toList(growable: false),
    nextCursor: json.stringOrNull('nextCursor'),
    hasNext: json.requireBool('hasNext'),
  );
}

/// 랭킹 한 줄. 가격·금액은 표시 정밀도를 위해 문자열로 보관한다.
class RankingItem {
  const RankingItem({
    required this.rank,
    required this.stockId,
    required this.symbol,
    required this.name,
    required this.market,
    required this.category,
    required this.realtime,
    this.isDividend,
    this.leverageFactor,
    this.currency,
    this.lastPrice,
    this.prevClose,
    this.changeAmount,
    this.changeRate,
    this.tradingAmount,
    this.quoteAt,
    this.stockLikeId,
  });

  final int rank;
  final int stockId;
  final String symbol;
  final String name;

  /// 거래소 이름(KOSPI, NASDAQ 등). 국가 구분은 MarketCountry를 따로 쓴다.
  final String market;

  final StockCategory category;

  /// 지금 시세가 실시간인지. false면 마지막 종가 기준이다.
  final bool realtime;

  final bool? isDividend;

  /// 레버리지 배수 문자열(해당 없으면 null).
  final String? leverageFactor;

  final String? currency;
  final String? lastPrice;
  final String? prevClose;
  final String? changeAmount;
  final String? changeRate;

  /// 거래대금.
  final String? tradingAmount;

  final DateTime? quoteAt;

  /// 로그인한 사용자가 찜한 경우의 식별자. 찜 삭제에 쓴다.
  final int? stockLikeId;

  factory RankingItem.fromJson(Map<String, Object?> json) => RankingItem(
    rank: json.requireInt('rank'),
    stockId: json.requireInt('stockId'),
    symbol: json.requireString('symbol'),
    name: json.requireString('name'),
    market: json.requireString('market'),
    category: StockCategory.fromWire(json.stringOrNull('category')),
    realtime: json.requireBool('realtime'),
    isDividend: json.boolOrNull('isDividend'),
    leverageFactor: json.stringOrNull('leverageFactor'),
    currency: json.stringOrNull('currency'),
    lastPrice: json.stringOrNull('lastPrice'),
    prevClose: json.stringOrNull('prevClose'),
    changeAmount: json.stringOrNull('changeAmount'),
    changeRate: json.stringOrNull('changeRate'),
    tradingAmount: json.stringOrNull('tradingAmount'),
    quoteAt: json.dateTimeOrNull('quoteAt'),
    stockLikeId: json.intOrNull('stockLikeId'),
  );
}
