import 'dart:async';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/account_api.dart';
import '../../core/api/api_error.dart';
import '../../core/api/order_api.dart';
import '../../core/api/stock_api.dart';
import '../../core/auth/auth_session.dart';
import '../../core/models/candle.dart';
import '../../core/models/market_country.dart';
import '../../core/models/order_book.dart';
import '../../core/models/stock_detail.dart';
import '../../core/polling.dart';
import '../../formatters.dart';
import '../../widgets/app_widgets.dart';
import 'financials_section.dart';
import 'lw_candle_chart.dart';
import 'trade_panel.dart';

/// 종목 상세. 가격·차트·호가·기본정보를 보여주고, 거래하기에서
/// 로그인 여부와 거래 가능 상태를 확인한 뒤 주문 패널을 연다.
class StockDetailScreen extends StatefulWidget {
  const StockDetailScreen({
    super.key,
    required this.symbol,
    required this.marketCountry,
    required this.stocks,
    required this.session,
    required this.orders,
    required this.account,
    this.stockId,
    this.stockLikeId,
  });

  final String symbol;
  final MarketCountry marketCountry;
  final StockApi stocks;
  final AuthSession session;
  final OrderApi orders;
  final AccountApi account;

  /// 랭킹에서 넘어올 때만 안다. 검색·상세 응답에는 stockId가 없어서
  /// 없으면 찜 버튼을 숨긴다.
  final int? stockId;
  final int? stockLikeId;

  @override
  State<StockDetailScreen> createState() => _StockDetailScreenState();
}

/// 캔들 봉 단위 — 웹 `candle-query.ts`의 `CandleUnit`과 같은 표.
/// 백엔드 `CandleQueryPolicy`가 허용하는 interval 값과 짝지어 둔다.
enum _CandleUnit {
  minute1('1분봉', '1m'),
  minute5('5분봉', '5m'),
  minute10('10분봉', '10m'),
  day('일봉', '1d'),
  week('1주봉', '1w');

  const _CandleUnit(this.label, this.interval);
  final String label;
  final String interval;

  /// 분봉 계열이면 장중 1분마다 캔들을 다시 가져온다(웹과 같은 규칙).
  bool get isIntraday => interval.endsWith('m');
}

/// 조회 기간 — 웹 `CandlePeriod`와 같은 표. range가 백엔드 쿼리값이다.
enum _CandlePeriod {
  day1('1일', '1D'),
  week1('1주일', '1W'),
  month1('1개월', '1M'),
  month6('6개월', '6M'),
  year1('1년', '1Y');

  const _CandlePeriod(this.label, this.range);
  final String label;
  final String range;
}

/// 봉 단위마다 고를 수 있는 기간 — 백엔드 허용 조합 그대로. 1개뿐이면
/// 기간 토글을 숨긴다(웹 `CANDLE_UNIT_PERIODS`와 동일).
const _unitPeriods = <_CandleUnit, List<_CandlePeriod>>{
  _CandleUnit.minute1: [_CandlePeriod.day1],
  _CandleUnit.minute5: [_CandlePeriod.day1, _CandlePeriod.week1],
  _CandleUnit.minute10: [_CandlePeriod.week1],
  _CandleUnit.day: [
    _CandlePeriod.month1,
    _CandlePeriod.month6,
    _CandlePeriod.year1,
  ],
  _CandleUnit.week: [_CandlePeriod.month6, _CandlePeriod.year1],
};

/// 봉 단위를 바꿀 때 이전 기간이 새 단위에 없을 수 있어 되돌아갈 기본 기간
/// (웹 `CANDLE_UNIT_DEFAULT_PERIOD`와 동일).
const _unitDefaultPeriod = <_CandleUnit, _CandlePeriod>{
  _CandleUnit.minute1: _CandlePeriod.day1,
  _CandleUnit.minute5: _CandlePeriod.day1,
  _CandleUnit.minute10: _CandlePeriod.week1,
  _CandleUnit.day: _CandlePeriod.month6,
  _CandleUnit.week: _CandlePeriod.month6,
};

