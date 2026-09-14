import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_error.dart';
import '../../core/api/order_api.dart';
import '../../core/api/stock_api.dart';
import '../../core/auth/auth_session.dart';
import '../../core/models/candle.dart';
import '../../core/models/market_country.dart';
import '../../core/models/order_book.dart';
import '../../core/models/stock_detail.dart';
import '../../formatters.dart';
import '../../widgets/app_widgets.dart';
import 'candle_chart.dart';
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
    this.stockId,
    this.stockLikeId,
  });

  final String symbol;
  final MarketCountry marketCountry;
  final StockApi stocks;
  final AuthSession session;
  final OrderApi orders;

  /// 랭킹에서 넘어올 때만 안다. 검색·상세 응답에는 stockId가 없어서
  /// 없으면 찜 버튼을 숨긴다.
  final int? stockId;
  final int? stockLikeId;

  @override
  State<StockDetailScreen> createState() => _StockDetailScreenState();
}

/// 백엔드가 지원하는 interval × range 조합만 노출한다.
const _ranges = <(String label, String interval, String range)>[
  ('1일', '1m', '1D'),
  ('1개월', '1d', '1M'),
  ('6개월', '1d', '6M'),
  ('1년', '1d', '1Y'),
];

class _StockDetailScreenState extends State<StockDetailScreen> {
  late Future<StockDetail> _detailFuture;
  Future<CandleSeries>? _candleFuture;
  Future<OrderBook>? _bookFuture;
  int _rangeIndex = 0;
  int? _stockLikeId;
  bool _likeBusy = false;

  @override
  void initState() {
    super.initState();
    _stockLikeId = widget.stockLikeId;
    _loadDetail();
    _loadCandles();
    _loadOrderBook();
  }

  void _loadDetail() {
    _detailFuture = widget.stocks.getDetail(
      symbol: widget.symbol,
      marketCountry: widget.marketCountry,
    );
  }

  void _loadCandles() {
    _candleFuture = widget.stocks.getCandles(
      symbol: widget.symbol,
      marketCountry: widget.marketCountry,
      interval: _ranges[_rangeIndex].$2,
      range: _ranges[_rangeIndex].$3,
    );
  }

  void _loadOrderBook() {
    _bookFuture = widget.stocks.getOrderBook(
      symbol: widget.symbol,
      marketCountry: widget.marketCountry,
    );
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

  void _selectRange(int index) {
    if (index == _rangeIndex) return;
    setState(() {
      _rangeIndex = index;
      _loadCandles();
    });
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
            candleFuture: _candleFuture,
            bookFuture: _bookFuture,
            rangeIndex: _rangeIndex,
            onRangeSelect: _selectRange,
            onRefresh: _reload,
            onRetryCandles: () => setState(_loadCandles),
            onRetryBook: () => setState(_loadOrderBook),
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

class _DetailBody extends StatelessWidget {
  const _DetailBody({
    required this.detail,
    required this.candleFuture,
    required this.bookFuture,
    required this.rangeIndex,
    required this.onRangeSelect,
    required this.onRefresh,
    required this.onRetryCandles,
    required this.onRetryBook,
  });

  final StockDetail detail;
  final Future<CandleSeries>? candleFuture;
  final Future<OrderBook>? bookFuture;
  final int rangeIndex;
  final ValueChanged<int> onRangeSelect;
  final Future<void> Function() onRefresh;
  final VoidCallback onRetryCandles;
  final VoidCallback onRetryBook;

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

        // 차트
        AppCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              SegmentedButton<int>(
                segments: [
                  for (var i = 0; i < _ranges.length; i++)
                    ButtonSegment(value: i, label: Text(_ranges[i].$1)),
                ],
                selected: {rangeIndex},
                onSelectionChanged: (set) => onRangeSelect(set.first),
                showSelectedIcon: false,
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
                      onRetry: onRetryCandles,
                    );
                  }
                  final series = snapshot.data;
                  if (series == null) {
                    return const SizedBox(
                      height: 200,
                      child: Center(child: CircularProgressIndicator()),
                    );
                  }
                  return CandleChart(items: series.items);
                },
              ),
            ],
          ),
        ),
        const SizedBox(height: 16),

        // 호가
        AppCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('호가', style: theme.textTheme.titleLarge),
              const SizedBox(height: 8),
              FutureBuilder<OrderBook>(
                future: bookFuture,
                builder: (context, snapshot) {
                  if (snapshot.hasError) {
                    return Notice(
                      message: snapshot.error is ApiException
                          ? (snapshot.error! as ApiException).message
                          : '호가를 불러오지 못했어요',
                      onRetry: onRetryBook,
                    );
                  }
                  final book = snapshot.data;
                  if (book == null) {
                    return const SizedBox(
                      height: 80,
                      child: Center(child: CircularProgressIndicator()),
                    );
                  }
                  return _OrderBookView(book: book);
                },
              ),
            ],
          ),
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

class _OrderBookView extends StatelessWidget {
  const _OrderBookView({required this.book});

  final OrderBook book;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (book.virtual)
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: _Badge(
              label: book.description ?? '가상 호가',
              color: scheme.onSurfaceVariant,
            ),
          ),
        // 매도 호가는 위에서 아래로 내려가는 순서(높은 가격이 위)로 보여준다.
        for (final level in book.asks.reversed)
          _LevelRow(level: level, isAsk: true, scheme: scheme, theme: theme),
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 6),
          child: Center(
            child: Text(
              '기준가 ${formatMoney(book.basePrice, book.currency)}',
              style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w700,
                color: scheme.onSurfaceVariant,
              ),
            ),
          ),
        ),
        for (final level in book.bids)
          _LevelRow(level: level, isAsk: false, scheme: scheme, theme: theme),
      ],
    );
  }
}

class _LevelRow extends StatelessWidget {
  const _LevelRow({
    required this.level,
    required this.isAsk,
    required this.scheme,
    required this.theme,
  });

  final OrderBookLevel level;
  final bool isAsk;
  final ColorScheme scheme;
  final ThemeData theme;

  @override
  Widget build(BuildContext context) {
    final color = isAsk ? const Color(0xFFEF4444) : const Color(0xFF3B82F6);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(
        children: [
          SizedBox(
            width: 20,
            child: Text(
              '${level.level}',
              style: TextStyle(fontSize: 11, color: scheme.onSurfaceVariant),
            ),
          ),
          Expanded(
            child: Text(
              formatMoney(level.price, null),
              style: theme.textTheme.bodyMedium?.copyWith(color: color),
            ),
          ),
          Text(
            level.quantity,
            style: TextStyle(fontSize: 12, color: scheme.onSurfaceVariant),
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
