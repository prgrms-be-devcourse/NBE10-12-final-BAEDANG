import 'dart:math';

import 'package:flutter/material.dart';

import '../../core/api/api_error.dart';
import '../../core/api/stock_api.dart';
import '../../core/models/market_country.dart';
import '../../core/models/stock_financials.dart';
import '../../formatters.dart';
import '../../widgets/app_widgets.dart';

/// 종목 상세의 재무제표 섹션.
/// 미지원 종목(FINANCIALS_NOT_SUPPORTED·STOCK_NOT_FOUND)은 아예 렌더링하지 않는다.
class FinancialsSection extends StatefulWidget {
  const FinancialsSection({
    super.key,
    required this.symbol,
    required this.marketCountry,
    required this.stocks,
  });

  final String symbol;
  final MarketCountry marketCountry;
  final StockApi stocks;

  @override
  State<FinancialsSection> createState() => _FinancialsSectionState();
}

class _FinancialsSectionState extends State<FinancialsSection> {
  Future<StockFinancials>? _future;

  /// 웹과 동일: 연간 3개·분기 6개만 미리 보여주고, 오래된 순으로 그린다.
  static const _previewLimit = {'annual': 3, 'quarterly': 6};

  /// null이면 데이터 기준 기본값(연간 데이터가 있으면 연간, 아니면 분기).
  bool? _annual;

  @override
  void initState() {
    super.initState();
    _load();
  }

  void _load() {
    _future = widget.stocks.getFinancials(
      symbol: widget.symbol,
      marketCountry: widget.marketCountry,
    );
  }

