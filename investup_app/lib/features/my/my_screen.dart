import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/account_api.dart';
import '../../core/api/api_error.dart';
import '../../core/api/exchange_rate_api.dart';
import '../../core/api/order_api.dart';
import '../../core/api/stock_api.dart';
import '../../core/auth/auth_session.dart';
import '../../core/auth/token_storage.dart';
import '../../core/models/exchange_rate.dart';
import '../../core/models/holding.dart';
import '../../core/models/ledger.dart';
import '../../core/models/order_detail.dart';
import '../../core/models/stock_like.dart';
import '../../formatters.dart';
import '../../widgets/app_widgets.dart';
import 'account_settings.dart';
import 'order_detail_sheet.dart';

/// 마이 탭. 프로필·계좌 요약·관심 종목·주문 내역·로그아웃을 보여준다.
class MyScreen extends StatefulWidget {
  const MyScreen({
    super.key,
    required this.session,
    required this.stocks,
    required this.account,
    required this.orders,
    required this.exchangeRates,
  });

  final AuthSession session;
  final StockApi stocks;
  final AccountApi account;
  final OrderApi orders;
  final ExchangeRateApi exchangeRates;

  @override
  State<MyScreen> createState() => _MyScreenState();
}

class _MyScreenState extends State<MyScreen> {
  bool _loggingOut = false;
  Future<StockLikePage>? _likesFuture;
  Future<OrderPage>? _ordersFuture;
  Future<Holdings>? _holdingsFuture;
  Future<ExchangeRateLatest>? _rateFuture;
  String? _usdKrwRate;
  final Set<int> _busyIds = {};

  @override
  void initState() {
    super.initState();
    _loadLists();
    // 로그인 상태가 바뀌면 목록을 다시 불러온다.
    widget.session.addListener(_loadLists);
  }

  @override
  void dispose() {
    widget.session.removeListener(_loadLists);
    super.dispose();
  }

  void _loadLists() {
    if (!widget.session.isAuthenticated) {
      setState(() {
        _likesFuture = null;
        _ordersFuture = null;
        _holdingsFuture = null;
        _rateFuture = null;
      });
      return;
    }
    setState(() {
      _ledgerTick++;
      _likesFuture = widget.stocks.getLikes(size: 20);
      _ordersFuture = widget.account.getOrders(size: 20);
      _holdingsFuture = widget.account.getHoldings();
      // 해외 종목의 평균단가·현재가 원화 환산에 쓴다.
      _rateFuture = widget.exchangeRates.getLatest().then((r) {
        if (mounted) setState(() => _usdKrwRate = r.rate);
        return r;
      });
    });
  }

  Future<void> _reload() async {
    await widget.session.reloadAccount();
    _loadLists();
  }

