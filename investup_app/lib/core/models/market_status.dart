import 'json_read.dart';
import 'market_country.dart';

/// GET /market/status 응답. 거래 버튼 활성 여부와 "실시간/종가" 라벨에 쓴다.
///
/// 시각은 KST(+09:00) 오프셋을 가진 OffsetDateTime 문자열이다. DateTime으로
/// 파싱하면 같은 순간을 가리키는 값이 되고, 기기 시간대로 날짜를 바꾸지 않는다.
class MarketStatus {
  const MarketStatus({required this.markets, this.serverTime});

  final List<MarketSession> markets;

  /// 서버가 내려준 KST 시각.
  final DateTime? serverTime;

  MarketSession? sessionOf(MarketCountry country) {
    for (final session in markets) {
      if (session.marketCountry == country) return session;
    }
    return null;
  }

  factory MarketStatus.fromJson(Map<String, Object?> json) => MarketStatus(
    markets: json
        .requireObjectList('markets')
        .map(MarketSession.fromJson)
        .toList(growable: false),
    serverTime: json.dateTimeOrNull('serverTime'),
  );
}

/// 시장별 개장 상태.
class MarketSession {
  const MarketSession({
    required this.marketCountry,
    required this.open,
    this.opensAt,
    this.closesAt,
    this.nextOpensAt,
  });

  final MarketCountry marketCountry;

  /// 지금 이 순간 정규장이 열려 있는지(open-NOW). 거래일 여부(open-DAY)와 다르다.
  final bool open;

  /// open이 true면 오늘 정규장 시작 시각, 아니면 null.
  final DateTime? opensAt;

  /// open이 true면 오늘 정규장 종료 시각, 아니면 null.
  final DateTime? closesAt;

  /// open이 false면 다음 정규장 개장 시각, 열려 있으면 null.
  final DateTime? nextOpensAt;

  factory MarketSession.fromJson(Map<String, Object?> json) => MarketSession(
    marketCountry: MarketCountry.fromWire(json.stringOrNull('marketCountry')),
    open: json.requireBool('open'),
    opensAt: json.dateTimeOrNull('opensAt'),
    closesAt: json.dateTimeOrNull('closesAt'),
    nextOpensAt: json.dateTimeOrNull('nextOpensAt'),
  );
}