  void _retry() => setState(_load);

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<StockFinancials>(
      future: _future,
      builder: (context, snapshot) {
        if (snapshot.hasError) {
          final error = snapshot.error;
          if (error is ApiException &&
              (error.error.code == 'FINANCIALS_NOT_SUPPORTED' ||
                  error.error.code == 'STOCK_NOT_FOUND')) {
            return const SizedBox.shrink();
          }
          return AppCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('재무제표', style: Theme.of(context).textTheme.titleLarge),
                const SizedBox(height: 8),
                Notice(
                  message: '재무 정보를 조회할 수 없어요. 잠시 후 다시 시도해주세요.',
                  onRetry: _retry,
                ),
              ],
            ),
          );
        }
        final financials = snapshot.data;
        if (financials == null) {
          return const SizedBox(
            height: 120,
            child: Center(child: CircularProgressIndicator()),
          );
        }

        // 웹과 동일: 연간이 없고 분기만 있으면 분기를 기본 선택한다.
        final annual = _annual ?? financials.annual.isNotEmpty;
        final all = annual ? financials.annual : financials.quarterly;
        final limit = _previewLimit[annual ? 'annual' : 'quarterly']!;
        final chartPeriods =
            all.take(limit).toList(growable: false).reversed.toList();

        final theme = Theme.of(context);
        final scheme = theme.colorScheme;
        return AppCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Text('재무제표', style: theme.textTheme.titleLarge),
                  const SizedBox(width: 8),
                  if (financials.industryName != null)
                    Flexible(
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 8,
                          vertical: 2,
                        ),
                        decoration: BoxDecoration(
                          color: scheme.surfaceContainerHighest,
                          borderRadius: BorderRadius.circular(6),
                        ),
                        child: Text(
                          financials.industryName!,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 11.5,
                            fontWeight: FontWeight.w600,
                            color: scheme.onSurfaceVariant,
                          ),
                        ),
                      ),
                    ),
                  const Spacer(),
                  SizedBox(
                    width: 132,
                    child: PillTabs<bool>(
                      options: const [
                        (label: '연간', value: true),
                        (label: '분기', value: false),
                      ],
                      value: annual,
                      onChanged: (v) => setState(() => _annual = v),
                    ),
                  ),
                ],
              ),
              if (financials.isStale)
                Padding(
                  padding: const EdgeInsets.only(top: 4),
                  child: Text(
                    '일부 정보가 최신이 아닐 수 있어요',
                    style: TextStyle(
                      fontSize: 11.5,
                      color: scheme.onSurfaceVariant,
                    ),
                  ),
                ),
              const SizedBox(height: 12),
              _ValuationSummary(financials: financials),
              const SizedBox(height: 12),
              if (all.isEmpty)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 24),
                  child: Center(
                    child: Text(
                      '${annual ? '연간' : '분기'} 재무 정보가 아직 없어요.',
                      style: TextStyle(
                        fontSize: 13,
                        color: scheme.onSurfaceVariant,
                      ),
                    ),
                  ),
                )
              else ...[
                _AmountChart(
                  title: '실적 추이',
                  subtitle: '매출과 이익이 어떻게 변했는지 봐요',
                  periods: chartPeriods,
                  series: [
                    _Series('매출액', scheme.primary, (p) => p.sales),
                    _Series(
                      '영업이익',
                      const Color(0xFFEF4444),
                      (p) => p.operatingProfit,
                    ),
                    _Series(
                      '순이익',
                      const Color(0xFF3B82F6),
                      (p) => p.netIncome,
                    ),
                  ],
                  onExpand: () => _openDetail(
                    '실적 추이',
                    chartPeriods,
                    all,
                    [
                      _Series('매출액', scheme.primary, (p) => p.sales),
                      _Series(
                        '영업이익',
                        const Color(0xFFEF4444),
                        (p) => p.operatingProfit,
                      ),
                      _Series(
                        '순이익',
                        const Color(0xFF3B82F6),
                        (p) => p.netIncome,
                      ),
                    ],
                    [
                      ('매출액', (p) => _formatWon(p.sales)),
                      ('영업이익', (p) => _formatWon(p.operatingProfit)),
                      ('순이익', (p) => _formatWon(p.netIncome)),
                    ],
                  ),
                ),
                const SizedBox(height: 12),
                _RatioChart(
                  title: '수익성 추이',
                  subtitle: '이익을 남기는 힘의 흐름을 비교해요',
                  periods: chartPeriods,
                  series: [
                    _Series(
                      '영업이익률',
                      scheme.primary,
                      (p) => p.operatingProfitMargin,
                    ),
                    _Series(
                      '순이익률',
                      const Color(0xFF3B82F6),
                      (p) => p.netProfitMargin,
                    ),
                    _Series('ROE', const Color(0xFFEF4444), (p) => p.roe),
                  ],
                  onExpand: () => _openRatioDetail(
                    '수익성 추이',
                    chartPeriods,
                    all,
                    [
                      ('영업이익률', (p) => _formatPercent(p.operatingProfitMargin)),
                      ('순이익률', (p) => _formatPercent(p.netProfitMargin)),
                      ('ROE', (p) => _formatPercent(p.roe)),
                    ],
                  ),
                ),
                const SizedBox(height: 12),
                _AmountChart(
                  title: '재무상태 추이',
                  subtitle: '자산·부채·자본의 흐름을 비교해요',
                  periods: chartPeriods,
                  series: [
                    _Series('총자산', scheme.primary, (p) => p.totalAssets),
                    _Series(
                      '총부채',
                      const Color(0xFF3B82F6),
                      (p) => p.totalLiabilities,
                    ),
                    _Series(
                      '총자본',
                      const Color(0xFFEF4444),
                      (p) => p.totalEquity,
                    ),
                  ],
                  onExpand: () => _openDetail(
                    '재무상태 추이',
                    chartPeriods,
                    all,
                    [
                      _Series('총자산', scheme.primary, (p) => p.totalAssets),
                      _Series(
                        '총부채',
                        const Color(0xFF3B82F6),
                        (p) => p.totalLiabilities,
                      ),
                      _Series(
                        '총자본',
                        const Color(0xFFEF4444),
                        (p) => p.totalEquity,
                      ),
                    ],
                    [
                      ('총자산', (p) => _formatWon(p.totalAssets)),
                      ('총부채', (p) => _formatWon(p.totalLiabilities)),
                      ('총자본', (p) => _formatWon(p.totalEquity)),
                      ('부채비율', (p) => _formatPercent(p.debtRatio)),
                    ],
                  ),
                ),
              ],
            ],
          ),
        );
      },
    );
  }

  void _openDetail(
    String title,
    List<FinancialPeriod> chartPeriods,
    List<FinancialPeriod> allPeriods,
    List<_Series> series,
    List<(String, String Function(FinancialPeriod))> columns,
  ) {
    showDialog<void>(
      context: context,
      builder: (context) => _FinancialChartDialog(
        title: title,
        chart: _AmountChart(
          title: title,
          subtitle: null,
          periods: chartPeriods,
          series: series,
          large: true,
        ),
        periods: allPeriods,
        columns: columns,
      ),
    );
  }

  void _openRatioDetail(
    String title,
    List<FinancialPeriod> chartPeriods,
    List<FinancialPeriod> allPeriods,
    List<(String, String Function(FinancialPeriod))> columns,
  ) {
    final scheme = Theme.of(context).colorScheme;
    showDialog<void>(
      context: context,
      builder: (context) => _FinancialChartDialog(
        title: title,
        chart: _RatioChart(
          title: title,
          subtitle: null,
          periods: chartPeriods,
          large: true,
          series: [
            _Series('영업이익률', scheme.primary, (p) => p.operatingProfitMargin),
            _Series(
              '순이익률',
              const Color(0xFF3B82F6),
              (p) => p.netProfitMargin,
            ),
            _Series('ROE', const Color(0xFFEF4444), (p) => p.roe),
          ],
        ),
        periods: allPeriods,
        columns: columns,
      ),
    );
  }

  static String _formatWon(String? value) =>
      value == null ? '-' : '${formatNumber(value)}원';

  static String _formatPercent(String? value) {
    final n = value == null ? null : double.tryParse(value);
    return n == null ? '-' : '${n.toStringAsFixed(2)}%';
  }
}

