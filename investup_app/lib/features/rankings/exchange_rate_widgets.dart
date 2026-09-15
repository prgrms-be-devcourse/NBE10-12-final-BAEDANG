import 'package:flutter/material.dart';

import '../../core/api/exchange_rate_api.dart';
import '../../core/models/exchange_rate.dart';
import '../../formatters.dart';
import '../../widgets/app_widgets.dart';

/// 랭킹 상단 환율 배너. 최신 USD/KRW와 등락을 보여주고
/// 탭하면 기간별 추이 그래프 시트를 연다.
class ExchangeRateBanner extends StatelessWidget {
  const ExchangeRateBanner({
    super.key,
    required this.future,
    required this.api,
  });

  /// 화면이 들고 있는 요청 — 행의 원화 환산 표시도 같은 값을 쓴다.
  final Future<ExchangeRateLatest>? future;
  final ExchangeRateApi api;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return FutureBuilder<ExchangeRateLatest>(
      future: future,
      builder: (context, snapshot) {
        final rate = snapshot.data;
        return Material(
          color: scheme.surface,
          borderRadius: BorderRadius.circular(14),
          child: InkWell(
            borderRadius: BorderRadius.circular(14),
            onTap: rate == null
                ? null
                : () => showModalBottomSheet<void>(
                      context: context,
                      isScrollControlled: true,
                      builder: (_) => ExchangeRateTrendSheet(api: api),
                    ),
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 10),
              decoration: BoxDecoration(
                border: Border.all(color: scheme.outlineVariant),
                borderRadius: BorderRadius.circular(14),
              ),
              child: Row(
                children: [
                  Text(
                    'USD / KRW',
                    style: TextStyle(
                      fontWeight: FontWeight.w700,
                      color: scheme.onSurface,
                    ),
                  ),
                  const SizedBox(width: 10),
                  if (rate == null && !snapshot.hasError)
                    Text(
                      '불러오는 중…',
                      style: TextStyle(color: scheme.onSurfaceVariant),
                    )
                  else if (rate == null)
                    Text(
                      '환율 조회 실패',
                      style: TextStyle(color: scheme.error),
                    )
                  else ...[
                    Text(
                      formatNumber(rate.rate),
                      style: TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w700,
                        color: scheme.onSurface,
                      ),
                    ),
                    const SizedBox(width: 8),
                    Flexible(
                      child: Text(
                        _changeLabel(rate),
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: 12.5,
                          fontWeight: FontWeight.w600,
                          color: changeColor(rate.changeRate, scheme),
                        ),
                      ),
                    ),
                  ],
                  const Spacer(),
                  Text(
                    '추이 →',
                    style: TextStyle(
                      fontSize: 12.5,
                      fontWeight: FontWeight.w600,
                      color: scheme.primary,
                    ),
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }

  String _changeLabel(ExchangeRateLatest rate) {
    final amount = double.tryParse(rate.changeAmount ?? '');
    if (amount == null) return '';
    final sign = amount >= 0 ? '▲' : '▼';
    return '$sign ${formatNumber(amount.abs())} (${formatRate(rate.changeRate)})';
  }
}

/// 환율 추이 바텀시트 — 기간 탭 + 라인 차트.
class ExchangeRateTrendSheet extends StatefulWidget {
  const ExchangeRateTrendSheet({super.key, required this.api});

  final ExchangeRateApi api;

  static const periods = [
    (value: '1d', label: '1일'),
    (value: '1w', label: '1주'),
    (value: '1m', label: '1개월'),
    (value: '3m', label: '3개월'),
    (value: '1y', label: '1년'),
  ];

  @override
  State<ExchangeRateTrendSheet> createState() => _ExchangeRateTrendSheetState();
}

class _ExchangeRateTrendSheetState extends State<ExchangeRateTrendSheet> {
  String _period = '1m';
  Future<ExchangeRateHistory>? _future;

  @override
  void initState() {
    super.initState();
    _load();
  }

  void _load() {
    _future = widget.api.getHistory(period: _period);
  }

  void _select(String period) {
    if (period == _period) return;
    setState(() {
      _period = period;
      _load();
    });
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 20, 20, 24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    'USD / KRW 환율 추이',
                    style: theme.textTheme.titleLarge,
                  ),
                ),
                TextButton(
                  onPressed: () => Navigator.of(context).pop(),
                  child: const Text('닫기'),
                ),
              ],
            ),
            const SizedBox(height: 12),
            PillTabs<String>(
              options: ExchangeRateTrendSheet.periods,
              value: _period,
              onChanged: _select,
            ),
            const SizedBox(height: 16),
            SizedBox(
              height: 220,
              child: FutureBuilder<ExchangeRateHistory>(
                future: _future,
                builder: (context, snapshot) {
                  if (snapshot.hasError) {
                    return Center(
                      child: Text(
                        '환율 추이를 불러오지 못했어요',
                        style: TextStyle(color: scheme.onSurfaceVariant),
                      ),
                    );
                  }
                  final data = snapshot.data;
                  if (data == null) {
                    return const Center(child: CircularProgressIndicator());
                  }
                  if (data.items.length < 2) {
                    return Center(
                      child: Text(
                        '표시할 데이터가 아직 없어요',
                        style: TextStyle(color: scheme.onSurfaceVariant),
                      ),
                    );
                  }
                  return CustomPaint(
                    painter: _RateLinePainter(
                      items: data.items,
                      color: scheme.primary,
                      gridColor: scheme.outlineVariant,
                    ),
                    size: Size.infinite,
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// 환율 추이 라인 차트. 캔들 차트와 같은 CustomPainter 방식.
class _RateLinePainter extends CustomPainter {
  _RateLinePainter({
    required this.items,
    required this.color,
    required this.gridColor,
  });

  final List<ExchangeRateHistoryItem> items;
  final Color color;
  final Color gridColor;

  @override
  void paint(Canvas canvas, Size size) {
    final values = [
      for (final item in items) double.tryParse(item.rate) ?? double.nan,
    ];
    final valid = values.where((v) => v.isFinite).toList();
    if (valid.length < 2) return;
    final min = valid.reduce((a, b) => a < b ? a : b);
    final max = valid.reduce((a, b) => a > b ? a : b);
    final range = (max - min) == 0 ? 1.0 : max - min;

    final gridPaint = Paint()
      ..color = gridColor
      ..strokeWidth = 0.5;
    for (var i = 1; i <= 3; i++) {
      final y = size.height * i / 4;
      canvas.drawLine(Offset(0, y), Offset(size.width, y), gridPaint);
    }

    final path = Path();
    var started = false;
    for (var i = 0; i < values.length; i++) {
      final v = values[i];
      if (!v.isFinite) continue;
      final x = size.width * i / (values.length - 1);
      final y = size.height - ((v - min) / range) * size.height;
      if (!started) {
        path.moveTo(x, y);
        started = true;
      } else {
        path.lineTo(x, y);
      }
    }
    canvas.drawPath(
      path,
      Paint()
        ..color = color
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2
        ..strokeJoin = StrokeJoin.round,
    );
  }

  @override
  bool shouldRepaint(_RateLinePainter old) => old.items != items;
}
