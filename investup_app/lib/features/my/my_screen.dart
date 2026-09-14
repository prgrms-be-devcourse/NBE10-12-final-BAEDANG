import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/account_api.dart';
import '../../core/api/api_error.dart';
import '../../core/api/order_api.dart';
import '../../core/api/stock_api.dart';
import '../../core/auth/auth_session.dart';
import '../../core/auth/token_storage.dart';
import '../../core/models/order_detail.dart';
import '../../core/models/stock_like.dart';
import '../../formatters.dart';
import '../../widgets/app_widgets.dart';

/// 마이 탭. 프로필·계좌 요약·관심 종목·주문 내역·로그아웃을 보여준다.
class MyScreen extends StatefulWidget {
  const MyScreen({
    super.key,
    required this.session,
    required this.stocks,
    required this.account,
    required this.orders,
  });

  final AuthSession session;
  final StockApi stocks;
  final AccountApi account;
  final OrderApi orders;

  @override
  State<MyScreen> createState() => _MyScreenState();
}

class _MyScreenState extends State<MyScreen> {
  bool _loggingOut = false;
  Future<StockLikePage>? _likesFuture;
  Future<OrderPage>? _ordersFuture;
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
      });
      return;
    }
    setState(() {
      _likesFuture = widget.stocks.getLikes(size: 20);
      _ordersFuture = widget.account.getOrders(size: 20);
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

  Future<void> _cancelOrder(OrderDetail order) async {
    if (_busyIds.contains(order.orderId)) return;
    setState(() => _busyIds.add(order.orderId));
    try {
      await widget.orders.cancelOrder(orderId: order.orderId);
      _loadLists();
      _refreshAccount();
    } on ApiException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(e.message)));
      }
    } finally {
      if (mounted) setState(() => _busyIds.remove(order.orderId));
    }
  }

  // 주문 취소 뒤 예약금 해제가 계좌에 반영된다.
  void _refreshAccount() => widget.session.reloadAccount();

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
            _LikesSection(
              future: _likesFuture,
              busyIds: _busyIds,
              onUnlike: _unlike,
              onRetry: _loadLists,
            ),
            const SizedBox(height: 24),
            _OrdersSection(
              future: _ordersFuture,
              busyIds: _busyIds,
              onCancel: _cancelOrder,
              onRetry: _loadLists,
            ),
            const SizedBox(height: 24),
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
    required this.busyIds,
    required this.onCancel,
    required this.onRetry,
  });

  final Future<OrderPage>? future;
  final Set<int> busyIds;
  final ValueChanged<OrderDetail> onCancel;
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
                    _OrderRow(order, busyIds, onCancel),
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
  const _OrderRow(this.order, this.busyIds, this.onCancel);

  final OrderDetail order;
  final Set<int> busyIds;
  final ValueChanged<OrderDetail> onCancel;

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
    return Padding(
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
          Column(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              _StatusChip(status: order.status),
              if (order.cancellable)
                TextButton(
                  onPressed: busyIds.contains(order.orderId)
                      ? null
                      : () => onCancel(order),
                  child: const Text('취소'),
                ),
            ],
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