  Future<void> _logOut() async {
    if (_loggingOut) return;
    setState(() => _loggingOut = true);
    try {
      await widget.session.logOut();
      // 화면 전환은 라우터 redirect가 담당한다.
    } on StorageException catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(error.message)));
      }
    } finally {
      if (mounted) setState(() => _loggingOut = false);
    }
  }

  Future<void> _unlike(StockLikeItem item) async {
    if (_busyIds.contains(item.stockLikeId)) return;
    setState(() => _busyIds.add(item.stockLikeId));
    try {
      await widget.stocks.unlike(stockLikeId: item.stockLikeId);
      _loadLists();
    } on ApiException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(e.message)));
      }
    } finally {
      if (mounted) setState(() => _busyIds.remove(item.stockLikeId));
    }
  }

  Future<void> _openOrderDetail(OrderDetail order) async {
    final updated = await OrderDetailSheet.show(
      context,
      order: order,
      api: widget.orders,
    );
    // 취소·경합 재조회로 상태가 바뀌었으면 목록과 계좌(예약금 해제)를 갱신한다.
    if (updated != null && mounted) {
      _loadLists();
      _refreshAccount();
    }
  }

  // 주문 취소 뒤 예약금 해제가 계좌에 반영된다.
  void _refreshAccount() => widget.session.reloadAccount();

  /// 원장 섹션의 갱신 신호. 로그인·당겨서 새로고침·거래 반영 때 올린다.
  int _ledgerTick = 0;

  Future<void> _resetAccount() async {
    final accountId = widget.session.account?.accountId;
    if (accountId == null) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('포트폴리오를 정말 초기화할까요?'),
        content: const Text(
          '보유 종목과 체결 내역이 모두 정리되고 모의 투자금이 '
          '50,000,000원으로 되돌아가요.\n되돌릴 수 없어요.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('취소'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('초기화할게요'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    try {
      await widget.account.resetAccount(accountId: accountId);
    } on ApiException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(e.message)));
      }
      return;
    }
    if (!mounted) return;
    // 새 회차로 바뀌었으니 계좌·목록·원장을 전부 다시 읽는다.
    await widget.session.reloadAccount();
    _loadLists();
    setState(() => _ledgerTick++);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return ListenableBuilder(
      listenable: widget.session,
      builder: (context, _) {
        final profile = widget.session.profile;
        final account = widget.session.account;
        if (!widget.session.isAuthenticated) {
          return PageList(
            children: [
              Text('내 정보', style: theme.textTheme.headlineMedium),
              const SizedBox(height: 16),
              AppCard(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Icon(
                      Icons.lock_outline,
                      size: 40,
                      color: theme.colorScheme.primary,
                    ),
                    const SizedBox(height: 12),
                    Text('로그인이 필요해요', style: theme.textTheme.titleLarge),
                    const SizedBox(height: 8),
                    const Text('내 계좌와 투자 기록을 보려면 로그인하세요.'),
                    const SizedBox(height: 16),
                    FilledButton(
                      onPressed: () => context.go('/login?from=/my'),
                      child: const Text('로그인'),
                    ),
                  ],
                ),
              ),
            ],
          );
        }
        return PageList(
          onRefresh: _reload,
          children: [
            Text('내 정보', style: theme.textTheme.headlineMedium),
            const SizedBox(height: 16),
            AppCard(
              child: Row(
                children: [
                  Icon(
                    Icons.account_circle_outlined,
                    size: 40,
                    color: theme.colorScheme.primary,
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          profile?.nickname ?? '-',
                          style: theme.textTheme.titleLarge,
                        ),
                        Text(
                          profile?.email ?? '',
                          style: TextStyle(
                            fontSize: 13,
                            color: theme.colorScheme.onSurfaceVariant,
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),
            if (account == null)
              Notice(
                message: '계좌 정보를 불러오지 못했어요',
                onRetry: _reload,
              )
            else
              AppCard(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      '계좌 ${account.roundNo}회차',
                      style: theme.textTheme.titleLarge,
                    ),
                    const SizedBox(height: 16),
                    _row(
                      context,
                      '총 자산',
                      formatMoney(account.totalAsset, 'KRW'),
                    ),
                    _row(
                      context,
                      '예수금',
                      formatMoney(account.cashBalance, 'KRW'),
                    ),
                    _row(
                      context,
                      '주식 평가금액',
                      formatMoney(account.stockValue, 'KRW'),
                    ),
                    _row(
                      context,
                      '평가손익',
                      formatMoney(account.unrealizedPnl, 'KRW'),
                      valueColor: changeColor(
                        account.unrealizedPnl,
                        theme.colorScheme,
                      ),
                    ),
                    _row(
                      context,
                      '수익률',
                      formatRate(account.unrealizedPnlRate),
                      valueColor: changeColor(
                        account.unrealizedPnlRate,
                        theme.colorScheme,
                      ),
                    ),
                  ],
                ),
              ),
            const SizedBox(height: 24),
            _HoldingsSection(
              future: _holdingsFuture,
              usdKrwRate: _usdKrwRate,
              rateLoaded: _rateFuture != null,
              onRetry: _loadLists,
            ),
            const SizedBox(height: 24),
            _LedgerSection(account: widget.account, tick: _ledgerTick),
            const SizedBox(height: 24),
            _LikesSection(
              future: _likesFuture,
              busyIds: _busyIds,
              onUnlike: _unlike,
              onRetry: _loadLists,
            ),
            const SizedBox(height: 24),
            _OrdersSection(
              future: _ordersFuture,
              onTapOrder: _openOrderDetail,
              onRetry: _loadLists,
            ),
            const SizedBox(height: 24),
            AccountSettings(session: widget.session),
            const SizedBox(height: 24),
            _ResetCard(
              onReset: _resetAccount,
            ),
            const SizedBox(height: 16),
            OutlinedButton.icon(
              onPressed: _loggingOut ? null : _logOut,
              icon: const Icon(Icons.logout),
              label: Text(_loggingOut ? '로그아웃 중…' : '로그아웃'),
            ),
          ],
        );
      },
    );
  }

  Widget _row(
    BuildContext context,
    String label,
    String value, {
    Color? valueColor,
  }) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(
            label,
            style: TextStyle(color: theme.colorScheme.onSurfaceVariant),
          ),
          Text(
            value,
            style: theme.textTheme.bodyLarge?.copyWith(color: valueColor),
          ),
        ],
      ),
    );
  }
}

