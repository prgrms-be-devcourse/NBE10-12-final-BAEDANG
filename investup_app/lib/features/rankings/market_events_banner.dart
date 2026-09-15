import 'dart:async';

import 'package:flutter/material.dart';

import '../../core/api/api_error.dart';
import '../../core/api/market_api.dart';
import '../../core/models/market_event.dart';

/// 오늘의 KRX 시장조치(서킷브레이커·사이드카) 발동 이력 배너.
/// 국내 주식 탭에서만 붙인다. 발동 이력이 없는 날이 대부분이라
/// 평소엔 아무것도 렌더링하지 않고, 조회 실패도 조용히 넘어간다.
class MarketEventsBanner extends StatefulWidget {
  const MarketEventsBanner({super.key, required this.api});

  final MarketApi api;

  static const _markets = ['KOSPI', 'KOSDAQ'];

  @override
  State<MarketEventsBanner> createState() => _MarketEventsBannerState();
}

class _MarketEventsBannerState extends State<MarketEventsBanner> {
  /// 시장 코드를 붙여 둔 행. KOSPI·KOSDAQ 응답을 합쳐서 보여준다.
  List<(String, MarketEventItem)> _rows = const [];
  bool _loaded = false;
  Timer? _poll;

  @override
  void initState() {
    super.initState();
    _load();
    // 발동 빈도가 낮은 긴급 정보라 1분마다 확인한다(웹과 같은 주기).
    _poll = Timer.periodic(const Duration(minutes: 1), (_) => _load());
  }

  @override
  void dispose() {
    _poll?.cancel();
    super.dispose();
  }

  /// KST 기준 오늘 `yyyy-MM-dd`. timezone 패키지 없이 UTC+9로 계산한다.
  static String _todayKst() {
    final kst = DateTime.now().toUtc().add(const Duration(hours: 9));
    return '${kst.year.toString().padLeft(4, '0')}-'
        '${kst.month.toString().padLeft(2, '0')}-'
        '${kst.day.toString().padLeft(2, '0')}';
  }

  Future<void> _load() async {
    try {
      final date = _todayKst();
      final responses = await Future.wait([
        for (final market in MarketEventsBanner._markets)
          widget.api.getMarketEvents(market, date),
      ]);
      if (!mounted) return;
      final merged = <(String, MarketEventItem)>[
        for (var i = 0; i < responses.length; i++)
          for (final item in responses[i].items)
            (MarketEventsBanner._markets[i], item),
      ];
      // 서버와 같은 정렬: 최신 발동이 위로, 같은 시각이면 eventId 역순.
      merged.sort((a, b) {
        final at = a.$2.triggeredAt;
        final bt = b.$2.triggeredAt;
        final byTime = (bt?.millisecondsSinceEpoch ?? 0).compareTo(
          at?.millisecondsSinceEpoch ?? 0,
        );
        return byTime != 0 ? byTime : b.$2.eventId.compareTo(a.$2.eventId);
      });
      setState(() {
        _rows = merged;
        _loaded = true;
      });
    } on ApiException {
      // 다음 폴링에서 다시 시도 — 평소(배너 없음)와 구분하지 않아도 된다.
      if (mounted && !_loaded) setState(() => _loaded = true);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (!_loaded || _rows.isEmpty) return const SizedBox.shrink();
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;

    // 사이드카는 프로그램 호가만 멈춘다 — 활성 CB가 있을 때만 "매매거래 일시중단" 문구.
    final hasActiveCb = _rows.any((r) => r.$2.active && r.$2.isCircuitBreaker);
    final hasActiveSidecar = _rows.any(
      (r) => r.$2.active && !r.$2.isCircuitBreaker,
    );
    final heading = hasActiveCb
        ? '지금 매매거래 일시중단 중이에요'
        : hasActiveSidecar
        ? '현재 시장조치가 발동 중이에요'
        : '오늘의 시장조치 이력';

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 14),
      decoration: BoxDecoration(
        color: scheme.errorContainer.withValues(alpha: 0.35),
        border: Border.all(color: scheme.error.withValues(alpha: 0.4)),
        borderRadius: BorderRadius.circular(14),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            heading,
            style: TextStyle(
              fontSize: 13.5,
              fontWeight: FontWeight.w700,
              color: scheme.onErrorContainer,
            ),
          ),
          const SizedBox(height: 8),
          for (final (market, row) in _rows)
            Padding(
              padding: const EdgeInsets.only(bottom: 6),
              child: _EventRow(market: market, item: row),
            ),
        ],
      ),
    );
  }
}

class _EventRow extends StatelessWidget {
  const _EventRow({required this.market, required this.item});

  final String market;
  final MarketEventItem item;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final kind = item.isCircuitBreaker
        ? '서킷브레이커 ${item.stage ?? '?'}단계'
        : '사이드카 ${item.direction == 'BUY' ? '매수' : '매도'}';
    // 서버는 +09:00을 내려주지만 기기 로캘과 무관하게 KST로 표시한다(웹과 같은 규칙).
    final at = item.triggeredAt?.toUtc().add(const Duration(hours: 9));
    final time = at == null
        ? ''
        : '· ${at.hour.toString().padLeft(2, '0')}:'
              '${at.minute.toString().padLeft(2, '0')}';
    return Wrap(
      crossAxisAlignment: WrapCrossAlignment.center,
      spacing: 6,
      runSpacing: 4,
      children: [
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
          decoration: BoxDecoration(
            color: scheme.surface,
            borderRadius: BorderRadius.circular(6),
          ),
          child: Text(
            market,
            style: TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.w700,
              color: scheme.onErrorContainer,
            ),
          ),
        ),
        Text(
          '$kind $time',
          style: TextStyle(
            fontSize: 12.5,
            fontWeight: FontWeight.w600,
            color: scheme.onErrorContainer,
          ),
        ),
        if (item.active)
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
            decoration: BoxDecoration(
              color: scheme.onErrorContainer,
              borderRadius: BorderRadius.circular(999),
            ),
            child: Text(
              '발동 중',
              style: TextStyle(
                fontSize: 11,
                fontWeight: FontWeight.w700,
                color: scheme.errorContainer,
              ),
            ),
          ),
        if (item.title != null)
          Text(
            item.title!,
            style: TextStyle(
              fontSize: 12,
              color: scheme.onErrorContainer,
              decoration: TextDecoration.underline,
            ),
          ),
      ],
    );
  }
}