/// 크게 보기 팝업 — 차트 + 해당 지표 전체 기간 표.
class _FinancialChartDialog extends StatelessWidget {
  const _FinancialChartDialog({
    required this.title,
    required this.chart,
    required this.periods,
    required this.columns,
  });

  final String title;
  final Widget chart;
  final List<FinancialPeriod> periods;
  final List<(String, String Function(FinancialPeriod))> columns;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return Dialog(
      insetPadding: const EdgeInsets.all(16),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(title, style: theme.textTheme.titleMedium),
                ),
                TextButton(
                  onPressed: () => Navigator.pop(context),
                  style: TextButton.styleFrom(
                    backgroundColor: scheme.surfaceContainerHighest,
                    padding: const EdgeInsets.symmetric(
                      horizontal: 12,
                      vertical: 6,
                    ),
                    minimumSize: Size.zero,
                    tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  ),
                  child: const Text('닫기', style: TextStyle(fontSize: 11.5)),
                ),
              ],
            ),
            chart,
            const SizedBox(height: 8),
            Flexible(
              child: SingleChildScrollView(
                child: Table(
                  columnWidths: const {0: FixedColumnWidth(72)},
                  children: [
                    TableRow(
                      children: [
                        Padding(
                          padding: const EdgeInsets.symmetric(vertical: 6),
                          child: Text(
                            '기준월',
                            style: TextStyle(
                              fontSize: 12,
                              fontWeight: FontWeight.w700,
                              color: scheme.onSurfaceVariant,
                            ),
                          ),
                        ),
                        for (final (label, _) in columns)
                          Padding(
                            padding: const EdgeInsets.symmetric(vertical: 6),
                            child: Text(
                              label,
                              textAlign: TextAlign.right,
                              style: TextStyle(
                                fontSize: 12,
                                fontWeight: FontWeight.w700,
                                color: scheme.onSurfaceVariant,
                              ),
                            ),
                          ),
                      ],
                    ),
                    for (final p in periods)
                      TableRow(
                        children: [
                          Padding(
                            padding: const EdgeInsets.symmetric(vertical: 6),
                            child: Text(
                              p.label,
                              style: const TextStyle(fontSize: 13),
                            ),
                          ),
                          for (final (_, get) in columns)
                            Padding(
                              padding: const EdgeInsets.symmetric(vertical: 6),
                              child: Text(
                                get(p),
                                textAlign: TextAlign.right,
                                style: const TextStyle(
                                  fontSize: 13,
                                  fontFeatures: [],
                                ),
                              ),
                            ),
                        ],
                      ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _ValuationSummary extends StatelessWidget {
  const _ValuationSummary({required this.financials});

  final StockFinancials financials;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final per = _calculatedPerLabel();
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      decoration: BoxDecoration(
        color: scheme.primaryContainer.withValues(alpha: 0.4),
        borderRadius: BorderRadius.circular(14),
      ),
      child: Row(
        children: [
          Text(
            '계산 PER',
            style: TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w700,
              color: scheme.onSurfaceVariant,
            ),
          ),
          const SizedBox(width: 10),
          Text(
            per,
            style: TextStyle(
              fontSize: 20,
              fontWeight: FontWeight.w800,
              color: scheme.primary,
            ),
          ),
          const SizedBox(width: 10),
          Flexible(
            child: Text(
              '최근 연간 EPS 기준',
              textAlign: TextAlign.right,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(fontSize: 11.5, color: scheme.onSurfaceVariant),
            ),
          ),
        ],
      ),
    );
  }

  /// 웹 `formatCalculatedPer`: per>0이면 "N배", 적자 EPS면 "적자", 아니면 "-".
  String _calculatedPerLabel() {
    final per = double.tryParse(financials.calculatedPer ?? '');
    if (per != null && per > 0) return '${per.toStringAsFixed(2)}배';
    final latestEps = financials.annual.isEmpty
        ? null
        : double.tryParse(financials.annual.first.eps ?? '');
    return latestEps != null && latestEps < 0 ? '적자' : '-';
  }
}

class _Series {
  const _Series(this.label, this.color, this.value);

  final String label;
  final Color color;
  final String? Function(FinancialPeriod) value;
}

double? _num(String? v) => v == null ? null : double.tryParse(v);

/// 그룹 막대그래프 카드(실적·재무상태).
class _AmountChart extends StatelessWidget {
  const _AmountChart({
    required this.title,
    required this.subtitle,
    required this.periods,
    required this.series,
    this.large = false,
    this.onExpand,
  });

  final String title;
  final String? subtitle;
  final List<FinancialPeriod> periods;
  final List<_Series> series;
  final bool large;
  final VoidCallback? onExpand;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final values = [
      for (final s in series)
        for (final p in periods) _num(s.value(p)),
    ].whereType<double>().toList();

    return _ChartCard(
      title: title,
      subtitle: subtitle,
      onExpand: onExpand,
      child: values.isEmpty
          ? _emptyChart(scheme)
          : Column(
              children: [
                SizedBox(
                  height: large ? 190 : 150,
                  width: double.infinity,
                  child: CustomPaint(
                    painter: _BarChartPainter(
                      periods: periods,
                      series: series,
                      labelColor: scheme.onSurfaceVariant,
                      gridColor: scheme.outlineVariant,
                    ),
                  ),
                ),
                _Legend(series: series),
              ],
            ),
    );
  }
}

/// 비율 선그래프 카드(수익성). 음수(적자)도 축에 포함한다.
class _RatioChart extends StatelessWidget {
  const _RatioChart({
    required this.title,
    required this.subtitle,
    required this.periods,
    required this.series,
    this.large = false,
    this.onExpand,
  });

  final String title;
  final String? subtitle;
  final List<FinancialPeriod> periods;
  final List<_Series> series;
  final bool large;
  final VoidCallback? onExpand;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final values = [
      for (final s in series)
        for (final p in periods) _num(s.value(p)),
    ].whereType<double>().toList();

    return _ChartCard(
      title: title,
      subtitle: subtitle,
      onExpand: onExpand,
      child: values.isEmpty
          ? _emptyChart(scheme)
          : Column(
              children: [
                SizedBox(
                  height: large ? 190 : 150,
                  width: double.infinity,
                  child: CustomPaint(
                    painter: _LineChartPainter(
                      periods: periods,
                      series: series,
                      labelColor: scheme.onSurfaceVariant,
                      gridColor: scheme.outlineVariant,
                    ),
                  ),
                ),
                _Legend(series: series),
              ],
            ),
    );
  }
}

