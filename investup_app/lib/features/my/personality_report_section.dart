import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../core/api/report_api.dart';
import '../../core/models/personality_report.dart';
import '../../formatters.dart';
import '../../widgets/app_widgets.dart';
import 'personality_types.dart';

final _dateFormat = DateFormat('yyyy.MM.dd');

/// "1주 3일"처럼 표시한다. 정확히 주 단위면 "일"을 생략한다.
String _formatWeeksDays(Duration d) {
  final totalDays = d.inMilliseconds <= 0 ? 0 : (d.inHours / 24).round();
  final weeks = totalDays ~/ 7;
  final days = totalDays % 7;
  if (weeks == 0) return '$days일';
  if (days == 0) return '$weeks주';
  return '$weeks주 $days일';
}

/// 마이페이지 투자 성향 리포트 — 웹 `PersonalityReportSection.tsx` 포팅.
///
/// 세 가지 상태를 그린다: 아직 N주가 안 지난 "잠김", 지났지만 보유 종목이
/// 2개 미만이라 유형을 못 정하는 "미분류", 유형이 정해진 "공개". 잠금 해제
/// 이후에는 열 때마다 다시 계산된 asOf 시각을 보여준다.
class PersonalityReportSection extends StatefulWidget {
  const PersonalityReportSection({
    super.key,
    required this.reports,
    this.tick = 0,
  });

  final ReportApi reports;

  /// 부모의 갱신 신호 — 값이 바뀌면 리포트를 다시 읽는다(당겨서 새로고침·
  /// 계좌 초기화 등). 리포트는 고정 스냅샷이 아니라 열 때마다 다시 계산된다.
  final int tick;

  @override
  State<PersonalityReportSection> createState() =>
      _PersonalityReportSectionState();
}

class _PersonalityReportSectionState extends State<PersonalityReportSection> {
  Future<PersonalityReport>? _future;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  @override
  void didUpdateWidget(PersonalityReportSection oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.tick != widget.tick) _reload();
  }

  void _reload() {
    setState(() {
      _future = widget.reports.getMyReport();
    });
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<PersonalityReport>(
      future: _future,
      builder: (context, snap) {
        if (snap.connectionState != ConnectionState.done) {
          return const AppCard(
            child: Padding(
              padding: EdgeInsets.symmetric(vertical: 48),
              child: Center(child: Text('투자 성향 리포트를 불러오는 중…')),
            ),
          );
        }
        final report = snap.data;
        if (snap.hasError || report == null) {
          return Notice(
            message: '투자 성향 리포트를 불러오지 못했어요. 잠시 후 다시 시도해주세요.',
            onRetry: _reload,
          );
        }
        return _ReportCard(report: report, reports: widget.reports);
      },
    );
  }
}

class _ReportCard extends StatelessWidget {
  const _ReportCard({required this.report, required this.reports});

  final PersonalityReport report;
  final ReportApi reports;

  void _showHelp(BuildContext context) {
    showDialog<void>(
      context: context,
      builder: (context) => const _HelpDialog(),
    );
  }

  void _showLeaderboard(BuildContext context) {
    showDialog<void>(
      context: context,
      builder: (context) => _LeaderboardDialog(reports: reports),
    );
  }

  void _showTypeComparison(BuildContext context) {
    showDialog<void>(
      context: context,
      builder: (context) =>
          _TypeComparisonDialog(reports: reports, myTypeCode: report.typeCode),
    );
  }

  @override
  Widget build(BuildContext context) {
    return report.locked
        ? _LockedReportCard(report: report, onHelp: () => _showHelp(context))
        : _OpenReportCard(
            report: report,
            onHelp: () => _showHelp(context),
            onOpenBoard: () => _showLeaderboard(context),
            onOpenTypeBoard: () => _showTypeComparison(context),
          );
  }
}

class _CardHeader extends StatelessWidget {
  const _CardHeader({
    required this.title,
    required this.subtitle,
    required this.badge,
    required this.onHelp,
  });

  final String title;
  final String subtitle;
  final Widget badge;
  final VoidCallback onHelp;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      padding: const EdgeInsets.fromLTRB(20, 18, 20, 16),
      decoration: BoxDecoration(
        border: Border(
          bottom: BorderSide(color: theme.colorScheme.outlineVariant),
        ),
      ),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Text(title, style: theme.textTheme.titleLarge),
                    const SizedBox(width: 6),
                    InkWell(
                      onTap: onHelp,
                      borderRadius: BorderRadius.circular(11),
                      child: Container(
                        width: 22,
                        height: 22,
                        alignment: Alignment.center,
                        decoration: BoxDecoration(
                          shape: BoxShape.circle,
                          color: theme.colorScheme.surfaceContainerHighest,
                        ),
                        child: Text(
                          '?',
                          style: TextStyle(
                            fontSize: 12,
                            fontWeight: FontWeight.w700,
                            color: theme.colorScheme.onSurfaceVariant,
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 4),
                Text(
                  subtitle,
                  style: TextStyle(
                    fontSize: 13,
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
              ],
            ),
          ),
          badge,
        ],
      ),
    );
  }
}