/// 관심 종목 섹션.
class _LikesSection extends StatelessWidget {
  const _LikesSection({
    required this.future,
    required this.busyIds,
    required this.onUnlike,
    required this.onRetry,
  });

  final Future<StockLikePage>? future;
  final Set<int> busyIds;
  final ValueChanged<StockLikeItem> onUnlike;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('관심 종목', style: theme.textTheme.titleLarge),
        const SizedBox(height: 8),
        FutureBuilder<StockLikePage>(
          future: future,
          builder: (context, snapshot) {
            if (snapshot.hasError) {
              return Notice(
                message: snapshot.error is ApiException
                    ? (snapshot.error! as ApiException).message
                    : '관심 종목을 불러오지 못했어요',
                onRetry: onRetry,
              );
            }
            final page = snapshot.data;
            if (page == null) {
              return const Padding(
                padding: EdgeInsets.all(24),
                child: Center(child: CircularProgressIndicator()),
              );
            }
            if (page.items.isEmpty) {
              return AppCard(
                child: Text(
                  '찜한 종목이 없어요. 랭킹에서 하트를 눌러 추가해보세요.',
                  style: TextStyle(color: theme.colorScheme.onSurfaceVariant),
                ),
              );
            }
            return AppCard(
              child: Column(
                children: [
                  for (final item in page.items) _LikeRow(item, busyIds, onUnlike),
                ],
              ),
            );
          },
        ),
      ],
    );
  }
}

class _LikeRow extends StatelessWidget {
  const _LikeRow(this.item, this.busyIds, this.onUnlike);

  final StockLikeItem item;
  final Set<int> busyIds;
  final ValueChanged<StockLikeItem> onUnlike;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return InkWell(
      onTap: () => context.push(
        '/stocks/${item.symbol}'
        '?marketCountry=${item.marketCountry.wireValue}'
        '&stockId=${item.stockId}&likeId=${item.stockLikeId}',
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(item.name, style: theme.textTheme.bodyLarge),
                  Text(
                    '${item.symbol} · ${item.marketCountry.wireValue}',
                    style: TextStyle(
                      fontSize: 12,
                      color: scheme.onSurfaceVariant,
                    ),
                  ),
                ],
              ),
            ),
            Column(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                Text(
                  formatMoney(item.lastPrice, null),
                  style: theme.textTheme.bodyMedium,
                ),
                Text(
                  formatRate(item.changeRate),
                  style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w700,
                    color: changeColor(item.changeRate, scheme),
                  ),
                ),
              ],
            ),
            IconButton(
              onPressed: busyIds.contains(item.stockLikeId)
                  ? null
                  : () => onUnlike(item),
              icon: const Icon(
                Icons.favorite,
                size: 20,
                color: Color(0xFFEF4444),
              ),
              tooltip: '찜 해제',
            ),
          ],
        ),
      ),
    );
  }
}

/// 주문 내역 섹션.
class _OrdersSection extends StatelessWidget {
  const _OrdersSection({
    required this.future,
    required this.onTapOrder,
    required this.onRetry,
  });

  final Future<OrderPage>? future;
  final ValueChanged<OrderDetail> onTapOrder;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('주문 내역', style: theme.textTheme.titleLarge),
        const SizedBox(height: 8),
        FutureBuilder<OrderPage>(
          future: future,
          builder: (context, snapshot) {
            if (snapshot.hasError) {
              return Notice(
                message: snapshot.error is ApiException
                    ? (snapshot.error! as ApiException).message
                    : '주문 내역을 불러오지 못했어요',
                onRetry: onRetry,
              );
            }
            final page = snapshot.data;
            if (page == null) {
              return const Padding(
                padding: EdgeInsets.all(24),
                child: Center(child: CircularProgressIndicator()),
              );
            }
            if (page.items.isEmpty) {
              return AppCard(
                child: Text(
                  '이번 회차 주문이 없어요',
                  style: TextStyle(color: theme.colorScheme.onSurfaceVariant),
                ),
              );
            }
            return AppCard(
              child: Column(
                children: [
                  for (final order in page.items)
                    _OrderRow(order, onTapOrder),
                ],
              ),
            );
          },
        ),
      ],
    );
  }
}

