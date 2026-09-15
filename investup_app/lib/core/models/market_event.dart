import 'json_read.dart';

/// KRX 시장조치(서킷브레이커·사이드카) 발동 한 건.
/// `active`는 서버가 조회 시각 기준으로 판정해 내려준다.
class MarketEventItem {
  const MarketEventItem({
    required this.eventId,
    required this.eventType,
    required this.active,
    this.stage,
    this.direction,
    this.triggeredAt,
    this.haltUntil,
    this.title,
    this.sourceUrl,
  });

  final int eventId;

  /// `CIRCUIT_BREAKER` | `SIDECAR`
  final String eventType;
  final int? stage;

  /// 사이드카만: `BUY` | `SELL`
  final String? direction;
  final DateTime? triggeredAt;
  final DateTime? haltUntil;
  final String? title;
  final String? sourceUrl;
  final bool active;

  bool get isCircuitBreaker => eventType == 'CIRCUIT_BREAKER';
}

class MarketEvents {
  const MarketEvents({required this.market, required this.items});

  final String market;
  final List<MarketEventItem> items;

  factory MarketEvents.fromJson(Map<String, Object?> json) => MarketEvents(
    market: json.requireString('market'),
    items: json
        .requireObjectList('items')
        .map(
          (item) => MarketEventItem(
            eventId: item.requireInt('eventId'),
            eventType: item.requireString('eventType'),
            stage: item.intOrNull('stage'),
            direction: item.stringOrNull('direction'),
            triggeredAt: item.dateTimeOrNull('triggeredAt'),
            haltUntil: item.dateTimeOrNull('haltUntil'),
            title: item.stringOrNull('title'),
            sourceUrl: item.stringOrNull('sourceUrl'),
            active: item.requireBool('active'),
          ),
        )
        .toList(growable: false),
  );
}