class _Badge extends StatelessWidget {
  const _Badge({required this.text, required this.locked});

  final String text;
  final bool locked;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      decoration: BoxDecoration(
        color: locked
            ? theme.colorScheme.surfaceContainerHighest
            : theme.colorScheme.primary.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        text,
        style: TextStyle(
          fontSize: 12,
          fontWeight: FontWeight.w700,
          color: locked
              ? theme.colorScheme.onSurfaceVariant
              : theme.colorScheme.primary,
        ),
      ),
    );
  }
}

class _LockedReportCard extends StatelessWidget {
  const _LockedReportCard({required this.report, required this.onHelp});

  final PersonalityReport report;
  final VoidCallback onHelp;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    // 잠김 응답엔 개설일이 없어 unlockAt(=개설일 + N주)에서 거꾸로 계산한다.
    final totalDays = report.holdingPeriodWeeks * 7;
    final unlockAt = report.unlockAt;
    final now = report.asOf ?? DateTime.now();
    final remain = unlockAt != null && unlockAt.isAfter(now)
        ? unlockAt.difference(now)
        : Duration.zero;
    final elapsed = Duration(days: totalDays) - remain;
    final percent = totalDays > 0
        ? (elapsed.inHours / (totalDays * 24) * 100).round().clamp(0, 100)
        : 0;
    final start = unlockAt?.subtract(Duration(days: totalDays));