class _OrderRow extends StatelessWidget {
  const _OrderRow(this.order, this.onTap);

  final OrderDetail order;
  final ValueChanged<OrderDetail> onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final isBuy = order.side == 'BUY';
    final price = order.orderType == 'LIMIT'
        ? formatMoney(
            order.requestedLimitPrice,
            order.requestedLimitCurrency,
          )
        : formatMoney(order.netAmount, 'KRW');
    return InkWell(
      onTap: () => onTap(order),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    '${isBuy ? '매수' : '매도'} ${order.name}',
                    style: theme.textTheme.bodyLarge,
                  ),
                  Text(
                    '${order.orderType == 'LIMIT' ? '지정가' : '시장가'} · '
                    '${order.quantity}주 · $price',
                    style: TextStyle(
                      fontSize: 12,
                      color: scheme.onSurfaceVariant,
                    ),
                  ),
                ],
              ),
            ),
            _StatusChip(status: order.status),
          ],
        ),
      ),
    );
  }
}

/// 보유 종목 섹션. 웹 7열 그리드를 모바일 행 카드로 옮겼다 —
/// 종목/수량 + 평균단가·현재가(USD는 원화 환산 병기) + 평가금액·평가손익.
class _HoldingsSection extends StatelessWidget {
  const _HoldingsSection({
    required this.future,
    required this.usdKrwRate,
    required this.rateLoaded,
    required this.onRetry,
  });

  final Future<Holdings>? future;