class _StockDetailScreenState extends State<StockDetailScreen> {
  late Future<StockDetail> _detailFuture;
  StockDetail? _detail;
  Future<CandleSeries>? _candleFuture;
  CandleSeries? _candles;
  Future<OrderBook>? _bookFuture;
  OrderBook? _book;
  _CandleUnit _unit = _CandleUnit.day;
  _CandlePeriod _period = _CandlePeriod.month6;
  int? _stockLikeId;
  bool _likeBusy = false;

  bool _pollInFlight = false;
  late final PollingTimer _pricePoll;
  late final PollingTimer _bookPoll;
  late final PollingTimer _refreshPoll;

  /// 이 종목의 보유 수량. 매도 한도로 쓴다 — 없거나 조회 실패면 null.
  /// 서버 주문 트랜잭션이 최종 권위라 여기서는 미리 보여주는 용도다.
  num? _heldQuantity;

  @override
  void initState() {
    super.initState();
    _stockLikeId = widget.stockLikeId;
    _pricePoll = PollingTimer(
      interval: const Duration(seconds: 5),
      onTick: _pollDetail,
    )..start();
    // 가상 호가는 서버가 3초 주기로 갱신한다 — 같은 주기로 맞춘다(웹과 동일).
    _bookPoll = PollingTimer(
      interval: const Duration(seconds: 3),
      onTick: _pollBook,
    )..start();
    // 장 마감 뒤 재개장 전환도 잡아야 realtime 플래그가 다시 켜진다.
    _refreshPoll = PollingTimer(
      interval: const Duration(minutes: 1),
      onTick: _pollDetailForce,
    )..start();
    _loadDetail();
    _loadCandles();
    _loadOrderBook();
    _loadHolding();
  }

  @override
  void dispose() {
    _pricePoll.dispose();
    _bookPoll.dispose();
    _refreshPoll.dispose();
    super.dispose();
  }

  Future<void> _loadHolding() async {
    if (!widget.session.isAuthenticated) return;
    try {
      final holdings = await widget.account.getHoldings();
      if (!mounted) return;
      final held = holdings.items
          .where((h) => h.symbol == widget.symbol)
          .firstOrNull;
      setState(() {
        _heldQuantity = num.tryParse(held?.quantity ?? '') ?? 0;
      });
    } on ApiException {
      // 실패해도 막지 않는다 — 서버가 주문 시 다시 판정한다.
    }
  }

  void _loadDetail() {
    _detailFuture = widget.stocks
        .getDetail(symbol: widget.symbol, marketCountry: widget.marketCountry)
        .then((d) {
          _detail = d;
          return d;
        });
  }

  bool get _routeVisible => ModalRoute.of(context)?.isCurrent ?? true;

  /// 5초 시세 폴링 — realtime(정규장)일 때만. 장 마감이면 quote_snapshot이
  /// 갱신되지 않아 폴링해도 새 값이 없다. 실패해도 현재 데이터를 유지한다.
  Future<void> _pollDetail() async {
    if (_detail?.price?.realtime != true) return;
    await _pollDetailForce();
  }

  Future<void> _pollDetailForce() async {
    if (_pollInFlight || !_routeVisible) return;
    _pollInFlight = true;
    try {
      final detail = await widget.stocks.getDetail(
        symbol: widget.symbol,
        marketCountry: widget.marketCountry,
      );
      if (!mounted) return;
      _detail = detail;
      setState(() {
        _detailFuture = Future.value(detail);
        // 분봉 차트는 장중 1분 주기로 캔들도 함께 갱신한다(웹과 같은 규칙).
        if (_unit.isIntraday && detail.price?.realtime == true) {
          _loadCandles();
        }
      });
    } on ApiException {
      // 다음 주기에 재시도.
    } finally {
      _pollInFlight = false;
    }
  }

