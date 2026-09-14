import 'package:flutter/material.dart';

import '../../core/api/api_error.dart';
import '../../core/api/order_api.dart';
import '../../core/models/order_detail.dart';
import '../../formatters.dart';

/// 주문 1건의 상세 + 체결 내역 바텀시트.
/// 목록 응답에 이미 전체 상세가 담겨 있어 주문은 다시 조회하지 않고
/// 체결 내역만 여기서 불러온다. 취소도 이 시트 안에서 한다.
///
/// 반환값: 취소 등으로 주문 상태가 바뀌었으면 최신 [OrderDetail],
/// 아니면 null — 부모가 목록을 갱신할지 판단한다.
class OrderDetailSheet extends StatefulWidget {
  const OrderDetailSheet({super.key, required this.order, required this.api});

  final OrderDetail order;
  final OrderApi api;

  /// 시트를 열고 결과(바뀐 주문 또는 null)를 돌려준다.
  static Future<OrderDetail?> show(
    BuildContext context, {
    required OrderDetail order,
    required OrderApi api,
  }) {
    return showModalBottomSheet<OrderDetail?>(
      context: context,
      isScrollControlled: true,
      builder: (_) => OrderDetailSheet(order: order, api: api),
    );
  }

  @override
  State<OrderDetailSheet> createState() => _OrderDetailSheetState();
}

class _OrderDetailSheetState extends State<OrderDetailSheet> {
  late OrderDetail _order = widget.order;

  List<ExecutionItem> _executions = const [];
  String? _execCursor;
  bool _execHasNext = false;
  bool _execLoading = true;
  bool _execLoadingMore = false;
  bool _execError = false;

  bool _canceling = false;
  String? _cancelError;

  @override
  void initState() {
    super.initState();
    _loadExecutions();
  }

  Future<void> _loadExecutions() async {
    try {
      final page = await widget.api.getOrderExecutions(
        orderId: _order.orderId,
        size: 20,
      );
      if (!mounted) return;
      setState(() {
        _executions = page.items;
        _execCursor = page.nextCursor;
        _execHasNext = page.hasNext;
        _execLoading = false;
      });
    } on ApiException {
      if (!mounted) return;
      setState(() {
        _execLoading = false;
        _execError = true;
      });
    }
  }

  Future<void> _loadMore() async {
    final cursor = _execCursor;
    if (!_execHasNext || cursor == null || _execLoadingMore) return;
    setState(() => _execLoadingMore = true);
    try {
      final page = await widget.api.getOrderExecutions(
        orderId: _order.orderId,
        cursor: cursor,
        size: 20,
      );
      if (!mounted) return;
      setState(() {
        _executions = [..._executions, ...page.items];
        _execCursor = page.nextCursor;
        _execHasNext = page.hasNext;
        _execLoadingMore = false;
      });
    } on ApiException {
      if (mounted) setState(() => _execLoadingMore = false);
    }
  }