  /// 최신 USD/KRW — 해외 종목의 현재가 원화 환산에만 쓴다.
  final String? usdKrwRate;
  final bool rateLoaded;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('보유 종목', style: theme.textTheme.titleLarge),
        const SizedBox(height: 8),
        FutureBuilder<Holdings>(
          future: future,
          builder: (context, snapshot) {
            if (snapshot.hasError) {
              return Notice(
                message: snapshot.error is ApiException
                    ? (snapshot.error! as ApiException).message
                    : '보유 종목을 불러오지 못했어요',
                onRetry: onRetry,
              );
            }
            final holdings = snapshot.data;
            if (holdings == null) {
              return const Padding(
                padding: EdgeInsets.all(24),
                child: Center(child: CircularProgressIndicator()),
              );
            }
            if (holdings.items.isEmpty) {
              return AppCard(
                child: Text(
                  '보유 중인 종목이 없어요',
                  style: TextStyle(color: scheme.onSurfaceVariant),
                ),
              );
            }
            return Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                AppCard(
                  child: Column(
                    children: [
                      for (final h in holdings.items)
                        _HoldingRow(item: h, usdKrwRate: usdKrwRate),
                    ],
                  ),
                ),
                const SizedBox(height: 6),
                Text(
                  usdKrwRate == null
                      ? '환율 정보가 없어 해외 종목 현재가를 원화로 환산할 수 없어요'
                      : '해외 종목 현재가는 적용 환율(${formatNumber(usdKrwRate)} '
                            'KRW/USD)로 환산돼요',
                  style: TextStyle(
                    fontSize: 12,
                    color: scheme.onSurfaceVariant,
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

class _HoldingRow extends StatelessWidget {
  const _HoldingRow({required this.item, required this.usdKrwRate});

  final HoldingItem item;
  final String? usdKrwRate;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    // 평균단가는 매수 시점 환율(avgExchangeRate), 현재가는 최신 환율로 환산한다.
    final avgKrw = toKrw(item.avgBuyPrice, item.currency, item.avgExchangeRate);
    final lastKrw = toKrw(item.lastPrice, item.currency, usdKrwRate);
    final pnl = double.tryParse(item.unrealizedPnl ?? '');
    final pnlUp = pnl == null || pnl >= 0;

    return InkWell(
      onTap: () => context.push(
        '/stocks/${item.symbol}'
        '?marketCountry=${item.isUsd ? 'US' : 'KR'}',
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 10),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    '${item.name} ${item.symbol}',
                    style: theme.textTheme.bodyLarge?.copyWith(
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
                Text(
                  '${formatNumber(item.quantity)}주',
                  style: theme.textTheme.bodyMedium,
                ),
              ],
            ),
            const SizedBox(height: 6),
            Row(
              children: [
                Expanded(
                  child: _priceCell(
                    '평균단가',
                    avgKrw == null ? '-' : '${formatNumber(avgKrw)}원',
                    item.isUsd && item.avgBuyPrice != null
                        ? '\$${formatNumber(item.avgBuyPrice)}'
                        : null,
                    scheme,
                  ),
                ),
                Expanded(
                  child: _priceCell(
                    '현재가',
                    lastKrw == null ? '-' : '${formatNumber(lastKrw)}원',
                    item.isUsd && item.lastPrice != null
                        ? '\$${formatNumber(item.lastPrice)}'
                        : null,
                    scheme,
                  ),
                ),
                Expanded(
                  child: _priceCell(
                    '평가금액',
                    '${formatNumber(item.evaluationAmount)}원',
                    null,
                    scheme,
                  ),
                ),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.end,
                    children: [
                      Text(
                        '평가손익',
                        style: TextStyle(
                          fontSize: 11,
                          color: scheme.onSurfaceVariant,
                        ),
                      ),
                      Text(
                        '${pnl != null && pnl > 0 ? '+' : ''}'
                        '${formatNumber(item.unrealizedPnl)}원',
                        style: TextStyle(
                          fontSize: 13,
                          fontWeight: FontWeight.w700,
                          color: pnlUp
                              ? const Color(0xFFEF4444)
                              : const Color(0xFF3B82F6),
                        ),
                      ),
                      Text(
                        formatRate(item.unrealizedPnlRate),
                        style: TextStyle(
                          fontSize: 11,
                          color: pnlUp
                              ? const Color(0xFFEF4444)
                              : const Color(0xFF3B82F6),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _priceCell(
    String label,
    String krw,
    String? native,
    ColorScheme scheme,
  ) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          label,
          style: TextStyle(fontSize: 11, color: scheme.onSurfaceVariant),
        ),
        Text(
          krw,
          style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600),
        ),
        if (native != null)
          Text(
            native,
            style: TextStyle(fontSize: 10.5, color: scheme.onSurfaceVariant),
          ),
      ],
    );
  }
}

/// 체결 내역(원장) 섹션 — 돈이 어떻게 움직였나. 커서 페이지네이션은
/// 이 섹션이 스스로 누적한다. [tick]이 바뀌면 처음부터 다시 읽는다.
class _LedgerSection extends StatefulWidget {
  const _LedgerSection({required this.account, required this.tick});

  final AccountApi account;
  final int tick;

  @override
  State<_LedgerSection> createState() => _LedgerSectionState();
}

class _LedgerSectionState extends State<_LedgerSection> {
  List<LedgerItem> _items = const [];
  String? _cursor;
  bool _hasNext = false;
  bool _loading = true;
  bool _loadingMore = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void didUpdateWidget(_LedgerSection old) {
    super.didUpdateWidget(old);
    if (old.tick != widget.tick) _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
      _items = const [];
      _cursor = null;
      _hasNext = false;
    });
    try {
      final page = await widget.account.getLedger();
      if (!mounted) return;
      setState(() {
        _items = page.items;
        _cursor = page.nextCursor;
        _hasNext = page.hasNext;
        _loading = false;
      });
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.message;
        _loading = false;
      });
    }
  }

  Future<void> _loadMore() async {
    final cursor = _cursor;
    if (!_hasNext || cursor == null || _loadingMore) return;
    setState(() => _loadingMore = true);
    try {
      final page = await widget.account.getLedger(cursor: cursor);
      if (!mounted) return;
      setState(() {
        _items = [..._items, ...page.items];
        _cursor = page.nextCursor;
        _hasNext = page.hasNext;
        _loadingMore = false;
      });
    } on ApiException {
      if (mounted) setState(() => _loadingMore = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('체결 내역', style: theme.textTheme.titleLarge),
        const SizedBox(height: 8),
        if (_error != null)
          Notice(message: _error!, onRetry: _load)
        else if (_loading)
          const Padding(
            padding: EdgeInsets.all(24),
            child: Center(child: CircularProgressIndicator()),
          )
        else if (_items.isEmpty)
          AppCard(
            child: Text(
              '체결 내역이 없어요',
              style: TextStyle(color: scheme.onSurfaceVariant),
            ),
          )
        else
          Column(
            children: [
              AppCard(
                child: Column(
                  children: [
                    for (final entry in _items) _LedgerRow(entry: entry),
                  ],
                ),
              ),
              if (_hasNext)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: SizedBox(
                    width: double.infinity,
                    child: OutlinedButton(
                      onPressed: _loadingMore ? null : _loadMore,
                      child: Text(_loadingMore ? '불러오는 중…' : '더 보기'),
                    ),
                  ),
                ),
            ],
          ),
      ],
    );
  }
}