  void _loadCandles() {
    _candleFuture = widget.stocks
        .getCandles(
          symbol: widget.symbol,
          marketCountry: widget.marketCountry,
          interval: _unit.interval,
          range: _period.range,
        )
        .then((s) {
          if (mounted) setState(() => _candles = s);
          return s;
        });
  }

  void _loadOrderBook() {
    _bookFuture = widget.stocks
        .getOrderBook(
          symbol: widget.symbol,
          marketCountry: widget.marketCountry,
        )
        .then((b) {
          if (mounted) setState(() => _book = b);
          return b;
        });
  }

  /// 호가 폴링 — 장중(realtime)에만, 화면 깜빡임 없이 데이터만 조용히 바꾼다.
  /// 실패(장 마감 등)해도 현재 표시를 유지한다(웹 `OrderBookPanel`과 동일).
  Future<void> _pollBook() async {
    if (_detail?.price?.realtime != true || !_routeVisible) return;
    try {
      final book = await widget.stocks.getOrderBook(
        symbol: widget.symbol,
        marketCountry: widget.marketCountry,
      );
      if (!mounted) return;
      setState(() => _book = book);
    } on ApiException {
      // 다음 주기에 재시도.
    }
  }

  Future<void> _reload() async {
    final detail = widget.stocks.getDetail(
      symbol: widget.symbol,
      marketCountry: widget.marketCountry,
    );
    setState(() {
      _detailFuture = detail;
      _loadCandles();
      _loadOrderBook();
    });
    try {
      await detail;
    } on ApiException {
      // FutureBuilder가 오류 상태를 그린다.
    }
  }

  void _selectUnit(_CandleUnit unit) {
    if (unit == _unit) return;
    setState(() {
      _unit = unit;
      // 이전 기간이 새 단위에서 유효하지 않을 수 있어 그 단위의 기본 기간으로 돌린다.
      final periods = _unitPeriods[unit]!;
      if (!periods.contains(_period)) _period = _unitDefaultPeriod[unit]!;
      _loadCandles();
    });
  }

  void _selectPeriod(_CandlePeriod period) {
    if (period == _period) return;
    setState(() {
      _period = period;
      _loadCandles();
    });
  }