Widget _emptyChart(ColorScheme scheme) => SizedBox(
      height: 120,
      child: Center(
        child: Text(
          '그래프로 볼 수 있는 데이터가 아직 없어요.',
          style: TextStyle(fontSize: 13, color: scheme.onSurfaceVariant),
        ),
      ),
    );

class _ChartCard extends StatelessWidget {
  const _ChartCard({
    required this.title,
    required this.subtitle,
    required this.child,
    this.onExpand,
  });

  final String title;
  final String? subtitle;
  final Widget child;
  final VoidCallback? onExpand;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.fromLTRB(14, 12, 14, 10),
      decoration: BoxDecoration(
        color: scheme.surface,
        border: Border.all(color: scheme.outlineVariant),
        borderRadius: BorderRadius.circular(14),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      style: const TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    if (subtitle != null)
                      Padding(
                        padding: const EdgeInsets.only(top: 2),
                        child: Text(
                          subtitle!,
                          style: TextStyle(
                            fontSize: 11.5,
                            color: scheme.onSurfaceVariant,
                          ),
                        ),
                      ),
                  ],
                ),
              ),
              if (onExpand != null)
                TextButton(
                  onPressed: onExpand,
                  style: TextButton.styleFrom(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 10,
                      vertical: 6,
                    ),
                    minimumSize: Size.zero,
                    tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  ),
                  child: const Text('크게 보기', style: TextStyle(fontSize: 11.5)),
                ),
            ],
          ),
          const SizedBox(height: 8),
          child,
        ],
      ),
    );
  }
}