class _LedgerRow extends StatelessWidget {
  const _LedgerRow({required this.entry});

  final LedgerItem entry;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final amount = double.tryParse(entry.amount);
    final positive = amount == null || amount >= 0;
    final at = entry.occurredAt?.toLocal();
    String two(int v) => v.toString().padLeft(2, '0');
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Row(
        children: [
          _LedgerBadge(type: entry.entryType),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  entry.memo ?? '-',
                  style: theme.textTheme.bodyMedium,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                ),
                if (at != null)
                  Text(
                    '${two(at.month)}-${two(at.day)} '
                    '${two(at.hour)}:${two(at.minute)}',
                    style: TextStyle(
                      fontSize: 11,
                      color: scheme.onSurfaceVariant,
                    ),
                  ),
              ],
            ),
          ),
          Column(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Text(
                '${positive ? '+' : ''}${formatNumber(entry.amount)}원',
                style: TextStyle(
                  fontWeight: FontWeight.w700,
                  color: positive
                      ? const Color(0xFFEF4444)
                      : const Color(0xFF3B82F6),
                ),
              ),
              Text(
                '잔액 ${formatNumber(entry.balanceAfter)}원',
                style: TextStyle(
                  fontSize: 11,
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

class _LedgerBadge extends StatelessWidget {
  const _LedgerBadge({required this.type});

  final String type;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    // 매수는 돈이 나가니 파랑(하락색), 매도는 들어오니 빨강 — 웹 LedgerBadge와 같다.
    final (label, color) = switch (type) {
      'BUY' => ('매수', const Color(0xFF3B82F6)),
      'SELL' => ('매도', const Color(0xFFEF4444)),
      'INITIAL_DEPOSIT' => ('초기지급', scheme.primary),
      _ => (type, scheme.onSurfaceVariant),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Text(
        label,
        style: TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.w700,
          color: color,
        ),
      ),
    );
  }
}

/// 포트폴리오 초기화 카드. 실제 초기화와 확인 대화상자는 부모가 연다.
class _ResetCard extends StatelessWidget {
  const _ResetCard({required this.onReset});

  final VoidCallback onReset;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: scheme.errorContainer.withValues(alpha: 0.35),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            '포트폴리오 초기화',
            style: TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.w700,
              color: scheme.onSurface,
            ),
          ),
          const SizedBox(height: 6),
          Text(
            '보유 종목과 체결 내역이 모두 정리되고 모의 투자금이 '
            '50,000,000원으로 되돌아가요. 되돌릴 수 없어요.',
            style: TextStyle(
              fontSize: 13,
              color: scheme.onErrorContainer,
            ),
          ),
          const SizedBox(height: 12),
          OutlinedButton(
            onPressed: onReset,
            child: const Text('포트폴리오 초기화'),
          ),
        ],
      ),
    );
  }
}

class _StatusChip extends StatelessWidget {
  const _StatusChip({required this.status});

  final String status;

  @override
  Widget build(BuildContext context) {
    final (label, color) = switch (status) {
      'FILLED' => ('체결', const Color(0xFF16A34A)),
      'PENDING' => ('미체결', const Color(0xFF3B82F6)),
      'PARTIALLY_FILLED' => ('부분체결', const Color(0xFF3B82F6)),
      'CANCELED' => ('취소', const Color(0xFF9CA3AF)),
      'EXPIRED' => ('만료', const Color(0xFF9CA3AF)),
      'REJECTED' => ('거절', const Color(0xFFEF4444)),
      _ => (status, Theme.of(context).colorScheme.onSurfaceVariant),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Text(
        label,
        style: TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.w700,
          color: color,
        ),
      ),
    );
  }
}