  /// 차트 크게보기 — 웹 `ChartExpandModal`처럼 같은 토글 상태를 공유한다.
  /// 모달에서 단위·기간을 바꾸면 닫은 뒤에도 유지된다.
  void _openChartExpand() {
    showDialog<void>(
      context: context,
      builder: (dialogContext) => StatefulBuilder(
        builder: (dialogContext, setLocal) {
          return Dialog.fullscreen(
            backgroundColor: Theme.of(dialogContext).scaffoldBackgroundColor,
            child: SafeArea(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(20, 12, 20, 20),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        // 웹 모달 헤더와 같이 종목명+심볼+시장을 보여준다.
                        Expanded(
                          child: Text.rich(
                            TextSpan(
                              children: [
                                TextSpan(text: _detail?.name ?? widget.symbol),
                                TextSpan(
                                  text: '  ${widget.symbol}',
                                  style: TextStyle(
                                    fontSize: 13,
                                    color: Theme.of(dialogContext)
                                        .colorScheme
                                        .onSurfaceVariant,
                                  ),
                                ),
                              ],
                            ),
                            style: Theme.of(dialogContext)
                                .textTheme
                                .titleLarge,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                        TextButton(
                          onPressed: () => Navigator.of(dialogContext).pop(),
                          child: const Text('닫기'),
                        ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    Expanded(
                      child: SingleChildScrollView(
                        child: _ChartSection(
                          unit: _unit,
                          period: _period,
                          candleFuture: _candleFuture,
                          candles: _candles,
                          onUnitSelect: (u) {
                            _selectUnit(u);
                            setLocal(() {});
                          },
                          onPeriodSelect: (p) {
                            _selectPeriod(p);
                            setLocal(() {});
                          },
                          onExpand: null,
                          onRetry: () {
                            setState(_loadCandles);
                            setLocal(() {});
                          },
                          chartHeight:
                              MediaQuery.sizeOf(dialogContext).height * 0.55,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          );
        },
      ),
    );
  }

  String get _selfPath =>
      '/stocks/${widget.symbol}?marketCountry=${widget.marketCountry.wireValue}';

  Future<void> _toggleLike() async {
    final stockId = widget.stockId;
    if (stockId == null || _likeBusy) return;
    if (!widget.session.isAuthenticated) {
      context.go('/login?from=${Uri.encodeComponent(_selfPath)}');
      return;
    }
    setState(() => _likeBusy = true);
    try {
      if (_stockLikeId != null) {
        await widget.stocks.unlike(stockLikeId: _stockLikeId!);
        if (mounted) setState(() => _stockLikeId = null);
      } else {
        final id = await widget.stocks.like(stockId: stockId);
        if (mounted) setState(() => _stockLikeId = id);
      }
    } on ApiException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(e.message)));
      }
    } finally {
      if (mounted) setState(() => _likeBusy = false);
    }
  }

  void _openTrade(StockDetail detail) {
    if (!widget.session.isAuthenticated) {
      context.go('/login?from=${Uri.encodeComponent(_selfPath)}');
      return;
    }
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (context) => TradePanel(
        detail: detail,
        session: widget.session,
        orders: widget.orders,
        heldQuantity: _heldQuantity,
        onOrderDone: _loadHolding,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: FutureBuilder<StockDetail>(
          future: _detailFuture,
          builder: (context, snapshot) =>
              Text(snapshot.data?.name ?? widget.symbol),
        ),
        actions: [
          if (widget.stockId != null)
            IconButton(
              onPressed: _likeBusy ? null : _toggleLike,
              icon: Icon(
                _stockLikeId != null ? Icons.favorite : Icons.favorite_border,
                color: _stockLikeId != null ? const Color(0xFFEF4444) : null,
              ),
              tooltip: '찜하기',
            ),
        ],
      ),
      body: FutureBuilder<StockDetail>(
        future: _detailFuture,
        builder: (context, snapshot) {
          if (snapshot.hasError) {
            return PageList(
              onRefresh: _reload,
              children: [
                Notice(
                  message: snapshot.error is ApiException
                      ? (snapshot.error! as ApiException).message
                      : '종목 정보를 불러오지 못했어요',
                  onRetry: _reload,
                ),
              ],
            );
          }
          final detail = snapshot.data;
          if (detail == null) {
            return const Center(child: CircularProgressIndicator());
          }
          return _DetailBody(
            detail: detail,
            stocks: widget.stocks,
            candleFuture: _candleFuture,
            candles: _candles,
            bookFuture: _bookFuture,
            unit: _unit,
            period: _period,
            onUnitSelect: _selectUnit,
            onPeriodSelect: _selectPeriod,
            onExpand: _openChartExpand,
            book: _book,
            onRefresh: _reload,
            onRetryCandles: () => setState(_loadCandles),
          );
        },
      ),
      bottomNavigationBar: FutureBuilder<StockDetail>(
        future: _detailFuture,
        builder: (context, snapshot) {
          final detail = snapshot.data;
          if (detail == null) return const SizedBox.shrink();
          return SafeArea(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 12),
              child: FilledButton.icon(
                onPressed: detail.tradable ? () => _openTrade(detail) : null,
                icon: const Icon(Icons.swap_horiz),
                label: Text(
                  detail.tradable
                      ? '거래하기'
                      : tradableReasonLabel(detail.tradableReason),
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}

/// 캔들차트 영역(봉 단위 + 기간 토글 + 차트) — 웹 `CandleChartSection`처럼
/// 기본 화면과 크게보기 모달이 같은 마크업을 공유한다.
class _ChartSection extends StatelessWidget {
  const _ChartSection({
    required this.unit,
    required this.period,
    required this.candleFuture,
    required this.candles,
    required this.onUnitSelect,
    required this.onPeriodSelect,
    required this.onRetry,
    this.onExpand,
    this.chartHeight = 260,
  });

  final _CandleUnit unit;
  final _CandlePeriod period;
  final Future<CandleSeries>? candleFuture;
  final CandleSeries? candles;
  final ValueChanged<_CandleUnit> onUnitSelect;
  final ValueChanged<_CandlePeriod> onPeriodSelect;
  final VoidCallback onRetry;

  /// 넘기지 않으면 크게보기 버튼을 숨긴다(모달 안에서는 불필요).
  final VoidCallback? onExpand;
  final double chartHeight;

  /// KST 기준 `MM.DD` — 백엔드 거래일 경계 정의와 맞춘다.
  static String _kstMd(DateTime t) {
    final kst = t.toUtc().add(const Duration(hours: 9));
    return '${kst.month.toString().padLeft(2, '0')}.'
        '${kst.day.toString().padLeft(2, '0')}';
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final periods = _unitPeriods[unit]!;
    // 고를 수 있는 기간이 하나뿐이면 기간 토글 자체가 무의미하므로 숨긴다.
    final hasPeriodChoice = periods.length > 1;
    // 일봉·1주봉은 마지막 봉 날짜(종가 기준)를, 분봉은 "최근 N봉"을 보여준다.
    final showsLastCandleDate =
        unit == _CandleUnit.day || unit == _CandleUnit.week;
    final lastAt = candles?.items.lastOrNull?.at;
    final lastLabel = lastAt == null ? null : _kstMd(lastAt);
    final summary = hasPeriodChoice
        ? '${unit.label} · ${period.label}'
            '${showsLastCandleDate && lastLabel != null ? ' · $lastLabel 종가까지' : ''}'
        : '${unit.label} · 최근 ${candles?.items.length ?? 0}봉';

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        PillTabs<_CandleUnit>(
          options: [
            for (final u in _CandleUnit.values) (value: u, label: u.label),
          ],
          value: unit,
          onChanged: onUnitSelect,
        ),
        const SizedBox(height: 10),
        // 웹은 한 줄 flex-wrap이라 좁으면 항목이 아래로 감긴다 — Wrap으로 맞춘다.
        Wrap(
          spacing: 10,
          runSpacing: 8,
          crossAxisAlignment: WrapCrossAlignment.center,
          alignment: WrapAlignment.spaceBetween,
          children: [
            if (hasPeriodChoice)
              SizedBox(
                width: 190,
                child: PillTabs<_CandlePeriod>(
                  options: [
                    for (final p in periods) (value: p, label: p.label),
                  ],
                  value: period,
                  onChanged: onPeriodSelect,
                ),
              ),
            // 라벨+크게보기는 한 덩어리로 묶어 웹의 ml-auto 우측 정렬과 맞춘다.
            Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Flexible(
                  child: Text(
                    summary,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 12,
                      color: scheme.onSurfaceVariant,
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                if (onExpand != null)
                  // 웹의 "⤢ 차트 크게보기" pill 버튼과 같은 표기.
                  GestureDetector(
                onTap: onExpand,
                  child: Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 10,
                      vertical: 6,
                    ),
                    decoration: BoxDecoration(
                      border: Border.all(
                        color: scheme.primary.withValues(alpha: 0.25),
                      ),
                      borderRadius: BorderRadius.circular(999),
                    ),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(
                          Icons.open_in_full,
                          size: 12,
                          color: scheme.primary,
                        ),
                        const SizedBox(width: 4),
                        Text(
                          '차트 크게보기',
                          style: TextStyle(
                            fontSize: 12,
                            fontWeight: FontWeight.w700,
                            color: scheme.primary,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ],
        ),
        const SizedBox(height: 12),
        FutureBuilder<CandleSeries>(
          future: candleFuture,
          builder: (context, snapshot) {
            if (snapshot.hasError) {
              return Notice(
                message: snapshot.error is ApiException
                    ? (snapshot.error! as ApiException).message
                    : '차트를 불러오지 못했어요',
                onRetry: onRetry,
              );
            }
            final series = snapshot.data;
            if (series == null) {
              return SizedBox(
                height: chartHeight,
                child: const Center(child: CircularProgressIndicator()),
              );
            }
            if (series.items.length < 2) {
              return SizedBox(
                height: chartHeight,
                child: Center(
                  child: Text(
                    '차트 데이터가 아직 없어요',
                    style: TextStyle(
                      fontSize: 13,
                      color: scheme.onSurfaceVariant,
                    ),
                  ),
                ),
              );
            }
            return LwCandleChart(items: series.items, height: chartHeight);
          },
        ),
      ],
    );
  }
}

class _DetailBody extends StatelessWidget {
  const _DetailBody({
    required this.detail,
    required this.stocks,
    required this.candleFuture,
    required this.candles,
    required this.bookFuture,
    required this.unit,
    required this.period,
    required this.onUnitSelect,
    required this.onPeriodSelect,
    required this.onExpand,
    required this.book,
    required this.onRefresh,
    required this.onRetryCandles,
  });

  final StockDetail detail;
  final StockApi stocks;
  final Future<CandleSeries>? candleFuture;
  final CandleSeries? candles;
  final Future<OrderBook>? bookFuture;
  final _CandleUnit unit;
  final _CandlePeriod period;
  final ValueChanged<_CandleUnit> onUnitSelect;
  final ValueChanged<_CandlePeriod> onPeriodSelect;
  final VoidCallback onExpand;
  final OrderBook? book;
  final Future<void> Function() onRefresh;
  final VoidCallback onRetryCandles;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final price = detail.price;

    return PageList(
      onRefresh: onRefresh,
      children: [
        // 종목 헤더
        Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(detail.name, style: theme.textTheme.headlineMedium),
            const SizedBox(height: 4),
            Text(
              [
                detail.symbol,
                if (detail.market != null) detail.market!,
                detail.marketCountry.wireValue,
                detail.category.wireValue,
              ].join(' · '),
              style: TextStyle(color: scheme.onSurfaceVariant),
            ),
            if (detail.englishName != null)
              Text(
                detail.englishName!,
                style: TextStyle(fontSize: 12, color: scheme.onSurfaceVariant),
              ),
          ],
        ),
        if (detail.warnings.isNotEmpty || detail.warningsUnavailable) ...[
          const SizedBox(height: 8),
          Wrap(
            spacing: 6,
            runSpacing: 6,
            children: [
              for (final w in detail.warnings)
                _Badge(label: w.label, color: scheme.error),
              if (detail.warningsUnavailable)
                _Badge(label: '유의사항 확인 중', color: scheme.onSurfaceVariant),
            ],
          ),
        ],
        const SizedBox(height: 16),

        // 현재가
        AppCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  Text(
                    formatMoney(price?.lastPrice, detail.currency),
                    style: theme.textTheme.headlineMedium,
                  ),
                  const SizedBox(width: 8),
                  Padding(
                    padding: const EdgeInsets.only(bottom: 4),
                    child: Text(
                      price?.realtime == true ? '실시간' : '종가',
                      style: TextStyle(
                        fontSize: 12,
                        color: scheme.onSurfaceVariant,
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 4),
              Text(
                '${formatMoney(price?.changeAmount, detail.currency)} '
                '(${formatRate(price?.changeRate)})',
                style: TextStyle(
                  fontWeight: FontWeight.w700,
                  color: changeColor(price?.changeRate, scheme),
                ),
              ),
              const SizedBox(height: 8),
              Text(
                '전일 ${formatMoney(price?.prevClose, detail.currency)} · '
                '상한 ${formatMoney(price?.upperLimit, detail.currency)} · '
                '하한 ${formatMoney(price?.lowerLimit, detail.currency)}',
                style: TextStyle(fontSize: 12, color: scheme.onSurfaceVariant),
              ),
            ],
          ),
        ),
        const SizedBox(height: 16),

        // 차트 — 웹과 같은 봉 단위/기간 2단 토글 + TradingView lightweight-charts.
        AppCard(
          child: _ChartSection(
            unit: unit,
            period: period,
            candleFuture: candleFuture,
            candles: candles,
            onUnitSelect: onUnitSelect,
            onPeriodSelect: onPeriodSelect,
            onExpand: onExpand,
            onRetry: onRetryCandles,
          ),
        ),
        const SizedBox(height: 16),

        // 호가 — 웹 OrderBookPanel과 같은 세로 호가창(뎁스 바 + 가상 호가 뱃지).
        AppCard(
          child: FutureBuilder<OrderBook>(
            future: bookFuture,
            builder: (context, snapshot) {
              final b = book ?? snapshot.data;
              return Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Text('호가', style: theme.textTheme.titleLarge),
                      if (b?.virtual == true) ...[
                        const SizedBox(width: 6),
                        Tooltip(
                          message: b?.description ?? '가상 호가',
                          child: Container(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 6,
                              vertical: 2,
                            ),
                            decoration: BoxDecoration(
                              color: scheme.primary.withValues(alpha: 0.08),
                              borderRadius: BorderRadius.circular(6),
                            ),
                            child: Text(
                              '가상 호가',
                              style: TextStyle(
                                fontSize: 10.5,
                                fontWeight: FontWeight.w700,
                                color: scheme.primary,
                              ),
                            ),
                          ),
                        ),
                      ],
                    ],
                  ),
                  const SizedBox(height: 10),
                  if (b != null)
                    _OrderBookView(book: b)
                  else if (snapshot.hasError)
                    // 503(장 마감 등)이 흔한 영역이라 재시도 버튼 없이 안내만 띄운다.
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 40),
                      child: Center(
                        child: Text(
                          '현재 호가를 조회할 수 없어요 (장 마감 등)',
                          style: TextStyle(
                            fontSize: 13,
                            color: scheme.onSurfaceVariant,
                          ),
                        ),
                      ),
                    )
                  else
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 40),
                      child: Center(
                        child: Text(
                          '호가 불러오는 중…',
                          style: TextStyle(
                            fontSize: 13,
                            color: scheme.onSurfaceVariant,
                          ),
                        ),
                      ),
                    ),
                ],
              );
            },
          ),
        ),
        const SizedBox(height: 16),