class _Legend extends StatelessWidget {
  const _Legend({required this.series});

  final List<_Series> series;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.only(top: 8, left: 4),
      child: Wrap(
        spacing: 14,
        runSpacing: 4,
        children: [
          for (final s in series)
            Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Container(
                  width: 7,
                  height: 7,
                  decoration: BoxDecoration(
                    color: s.color,
                    shape: BoxShape.circle,
                  ),
                ),
                const SizedBox(width: 5),
                Text(
                  s.label,
                  style: TextStyle(
                    fontSize: 11.5,
                    color: scheme.onSurfaceVariant,
                  ),
                ),
              ],
            ),
        ],
      ),
    );
  }
}

class _BarChartPainter extends CustomPainter {
  _BarChartPainter({
    required this.periods,
    required this.series,
    required this.labelColor,
    required this.gridColor,
  });

  final List<FinancialPeriod> periods;
  final List<_Series> series;
  final Color labelColor;
  final Color gridColor;

  @override
  void paint(Canvas canvas, Size size) {
    const labelHeight = 18.0;
    final plotHeight = size.height - labelHeight;
    if (periods.isEmpty || plotHeight <= 0) return;

    var maxAbs = 1.0;
    for (final s in series) {
      for (final p in periods) {
        final v = _num(s.value(p));
        if (v != null) maxAbs = max(maxAbs, v.abs());
      }
    }

    final baseline = plotHeight;
    // 기준선.
    canvas.drawLine(
      Offset(0, baseline),
      Offset(size.width, baseline),
      Paint()
        ..color = gridColor
        ..strokeWidth = 1,
    );

    final groupWidth = size.width / periods.length;
    final barWidth = min(24.0, max(8.0, groupWidth / (series.length + 2)));

    for (var i = 0; i < periods.length; i++) {
      final center = groupWidth * (i + 0.5);
      for (var j = 0; j < series.length; j++) {
        final v = _num(series[j].value(periods[i]));
        if (v == null) continue;
        final barHeight = max(3.0, v.abs() / maxAbs * (plotHeight - 12));
        final x = center +
            (j - (series.length - 1) / 2) * (barWidth + 3) -
            barWidth / 2;
        final y = v >= 0 ? baseline - barHeight : baseline;
        canvas.drawRRect(
          RRect.fromRectAndRadius(
            Rect.fromLTWH(x, y, barWidth, barHeight),
            const Radius.circular(4),
          ),
          Paint()..color = series[j].color.withValues(alpha: 0.9),
        );
      }
      _drawLabel(
        canvas,
        periods[i].label,
        Offset(center, baseline + 2),
        labelColor,
        size.width,
      );
    }
  }