  Future<void> _cancel() async {
    if (_canceling) return;
    setState(() {
      _canceling = true;
      _cancelError = null;
    });
    try {
      final updated = await widget.api.cancelOrder(orderId: _order.orderId);
      if (!mounted) return;
      Navigator.of(context).pop(updated);
    } on ApiException catch (e) {
      if (!mounted) return;
      if (e.error.code == 'ORDER_STATE_CONFLICT') {
        // 누르는 사이에 체결/만료된 경합 — 서버 최신 상태로 맞춘 뒤 닫는다.
        try {
          final fresh = await widget.api.getOrderDetail(
            orderId: _order.orderId,
          );
          if (!mounted) return;
          Navigator.of(context).pop(fresh);
          return;
        } on ApiException {
          // 재조회마저 실패하면 원래 오류만 보여준다.
        }
      }
      setState(() => _cancelError = e.message);
    } finally {
      if (mounted) setState(() => _canceling = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final order = _order;
    final isUsd = order.marketCountry.wireValue == 'US';
    return SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(24, 20, 24, 24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    '${order.name} ${order.symbol}',
                    style: theme.textTheme.titleLarge,
                  ),
                ),
                TextButton(
                  onPressed: () => Navigator.of(context).pop(),
                  child: const Text('닫기'),
                ),
              ],
            ),
            Wrap(
              spacing: 6,
              children: [
                _chip(
                  order.side == 'BUY' ? '매수' : '매도',
                  order.side == 'BUY'
                      ? const Color(0xFF3B82F6)
                      : const Color(0xFFEF4444),
                ),
                _chip(
                  order.orderType == 'LIMIT' ? '지정가' : '시장가',
                  scheme.onSurfaceVariant,
                ),
                _chip(_statusLabel(order.status), _statusColor(order.status)),
              ],
            ),
            const SizedBox(height: 16),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: scheme.surfaceContainerHighest.withValues(alpha: 0.4),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Column(
                children: [
                  _row(
                    '수량',
                    '${formatNumber(order.filledQuantity ?? '0')} / '
                        '${formatNumber(order.quantity)}주',
                  ),
                  if (order.cancellable)
                    _row(
                      '미체결 수량',
                      '${formatNumber(order.activeRemainingQuantity)}주',
                    ),
                  if (order.orderType == 'LIMIT')
                    _row(
                      '지정가',
                      formatMoney(
                        order.requestedLimitPrice,
                        order.requestedLimitCurrency,
                      ),
                    ),
                  _row('주문금액', '${formatNumber(order.grossAmount)}원'),
                  _row('수수료', '${formatNumber(order.fee)}원'),
                  _row('세금', '${formatNumber(order.tax)}원'),
                  _row('예약금액', '${formatNumber(order.reservedCash)}원'),
                  _row('주문시각', _dateTime(order.orderedAt)),
                  if (order.expiresAt != null)
                    _row('만료시각', _dateTime(order.expiresAt)),
                  if (order.closedAt != null)
                    _row('종료시각', _dateTime(order.closedAt)),
                  if (order.rejectReason != null)
                    _row(
                      '거절사유',
                      order.rejectReason!,
                      valueColor: scheme.error,
                    ),
                ],
              ),
            ),
            const SizedBox(height: 16),
            Text(
              '체결 내역',
              style: TextStyle(
                fontSize: 13,
                fontWeight: FontWeight.w700,
                color: scheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 8),
            if (_execLoading)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 24),
                child: Center(child: CircularProgressIndicator()),
              )
            else if (_execError)
              Center(
                child: Text(
                  '체결 내역을 불러오지 못했어요',
                  style: TextStyle(color: scheme.onSurfaceVariant),
                ),
              )
            else if (_executions.isEmpty)
              Center(
                child: Padding(
                  padding: const EdgeInsets.symmetric(vertical: 20),
                  child: Text(
                    '아직 체결된 내역이 없어요',
                    style: TextStyle(color: scheme.onSurfaceVariant),
                  ),
                ),
              )
            else
              Container(
                decoration: BoxDecoration(
                  border: Border.all(color: scheme.outlineVariant),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Column(
                  children: [
                    for (final ex in _executions)
                      Padding(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 14,
                          vertical: 10,
                        ),
                        child: Row(
                          children: [
                            Text(
                              '#${ex.sequenceNo}',
                              style: TextStyle(
                                fontSize: 12.5,
                                color: scheme.onSurfaceVariant,
                              ),
                            ),
                            const SizedBox(width: 10),
                            Expanded(
                              child: Text(
                                '${formatNumber(ex.quantity)}주 @ '
                                '${formatMoney(ex.price, isUsd ? 'USD' : 'KRW')}',
                                style: const TextStyle(fontSize: 12.5),
                              ),
                            ),
                            Text(
                              '${formatNumber(ex.netAmount)}원',
                              style: const TextStyle(fontSize: 12.5),
                            ),
                            const SizedBox(width: 8),
                            Text(
                              _dateTime(ex.executedAt, short: true),
                              style: TextStyle(
                                fontSize: 12,
                                color: scheme.onSurfaceVariant,
                              ),
                            ),
                          ],
                        ),
                      ),
                  ],
                ),
              ),
            if (_execHasNext)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: SizedBox(
                  width: double.infinity,
                  child: OutlinedButton(
                    onPressed: _execLoadingMore ? null : _loadMore,
                    child: Text(_execLoadingMore ? '불러오는 중…' : '더 보기'),
                  ),
                ),
              ),
            if (_cancelError != null)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text(
                  _cancelError!,
                  style: TextStyle(fontSize: 12.5, color: scheme.error),
                ),
              ),
            if (order.cancellable) ...[
              const SizedBox(height: 12),
              SizedBox(
                width: double.infinity,
                child: FilledButton(
                  style: FilledButton.styleFrom(
                    backgroundColor: scheme.errorContainer,
                    foregroundColor: scheme.onErrorContainer,
                  ),
                  onPressed: _canceling ? null : _cancel,
                  child: Text(_canceling ? '취소하는 중…' : '주문 취소'),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _chip(String label, Color color) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Text(
        label,
        style: TextStyle(
          fontSize: 12,
          fontWeight: FontWeight.w700,
          color: color,
        ),
      ),
    );
  }

  Widget _row(String label, String value, {Color? valueColor}) {
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label, style: TextStyle(color: scheme.onSurfaceVariant)),
          Text(
            value,
            style: TextStyle(fontSize: 13.5, color: valueColor),
          ),
        ],
      ),
    );
  }

  static String _statusLabel(String status) => switch (status) {
    'PENDING' => '접수',
    'PARTIALLY_FILLED' => '부분체결',
    'FILLED' => '체결완료',
    'REJECTED' => '거절',
    'CANCELED' => '취소됨',
    'EXPIRED' => '만료',
    _ => status,
  };

  static Color _statusColor(String status) => switch (status) {
    'PENDING' => const Color(0xFF3B82F6),
    'PARTIALLY_FILLED' => const Color(0xFFF59E0B),
    'FILLED' => const Color(0xFF16A34A),
    'REJECTED' => const Color(0xFFEF4444),
    _ => const Color(0xFF9CA3AF),
  };

  static String _dateTime(DateTime? at, {bool short = false}) {
    if (at == null) return '-';
    final local = at.toLocal();
    String two(int v) => v.toString().padLeft(2, '0');
    if (short) {
      return '${two(local.month)}-${two(local.day)} '
          '${two(local.hour)}:${two(local.minute)}';
    }
    return '${local.year}-${two(local.month)}-${two(local.day)} '
        '${two(local.hour)}:${two(local.minute)}';
  }
}