        // 재무제표 (미지원 종목은 위젯이 스스로 숨는다)
        FinancialsSection(
          symbol: detail.symbol,
          marketCountry: detail.marketCountry,
          stocks: stocks,
        ),
        const SizedBox(height: 16),

        // 기본 정보
        AppCard(
          child: Column(
            children: [
              _InfoRow('시가총액', detail.info?.marketCap),
              _InfoRow('상장주식수', detail.info?.sharesOutstanding),
              _InfoRow('상장일', detail.info?.listDate),
              _InfoRow('통화', detail.currency),
              _InfoRow('ISIN', detail.isinCode),
            ],
          ),
        ),
      ],
    );
  }
}

/// 웹 `OrderBookPanel`의 세로 호가창 — 매도 위→아래(높은 가격 먼저), 기준가,
/// 매수 순서. 각 행은 잔량 비율만큼 배경이 채워지는 뎁스 바를 가진다.
class _OrderBookView extends StatelessWidget {
  const _OrderBookView({required this.book});

  final OrderBook book;

  // 웹 --up/--down과 같은 값: 매수=빨강(up), 매도=파랑(down).
  static const _up = Color(0xFFEF4444);
  static const _down = Color(0xFF3B82F6);

  /// 웹은 KRW 가격을 순수 숫자(formatNumber)로, USD는 `$xx.xx`(formatUsd)로 표시한다.
  static String _price(String raw, String? currency) {
    if (currency == 'USD') {
      final v = double.tryParse(raw);
      return v == null ? '-' : '\$${v.toStringAsFixed(2)}';
    }
    return formatNumber(raw);
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    var maxQuantity = 1.0;
    for (final l in [...book.asks, ...book.bids]) {
      final q = double.tryParse(l.quantity) ?? 0;
      if (q > maxQuantity) maxQuantity = q;
    }
    return Column(
      children: [
        if (book.asks.isEmpty)
          _emptySide(context, '매도 호가 없음 · 매수 체결 대기'),
        // 매도 호가는 높은 가격이 위에 오도록 역순으로 그린다.
        for (final level in book.asks.reversed)
          _levelRow(context, level, isAsk: true, maxQuantity: maxQuantity),
        Container(
          margin: const EdgeInsets.symmetric(vertical: 4),
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
          decoration: BoxDecoration(
            color: scheme.surfaceContainerHighest,
            borderRadius: BorderRadius.circular(6),
          ),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                '기준가',
                style: TextStyle(
                  fontSize: 12.5,
                  fontWeight: FontWeight.w700,
                  color: scheme.onSurfaceVariant,
                ),
              ),
              Text(
                _price(book.basePrice ?? '', book.currency),
                style: const TextStyle(
                  fontSize: 12.5,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ],
          ),
        ),
        if (book.bids.isEmpty)
          _emptySide(context, '매수 호가 없음 · 매도 체결 대기'),
        for (final level in book.bids)
          _levelRow(context, level, isAsk: false, maxQuantity: maxQuantity),
      ],
    );
  }

  Widget _emptySide(BuildContext context, String message) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 16),
    child: Center(
      child: Text(
        message,
        style: TextStyle(
          fontSize: 12,
          color: Theme.of(context).colorScheme.onSurfaceVariant,
        ),
      ),
    ),
  );

  Widget _levelRow(
    BuildContext context,
    OrderBookLevel level, {
    required bool isAsk,
    required double maxQuantity,
  }) {
    final scheme = Theme.of(context).colorScheme;
    final color = isAsk ? _down : _up;
    final qty = double.tryParse(level.quantity) ?? 0;
    final pct = (qty / maxQuantity).clamp(0.0, 1.0);
    return Container(
      height: 28,
      margin: const EdgeInsets.only(bottom: 2),
      clipBehavior: Clip.antiAlias,
      decoration: BoxDecoration(borderRadius: BorderRadius.circular(6)),
      child: Stack(
        children: [
          // 잔량 비율만큼 왼쪽부터 채워지는 뎁스 바(웹 --upBg/--downBg에 해당).
          Align(
            alignment: Alignment.centerLeft,
            child: FractionallySizedBox(
              widthFactor: pct,
              heightFactor: 1,
              child: ColoredBox(color: color.withValues(alpha: 0.10)),
            ),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 10),
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    _price(level.price, book.currency),
                    textAlign: TextAlign.right,
                    style: TextStyle(
                      fontSize: 12.5,
                      fontWeight: FontWeight.w600,
                      color: color,
                    ),
                  ),
                ),
                Expanded(
                  child: Text(
                    formatNumber(level.quantity),
                    textAlign: TextAlign.right,
                    style: TextStyle(
                      fontSize: 12.5,
                      color: scheme.onSurfaceVariant,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _Badge extends StatelessWidget {
  const _Badge({required this.label, required this.color});

  final String label;
  final Color color;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
    decoration: BoxDecoration(
      color: color.withValues(alpha: 0.1),
      borderRadius: BorderRadius.circular(8),
      border: Border.all(color: color.withValues(alpha: 0.4)),
    ),
    child: Text(
      label,
      style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: color),
    ),
  );
}

class _InfoRow extends StatelessWidget {
  const _InfoRow(this.label, this.value);

  final String label;
  final String? value;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label, style: theme.textTheme.bodyMedium),
          Text(
            value ?? '-',
            style: theme.textTheme.bodyMedium?.copyWith(
              fontWeight: FontWeight.w600,
            ),
          ),
        ],
      ),
    );
  }
}