  void _drawLabel(
    Canvas canvas,
    String text,
    Offset topCenter,
    Color color,
    double maxWidth,
  ) {
    final tp = TextPainter(
      text: TextSpan(text: text, style: TextStyle(fontSize: 11, color: color)),
      textDirection: TextDirection.ltr,
    )..layout();
    final dx = (topCenter.dx - tp.width / 2).clamp(0.0, maxWidth - tp.width);
    tp.paint(canvas, Offset(dx, topCenter.dy));
  }

  @override
  bool shouldRepaint(_BarChartPainter old) =>
      old.periods != periods || old.series != series;
}

class _LineChartPainter extends CustomPainter {
  _LineChartPainter({
    required this.periods,
    required this.series,
    required this.labelColor,
    required this.gridColor,
  });

  final List<FinancialPeriod> periods;
  final List<_Series> series;
  final Color labelColor;
  final Color gridColor;

  @override
  void paint(Canvas canvas, Size size) {
    const labelHeight = 18.0;
    const axisLabelWidth = 34.0;
    final left = axisLabelWidth;
    final right = size.width;
    final top = 8.0;
    final bottom = size.height - labelHeight - 6;
    if (periods.isEmpty || bottom <= top) return;

    var maxValue = 100.0;
    var minValue = 0.0;
    for (final s in series) {
      for (final p in periods) {
        final v = _num(s.value(p));
        if (v != null) {
          maxValue = max(maxValue, v);
          minValue = min(minValue, v);
        }
      }
    }
    final range = maxValue - minValue;
    if (range <= 0) return;

    double x(int i) => periods.length == 1
        ? (left + right) / 2
        : left + (right - left) * i / (periods.length - 1);
    double y(double v) => bottom - (v - minValue) / range * (bottom - top);

    final gridPaint = Paint()
      ..color = gridColor
      ..strokeWidth = 1;
    canvas.drawLine(Offset(left, top), Offset(right, top), gridPaint);
    canvas.drawLine(Offset(left, bottom), Offset(right, bottom), gridPaint);

    final labelStyle = TextStyle(fontSize: 10, color: labelColor);
    void yLabel(String text, double yPos) {
      final tp = TextPainter(
        text: TextSpan(text: text, style: labelStyle),
        textDirection: TextDirection.ltr,
      )..layout();
      tp.paint(canvas, Offset(0, yPos - tp.height / 2));
    }

    yLabel('${maxValue.round()}%', top);
    // 음수가 있으면 0% 기준선을 따로 그어 적자 구간을 구분한다.
    if (minValue < 0) {
      final zeroY = y(0);
      final dash = Paint()
        ..color = labelColor.withValues(alpha: 0.5)
        ..strokeWidth = 1;
      canvas.drawLine(Offset(left, zeroY), Offset(right, zeroY), dash);
      yLabel('0%', zeroY);
      yLabel('${minValue.round()}%', bottom);
    }

    for (final s in series) {
      final path = Path();
      var started = false;
      for (var i = 0; i < periods.length; i++) {
        final v = _num(s.value(periods[i]));
        if (v == null) continue;
        if (started) {
          path.lineTo(x(i), y(v));
        } else {
          path.moveTo(x(i), y(v));
          started = true;
        }
      }
      canvas.drawPath(
        path,
        Paint()
          ..color = s.color
          ..style = PaintingStyle.stroke
          ..strokeWidth = 2.5
          ..strokeCap = StrokeCap.round
          ..strokeJoin = StrokeJoin.round,
      );
    }

    for (var i = 0; i < periods.length; i++) {
      final tp = TextPainter(
        text: TextSpan(
          text: periods[i].label,
          style: TextStyle(fontSize: 11, color: labelColor),
        ),
        textDirection: TextDirection.ltr,
      )..layout();
      final dx = (x(i) - tp.width / 2).clamp(left, right - tp.width);
      tp.paint(canvas, Offset(dx, bottom + 4));
    }
  }

  @override
  bool shouldRepaint(_LineChartPainter old) =>
      old.periods != periods || old.series != series;
}