    return AppCard(
      padding: EdgeInsets.zero,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _CardHeader(
            title: '투자 성향 리포트',
            subtitle: '${report.holdingPeriodWeeks}주 단위로 발급돼요',
            badge: const _Badge(text: '잠김', locked: true),
            onHelp: onHelp,
          ),
          Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Container(
                  padding: const EdgeInsets.symmetric(vertical: 36),
                  decoration: BoxDecoration(
                    color: theme.colorScheme.surfaceContainerHighest,
                    borderRadius: BorderRadius.circular(20),
                    border: Border.all(
                      color: theme.colorScheme.outlineVariant,
                      // dashed는 없으니 단색으로 대신한다.
                    ),
                  ),
                  child: Column(
                    children: [
                      Icon(
                        Icons.lock_outline,
                        size: 34,
                        color: theme.colorScheme.onSurfaceVariant,
                      ),
                      const SizedBox(height: 12),
                      Text(
                        '아직 공개 전이에요',
                        style: TextStyle(
                          fontSize: 13,
                          fontWeight: FontWeight.w700,
                          color: theme.colorScheme.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 20),
                Text(
                  '투자 성향 리포트는 ${report.holdingPeriodWeeks}주 뒤에 열려요',
                  style: const TextStyle(
                    fontSize: 20,
                    fontWeight: FontWeight.w800,
                    height: 1.3,
                  ),
                ),
                const SizedBox(height: 10),
                Text.rich(
                  TextSpan(
                    children: [
                      TextSpan(
                        text:
                            '계좌를 ${report.holdingPeriodWeeks}주 동안 굴려야 '
                            '종목 비중·변동성이 성향으로 굳어져요. 지금 계좌는 ',
                      ),
                      TextSpan(
                        text: _formatWeeksDays(elapsed),
                        style: const TextStyle(fontWeight: FontWeight.w700),
                      ),
                      const TextSpan(text: ' 지났고, '),
                      TextSpan(
                        text: _formatWeeksDays(remain),
                        style: TextStyle(
                          fontWeight: FontWeight.w700,
                          color: theme.colorScheme.primary,
                        ),
                      ),
                      const TextSpan(text: ' 남았어요.'),
                    ],
                  ),
                  style: TextStyle(
                    fontSize: 14,
                    height: 1.7,
                    color: theme.colorScheme.onSurface,
                  ),
                ),
                const SizedBox(height: 20),
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(
                      start != null
                          ? '${_dateFormat.format(start)} 시작'
                          : '',
                      style: TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w700,
                        color: theme.colorScheme.onSurfaceVariant,
                      ),
                    ),
                    Text(
                      '$percent%',
                      style: TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w700,
                        color: theme.colorScheme.onSurfaceVariant,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                ClipRRect(
                  borderRadius: BorderRadius.circular(999),
                  child: LinearProgressIndicator(
                    value: percent / 100,
                    minHeight: 10,
                    backgroundColor:
                        theme.colorScheme.surfaceContainerHighest,
                    valueColor: AlwaysStoppedAnimation(
                      theme.colorScheme.primary,
                    ),
                  ),
                ),
                const SizedBox(height: 8),
                if (unlockAt != null)
                  Text(
                    '${_dateFormat.format(unlockAt)} 공개 예정',
                    style: TextStyle(
                      fontSize: 12,
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
                const SizedBox(height: 20),
                FilledButton(
                  onPressed: null,
                  child: const Text('지금은 볼 수 없어요'),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _OpenReportCard extends StatelessWidget {
  const _OpenReportCard({
    required this.report,
    required this.onHelp,
    required this.onOpenBoard,
    required this.onOpenTypeBoard,
  });

  final PersonalityReport report;
  final VoidCallback onHelp;
  final VoidCallback onOpenBoard;
  final VoidCallback onOpenTypeBoard;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final shares = report.shares;
    final personaType = report.typeCode != null
        ? kPersonalityTypes[report.typeCode]
        : null;
    final classified = report.classified && report.typeCode != null;

    return AppCard(
      padding: EdgeInsets.zero,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _CardHeader(
            title: '투자 성향 리포트',
            subtitle:
                '${report.roundNo}회차 · '
                '${report.asOf != null ? _dateFormat.format(report.asOf!) : '-'}'
                ' 기준으로 다시 계산했어요',
            badge: _Badge(
              text: report.classified ? '공개' : '미분류',
              locked: false,
            ),
            onHelp: onHelp,
          ),
          if (classified && shares != null)
            Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      // 유형 이미지 — 웹은 public/personality-types/CODE.webp를
                      // 보여주고 탭하면 확대 모달을 연다. 이미지가 등록되지
                      // 않은 유형은 코드 텍스트 카드로 폴백한다.
                      _TypeImage(
                        typeCode: report.typeCode!,
                        personaType: personaType,
                        size: 88,
                      ),
                      const SizedBox(width: 16),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              '이번 회차 투자 성향',
                              style: TextStyle(
                                fontSize: 12.5,
                                fontWeight: FontWeight.w700,
                                color: theme.colorScheme.onSurfaceVariant,
                              ),
                            ),
                            const SizedBox(height: 4),
                            Text(
                              personaType?.nickname ??
                                  report.typeLabel ??
                                  '',
                              style: const TextStyle(
                                fontSize: 22,
                                fontWeight: FontWeight.w800,
                                height: 1.25,
                              ),
                            ),
                            const SizedBox(height: 4),
                            Text(
                              report.typeLabel ?? '',
                              style: TextStyle(
                                fontSize: 12.5,
                                color:
                                    theme.colorScheme.onSurfaceVariant,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                  if (personaType != null) ...[
                    const SizedBox(height: 14),
                    Text(
                      personaType.description,
                      style: TextStyle(
                        fontSize: 14,
                        height: 1.7,
                        color: theme.colorScheme.onSurface,
                      ),
                    ),
                  ],
                  const SizedBox(height: 20),
                  _AxisBar(
                    axis: kPersonalityAxes[0],
                    share: shares.concentration,
                    letter: report.typeCode![0],
                  ),
                  const SizedBox(height: 14),
                  _AxisBar(
                    axis: kPersonalityAxes[1],
                    share: shares.domestic,
                    letter: report.typeCode![1],
                  ),
                  const SizedBox(height: 14),
                  _AxisBar(
                    axis: kPersonalityAxes[2],
                    share: shares.individual,
                    letter: report.typeCode![2],
                  ),
                  const SizedBox(height: 14),
                  _AxisBar(
                    axis: kPersonalityAxes[3],
                    share: shares.aggressive,
                    letter: report.typeCode![3],
                  ),
                ],
              ),
            )
          else
            Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                children: [
                  Container(
                    width: 64,
                    height: 64,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: theme.colorScheme.surfaceContainerHighest,
                    ),
                    child: const Center(
                      child: Text('🌱', style: TextStyle(fontSize: 22)),
                    ),
                  ),
                  const SizedBox(height: 14),
                  const Text(
                    '아직 성향을 정하기엔 종목이 부족해요',
                    style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    '보유 종목이 ${report.holdingCount}개예요 — '
                    '2개 이상 보유하면 다음번에 유형이 나와요.',
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      fontSize: 13.5,
                      height: 1.6,
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
                ],
              ),
            ),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 0, 20, 20),
            child: Column(
              children: [
                _ReportStat(
                  label: '회차 수익률',
                  value: formatRate(report.returnRate),
                  sub: '초기자본 대비 총손익 기준',
                  color: changeColor(report.returnRate, theme.colorScheme),
                ),
                const SizedBox(height: 10),
                _ReportStat(
                  label: '총 자산',
                  value: '${formatNumber(report.totalAsset)}원',
                  sub: '시작 ${formatNumber(report.initialCash)}원',
                ),
                const SizedBox(height: 10),
                _ReportStat(
                  label: '예수금',
                  value: '${formatNumber(report.cashBalance)}원',
                  sub: '주식 평가금액 ${formatNumber(report.stockValue)}원',
                ),
              ],
            ),
          ),
          if (report.longHeldStocks.isNotEmpty) ...[
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 0, 20, 20),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.baseline,
                    textBaseline: TextBaseline.alphabetic,
                    children: [
                      Text(
                        '${report.holdingPeriodWeeks}주 이상 보유한 종목',
                        style: const TextStyle(
                          fontSize: 15,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      const SizedBox(width: 8),
                      Text(
                        '가장 오래 보유한 순',
                        style: TextStyle(
                          fontSize: 12,
                          color: theme.colorScheme.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 10),
                  Container(
                    decoration: BoxDecoration(
                      border: Border.all(
                        color: theme.colorScheme.outlineVariant,
                      ),
                      borderRadius: BorderRadius.circular(16),
                    ),
                    child: Column(
                      children: [
                        _LongHeldHeader(theme: theme),
                        for (final h in report.longHeldStocks)
                          _LongHeldRow(stock: h, theme: theme),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ],
          Container(
            padding: const EdgeInsets.all(20),
            decoration: BoxDecoration(
              color: theme.scaffoldBackgroundColor,
              border: Border(
                top: BorderSide(color: theme.colorScheme.outlineVariant),
              ),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const Text(
                  '수익률 리더보드',
                  style: TextStyle(fontSize: 15, fontWeight: FontWeight.w700),
                ),
                const SizedBox(height: 4),
                Text(
                  '내 순위와 상위 백분율을 함께 보여드려요 · '
                  '절대 금액·닉네임 전체는 공개되지 않아요',
                  style: TextStyle(
                    fontSize: 12.5,
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
                const SizedBox(height: 14),
                Row(
                  children: [
                    Expanded(
                      child: OutlinedButton(
                        onPressed: onOpenTypeBoard,
                        child: const Text('유형별 비교 보기'),
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: FilledButton(
                        onPressed: onOpenBoard,
                        child: const Text('전체 랭킹 보기'),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// 한 축의 비중 바 — 왼쪽이 "높은 쪽"(집중C·국내K·개별주S·공격A),
/// 마커가 왼쪽에 붙을수록 그쪽 비중이 높다.
class _AxisBar extends StatelessWidget {
  const _AxisBar({
    required this.axis,
    required this.share,
    required this.letter,
  });

  final PersonalityAxis axis;
  final String? share;
  final String letter;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final shareValue = double.tryParse(share ?? '') ?? 0;
    final isHigh = letter == axis.highLetter;
    final sharePercent = (shareValue * 100).round();
    final valueLetter = isHigh ? axis.highLetter : axis.lowLetter;
    final valuePercent = isHigh ? sharePercent : 100 - sharePercent;
    final fill = (1 - shareValue).clamp(0.0, 1.0);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            SizedBox(
              width: 44,
              child: Text(
                axis.title,
                style: TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w700,
                  color: theme.colorScheme.primary,
                ),
              ),
            ),
            Text(
              '${axis.highName} ${axis.highLetter}',
              style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w700,
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
            const Spacer(),
            Text(
              '${axis.lowName} ${axis.lowLetter}',
              style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w700,
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
          ],
        ),
        const SizedBox(height: 6),
        LayoutBuilder(
          builder: (context, constraints) {
            final markerLeft = constraints.maxWidth * fill;
            return Stack(
              children: [
                Container(
                  height: 26,
                  decoration: BoxDecoration(
                    color: theme.colorScheme.surfaceContainerHighest,
                    borderRadius: BorderRadius.circular(999),
                  ),
                ),
                Positioned(
                  left: 0,
                  top: 0,
                  bottom: 0,
                  width: markerLeft,
                  child: Container(
                    decoration: BoxDecoration(
                      color: theme.colorScheme.primary
                          .withValues(alpha: 0.18),
                      borderRadius: BorderRadius.circular(999),
                    ),
                  ),
                ),
                Positioned(
                  left: (markerLeft - 2).clamp(0.0, constraints.maxWidth - 4),
                  top: 0,
                  bottom: 0,
                  width: 4,
                  child: Container(
                    decoration: BoxDecoration(
                      color: theme.colorScheme.primary,
                      borderRadius: BorderRadius.circular(2),
                    ),
                  ),
                ),
                Positioned.fill(
                  child: Align(
                    alignment: Alignment.centerRight,
                    child: Padding(
                      padding: const EdgeInsets.only(right: 14),
                      child: Text(
                        '$valueLetter $valuePercent%',
                        style: TextStyle(
                          fontSize: 12,
                          fontWeight: FontWeight.w700,
                          color: theme.colorScheme.primary,
                        ),
                      ),
                    ),
                  ),
                ),
              ],
            );
          },
        ),
      ],
    );
  }
}

class _ReportStat extends StatelessWidget {
  const _ReportStat({
    required this.label,
    required this.value,
    required this.sub,
    this.color,
  });

  final String label;
  final String value;
  final String sub;
  final Color? color;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: theme.scaffoldBackgroundColor,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            label,
            style: TextStyle(
              fontSize: 12.5,
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ),
          const SizedBox(height: 6),
          Text(
            value,
            style: TextStyle(
              fontSize: 20,
              fontWeight: FontWeight.w800,
              color: color ?? theme.colorScheme.onSurface,
            ),
          ),
          const SizedBox(height: 4),
          Text(
            sub,
            style: TextStyle(
              fontSize: 12,
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ),
        ],
      ),
    );
  }
}

class _LongHeldHeader extends StatelessWidget {
  const _LongHeldHeader({required this.theme});

  final ThemeData theme;

  @override
  Widget build(BuildContext context) {
    final style = TextStyle(
      fontSize: 12,
      fontWeight: FontWeight.w700,
      color: theme.colorScheme.onSurfaceVariant,
    );
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerHighest,
        borderRadius: const BorderRadius.vertical(top: Radius.circular(16)),
      ),
      child: Row(
        children: [
          Expanded(flex: 16, child: Text('종목', style: style)),
          Expanded(
            flex: 10,
            child: Text('평균단가', textAlign: TextAlign.right, style: style),
          ),
          Expanded(
            flex: 10,
            child: Text('현재가', textAlign: TextAlign.right, style: style),
          ),
          Expanded(
            flex: 10,
            child: Text('수익률', textAlign: TextAlign.right, style: style),
          ),
        ],
      ),
    );
  }
}

class _LongHeldRow extends StatelessWidget {
  const _LongHeldRow({required this.stock, required this.theme});

  final LongHeldStock stock;
  final ThemeData theme;

  @override
  Widget build(BuildContext context) {
    final currency = stock.currency ?? 'KRW';
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      decoration: BoxDecoration(
        border: Border(
          top: BorderSide(color: theme.colorScheme.outlineVariant),
        ),
      ),
      child: Row(
        children: [
          Expanded(
            flex: 16,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  stock.name,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                Text(
                  '${stock.symbol} · '
                  '${stock.heldSince != null ? _dateFormat.format(stock.heldSince!) : '-'}~',
                  style: TextStyle(
                    fontSize: 11.5,
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
              ],
            ),
          ),
          Expanded(
            flex: 10,
            child: Text(
              formatMoney(stock.avgBuyPrice, currency),
              textAlign: TextAlign.right,
              style: const TextStyle(fontSize: 13.5),
            ),
          ),
          Expanded(
            flex: 10,
            child: Text(
              formatMoney(stock.lastPrice, currency),
              textAlign: TextAlign.right,
              style: const TextStyle(fontSize: 13.5),
            ),
          ),
          Expanded(
            flex: 10,
            child: Text(
              formatRate(stock.returnRate),
              textAlign: TextAlign.right,
              style: TextStyle(
                fontSize: 13.5,
                fontWeight: FontWeight.w600,
                color: changeColor(stock.returnRate, theme.colorScheme),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// 성향 판정 기준 도움말 — 웹 HelpModal과 같은 4축 설명.
class _HelpDialog extends StatelessWidget {
  const _HelpDialog();

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return AlertDialog(
      title: const Text('투자 성향은 이렇게 정해져요'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              '4개 축을 조합해 16가지 유형이 나와요',
              style: TextStyle(
                fontSize: 13,
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 14),
            for (final item in kPersonalityHelpItems) ...[
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(14),
                decoration: BoxDecoration(
                  color: theme.scaffoldBackgroundColor,
                  borderRadius: BorderRadius.circular(14),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      item.title,
                      style: TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w700,
                        color: theme.colorScheme.primary,
                      ),
                    ),
                    const SizedBox(height: 6),
                    Text(
                      item.desc,
                      style: TextStyle(
                        fontSize: 13,
                        height: 1.65,
                        color: theme.colorScheme.onSurface,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 10),
            ],
            Text(
              '4주 창의 원가 구성을 시점별로 뽑아 평균낸 값으로 판정하고, '
              '잠금이 풀린 뒤에는 열 때마다 다시 계산돼요. '
              '계좌를 초기화하면 새 계좌 기준으로 다시 4주를 채워야 해요.',
              style: TextStyle(
                fontSize: 12.5,
                height: 1.6,
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('확인했어요'),
        ),
      ],
    );
  }
}

/// 수익률 리더보드 — 웹 LeaderboardModal. 상위 10위 + 내 순위 주변만 표시.
class _LeaderboardDialog extends StatefulWidget {
  const _LeaderboardDialog({required this.reports});

  final ReportApi reports;

  @override
  State<_LeaderboardDialog> createState() => _LeaderboardDialogState();
}

class _LeaderboardDialogState extends State<_LeaderboardDialog> {
  late final Future<Leaderboard> _future = widget.reports.getLeaderboard();

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return AlertDialog(
      title: const Text('리더보드'),
      contentPadding: const EdgeInsets.fromLTRB(0, 12, 0, 0),
      content: SizedBox(
        width: double.maxFinite,
        child: FutureBuilder<Leaderboard>(
          future: _future,
          builder: (context, snap) {
            final subtitle = switch (snap.data) {
              final b? when b.asOf != null =>
                '${_dateFormat.format(b.asOf!)} 기준 · 수익률 순 · '
                    '참가자 ${formatNumber(b.participants)}명 · '
                    '상위 10위 + 내 순위 주변만 표시',
              _ => '수익률 순 · 상위 10위 + 내 순위 주변만 표시',
            };
            return Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 24),
                  child: Text(
                    subtitle,
                    style: TextStyle(
                      fontSize: 12.5,
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
                ),
                const SizedBox(height: 10),
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 24,
                    vertical: 8,
                  ),
                  color: theme.scaffoldBackgroundColor,
                  child: Row(
                    children: [
                      SizedBox(
                        width: 52,
                        child: _th('등수', theme),
                      ),
                      Expanded(child: _th('닉네임', theme)),
                      SizedBox(
                        width: 84,
                        child: Align(
                          alignment: Alignment.centerRight,
                          child: _th('수익률', theme),
                        ),
                      ),
                    ],
                  ),
                ),
                Flexible(child: _buildBody(context, snap)),
                _buildFooter(context, snap.data?.me),
              ],
            );
          },
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('닫기'),
        ),
      ],
    );
  }

  Widget _th(String text, ThemeData theme) => Text(
    text,
    style: TextStyle(
      fontSize: 12,
      fontWeight: FontWeight.w700,
      color: theme.colorScheme.onSurfaceVariant,
    ),
  );

  Widget _buildBody(BuildContext context, AsyncSnapshot<Leaderboard> snap) {
    final theme = Theme.of(context);
    if (snap.connectionState != ConnectionState.done) {
      return const Padding(
        padding: EdgeInsets.symmetric(vertical: 40),
        child: Center(child: Text('불러오는 중…')),
      );
    }
    final board = snap.data;
    if (snap.hasError || board == null) {
      return const Padding(
        padding: EdgeInsets.symmetric(vertical: 40),
        child: Center(child: Text('리더보드를 불러오지 못했어요.')),
      );
    }
    if (board.participants == 0) {
      return const Padding(
        padding: EdgeInsets.symmetric(vertical: 40),
        child: Center(child: Text('아직 집계된 순위가 없어요. 다음 배치를 기다려주세요.')),
      );
    }
    // top과 me.neighbors가 겹칠 수 있어 rank 기준으로 중복을 제거하고,
    // 두 그룹 사이에 끊긴 구간이 있으면 점선으로 표시한다.
    final topRanks = board.top.map((e) => e.rank).toSet();
    final neighbors = board.me?.neighbors
            .where((e) => !topRanks.contains(e.rank))
            .toList() ??
        const <LeaderboardEntry>[];
    final hasGap = neighbors.isNotEmpty &&
        board.top.isNotEmpty &&
        neighbors.first.rank - board.top.last.rank > 1;

    return SingleChildScrollView(
      child: Column(
        children: [
          for (final row in board.top)
            _LeaderboardRow(
              row: row,
              isMe: board.me?.rank == row.rank,
              theme: theme,
            ),
          if (hasGap)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 24),
              child: Divider(
                color: theme.colorScheme.outlineVariant,
              ),
            ),
          for (final row in neighbors)
            _LeaderboardRow(
              row: row,
              isMe: board.me?.rank == row.rank,
              theme: theme,
            ),
        ],
      ),
    );
  }

  Widget _buildFooter(BuildContext context, LeaderboardMe? me) {
    final theme = Theme.of(context);
    if (me == null) return const SizedBox.shrink();
    return Container(
      padding: const EdgeInsets.fromLTRB(24, 12, 24, 4),
      decoration: BoxDecoration(
        border: Border(
          top: BorderSide(color: theme.colorScheme.outlineVariant),
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            '내 순위 ${me.rank}위'
            '${me.topPercent != null ? ' · 상위 ${me.topPercent}%' : ''}',
            style: TextStyle(
              fontSize: 12.5,
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ),
          if (me.typeRank != null)
            Text.rich(
              TextSpan(
                children: [
                  TextSpan(
                    text: '${me.typeLabel}',
                    style: TextStyle(
                      fontWeight: FontWeight.w700,
                      color: theme.colorScheme.primary,
                    ),
                  ),
                  TextSpan(
                    text:
                        '(${me.typeCode}) 유형 안에서 '
                        '${me.typeRank}위/${formatNumber(me.typeParticipants)}명'
                        '${me.typePercent != null ? ' · 상위 ${me.typePercent}%' : ''}',
                  ),
                ],
              ),
              style: TextStyle(
                fontSize: 12.5,
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
          Text(
            '닉네임은 가운데 글자가 가려진 채로 공개돼요',
            style: TextStyle(
              fontSize: 12,
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ),
        ],
      ),
    );
  }
}

class _LeaderboardRow extends StatelessWidget {
  const _LeaderboardRow({
    required this.row,
    required this.isMe,
    required this.theme,
  });

  final LeaderboardEntry row;
  final bool isMe;
  final ThemeData theme;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
      decoration: BoxDecoration(
        color: isMe ? theme.scaffoldBackgroundColor : null,
        border: Border(
          top: BorderSide(color: theme.colorScheme.outlineVariant),
        ),
      ),
      child: Row(
        children: [
          SizedBox(
            width: 52,
            child: Text(
              '${row.rank}위',
              style: TextStyle(
                fontSize: 15,
                fontWeight: FontWeight.w800,
                color: row.rank <= 3
                    ? theme.colorScheme.primary
                    : theme.colorScheme.onSurfaceVariant,
              ),
            ),
          ),
          Expanded(
            child: Row(
              children: [
                Flexible(
                  child: Text(
                    row.nickname,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
                if (isMe) ...[
                  const SizedBox(width: 6),
                  Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 8,
                      vertical: 2,
                    ),
                    decoration: BoxDecoration(
                      color: theme.colorScheme.primary,
                      borderRadius: BorderRadius.circular(999),
                    ),
                    child: const Text(
                      '나',
                      style: TextStyle(
                        fontSize: 11,
                        fontWeight: FontWeight.w700,
                        color: Colors.white,
                      ),
                    ),
                  ),
                ],
              ],
            ),
          ),
          SizedBox(
            width: 84,
            child: Text(
              formatRate(row.returnRate),
              textAlign: TextAlign.right,
              style: TextStyle(
                fontSize: 15,
                fontWeight: FontWeight.w700,
                color: changeColor(row.returnRate, theme.colorScheme),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// 유형별 평균 수익률 비교 — 웹 TypeComparisonModal.
class _TypeComparisonDialog extends StatefulWidget {
  const _TypeComparisonDialog({required this.reports, this.myTypeCode});

  final ReportApi reports;
  final String? myTypeCode;

  @override
  State<_TypeComparisonDialog> createState() => _TypeComparisonDialogState();
}

class _TypeComparisonDialogState extends State<_TypeComparisonDialog> {
  late final Future<LeaderboardTypes> _future =
      widget.reports.getLeaderboardTypes();

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return AlertDialog(
      title: const Text('유형별 비교'),
      contentPadding: const EdgeInsets.fromLTRB(0, 12, 0, 0),
      content: SizedBox(
        width: double.maxFinite,
        child: FutureBuilder<LeaderboardTypes>(
          future: _future,
          builder: (context, snap) {
            final data = snap.data;
            // 응답은 정렬을 보장하지 않으니 평균 수익률 내림차순으로 다시 정렬한다.
            final sorted = [...(data?.types ?? const <LeaderboardTypeEntry>[])]
              ..sort((a, b) => (double.tryParse(b.avgReturnRate ?? '') ?? 0)
                  .compareTo(double.tryParse(a.avgReturnRate ?? '') ?? 0));
            return Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 24),
                  child: Text(
                    data?.asOf != null
                        ? '${_dateFormat.format(data!.asOf!)} 기준 · 유형별 평균 수익률'
                        : '유형별 평균 수익률',
                    style: TextStyle(
                      fontSize: 12.5,
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
                ),
                const SizedBox(height: 10),
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 24,
                    vertical: 8,
                  ),
                  color: theme.scaffoldBackgroundColor,
                  child: Row(
                    children: [
                      SizedBox(
                        width: 36,
                        child: _th('순위', theme),
                      ),
                      Expanded(child: _th('유형', theme)),
                      SizedBox(
                        width: 56,
                        child: Align(
                          alignment: Alignment.centerRight,
                          child: _th('인원', theme),
                        ),
                      ),
                      SizedBox(
                        width: 84,
                        child: Align(
                          alignment: Alignment.centerRight,
                          child: _th('평균 수익률', theme),
                        ),
                      ),
                    ],
                  ),
                ),
                Flexible(child: _buildBody(context, snap, sorted)),
                Container(
                  width: double.infinity,
                  padding: const EdgeInsets.fromLTRB(24, 12, 24, 4),
                  decoration: BoxDecoration(
                    border: Border(
                      top: BorderSide(
                        color: theme.colorScheme.outlineVariant,
                      ),
                    ),
                  ),
                  child: Text(
                    '미분류(보유 종목 2개 미만) 계좌는 유형 자체가 없어 비교에서 빠져요',
                    style: TextStyle(
                      fontSize: 12.5,
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
                ),
              ],
            );
          },
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('닫기'),
        ),
      ],
    );
  }

  Widget _th(String text, ThemeData theme) => Text(
    text,
    style: TextStyle(
      fontSize: 12,
      fontWeight: FontWeight.w700,
      color: theme.colorScheme.onSurfaceVariant,
    ),
  );

  Widget _buildBody(
    BuildContext context,
    AsyncSnapshot<LeaderboardTypes> snap,
    List<LeaderboardTypeEntry> sorted,
  ) {
    if (snap.connectionState != ConnectionState.done) {
      return const Padding(
        padding: EdgeInsets.symmetric(vertical: 40),
        child: Center(child: Text('불러오는 중…')),
      );
    }
    if (snap.hasError) {
      return const Padding(
        padding: EdgeInsets.symmetric(vertical: 40),
        child: Center(child: Text('유형별 비교를 불러오지 못했어요.')),
      );
    }
    if (sorted.isEmpty) {
      return const Padding(
        padding: EdgeInsets.symmetric(vertical: 40),
        child: Center(child: Text('아직 집계된 유형이 없어요. 다음 배치를 기다려주세요.')),
      );
    }
    return SingleChildScrollView(
      child: Column(
        children: [
          for (final (i, row) in sorted.indexed)
            _TypeComparisonRow(
              rank: i + 1,
              row: row,
              isMyType: row.typeCode == widget.myTypeCode,
            ),
        ],
      ),
    );
  }
}

class _TypeComparisonRow extends StatelessWidget {
  const _TypeComparisonRow({
    required this.rank,
    required this.row,
    required this.isMyType,
  });

  final int rank;
  final LeaderboardTypeEntry row;
  final bool isMyType;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final personaType = kPersonalityTypes[row.typeCode];
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
      decoration: BoxDecoration(
        color: isMyType ? theme.scaffoldBackgroundColor : null,
        border: Border(
          top: BorderSide(color: theme.colorScheme.outlineVariant),
        ),
      ),
      child: Row(
        children: [
          SizedBox(
            width: 36,
            child: Text(
              '$rank',
              style: TextStyle(
                fontSize: 15,
                fontWeight: FontWeight.w800,
                color: rank <= 3
                    ? theme.colorScheme.primary
                    : theme.colorScheme.onSurfaceVariant,
              ),
            ),
          ),
          Expanded(
            child: Row(
              children: [
                Flexible(
                  child: Text(
                    personaType?.nickname ?? row.typeLabel ?? row.typeCode,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontSize: 14.5,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
                const SizedBox(width: 6),
                Text(
                  row.typeCode,
                  style: TextStyle(
                    fontFamily: 'monospace',
                    fontSize: 11,
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
                if (isMyType) ...[
                  const SizedBox(width: 6),
                  Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 8,
                      vertical: 2,
                    ),
                    decoration: BoxDecoration(
                      color: theme.colorScheme.primary,
                      borderRadius: BorderRadius.circular(999),
                    ),
                    child: const Text(
                      '내 유형',
                      style: TextStyle(
                        fontSize: 11,
                        fontWeight: FontWeight.w700,
                        color: Colors.white,
                      ),
                    ),
                  ),
                ],
              ],
            ),
          ),
          SizedBox(
            width: 56,
            child: Text(
              '${formatNumber(row.count)}명',
              textAlign: TextAlign.right,
              style: TextStyle(
                fontSize: 13,
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
          ),
          SizedBox(
            width: 84,
            child: Text(
              formatRate(row.avgReturnRate),
              textAlign: TextAlign.right,
              style: TextStyle(
                fontSize: 15,
                fontWeight: FontWeight.w700,
                color: changeColor(row.avgReturnRate, theme.colorScheme),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// 유형 이미지 — 웹 `personaType.image` 카드. 탭하면 확대 다이얼로그를 연다.
/// 이미지가 없거나 로드에 실패하면 기존 코드 텍스트 카드로 폴백한다.
class _TypeImage extends StatelessWidget {
  const _TypeImage({
    required this.typeCode,
    required this.personaType,
    required this.size,
  });

  final String typeCode;
  final PersonalityTypeInfo? personaType;
  final double size;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final image = personaType?.image;
    final fallback = Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        color: theme.colorScheme.primary.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Center(
        child: Text(
          typeCode,
          style: TextStyle(
            fontFamily: 'monospace',
            fontSize: size * 0.25,
            fontWeight: FontWeight.w800,
            letterSpacing: 2,
            color: theme.colorScheme.primary,
          ),
        ),
      ),
    );
    if (image == null) return fallback;
    return GestureDetector(
      onTap: () => showDialog<void>(
        context: context,
        builder: (context) =>
            _TypeImageDialog(typeCode: typeCode, personaType: personaType),
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(20),
        child: Image.asset(
          image,
          width: size,
          height: size,
          fit: BoxFit.cover,
          errorBuilder: (context, error, stackTrace) => fallback,
        ),
      ),
    );
  }
}

/// 유형 이미지 확대 — 웹 `PersonalityImageModal` 대응.
class _TypeImageDialog extends StatelessWidget {
  const _TypeImageDialog({required this.typeCode, required this.personaType});

  final String typeCode;
  final PersonalityTypeInfo? personaType;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final image = personaType?.image;
    return Dialog(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24)),
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        typeCode,
                        style: const TextStyle(
                          fontSize: 18,
                          fontWeight: FontWeight.w800,
                          letterSpacing: 0.8,
                        ),
                      ),
                      const SizedBox(height: 2),
                      Text(
                        personaType?.nickname ?? '',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: 13,
                          fontWeight: FontWeight.w700,
                          color: theme.colorScheme.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                ),
                TextButton(
                  onPressed: () => Navigator.of(context).pop(),
                  child: const Text('닫기'),
                ),
              ],
            ),
            const SizedBox(height: 12),
            if (image != null)
              ClipRRect(
                borderRadius: BorderRadius.circular(18),
                child: Image.asset(image, fit: BoxFit.cover),
              ),
          ],
        ),
      ),
    );
  }
}
