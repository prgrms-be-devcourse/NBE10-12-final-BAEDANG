import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';

import '../../core/api/api_error.dart';
import '../../core/api/order_api.dart';
import '../../core/auth/auth_session.dart';
import '../../core/models/order_detail.dart';
import '../../core/models/order_quote.dart';
import '../../core/models/stock_detail.dart';
import '../../formatters.dart';

/// 시장가·지정가 매수/매도 패널. 바텀시트로 연다.
///
/// - 주문 의도 하나에 clientOrderId를 고정한다. 같은 패널에서의 재시도는
///   같은 ID를 써서 서버가 저장된 결과를 그대로 돌려준다.
/// - 서버가 `data.retryPolicy`로 NEW_CLIENT_ORDER_ID를 지시하면 새 ID를 만든다.
/// - 제출 중에는 버튼을 잠가 중복 제출을 막는다.
class TradePanel extends StatefulWidget {
  const TradePanel({
    super.key,
    required this.detail,
    required this.session,
    required this.orders,
    this.heldQuantity,
    this.onOrderDone,
  });

  final StockDetail detail;
  final AuthSession session;
  final OrderApi orders;

  /// 이 종목의 보유 수량. 매도 상한으로 쓴다 — 보유가 없으면 0.
  final num? heldQuantity;

  /// 주문이 끝나면 호출 — 부모가 보유 수량을 다시 조회한다.
  final VoidCallback? onOrderDone;

  @override
  State<TradePanel> createState() => _TradePanelState();
}

class _TradePanelState extends State<TradePanel> {
  String _orderType = 'MARKET'; // MARKET | LIMIT
  String _side = 'BUY';
  final _quantityController = TextEditingController();
  final _priceController = TextEditingController();
  Timer? _debounce;
  CancelToken? _quoteToken;

  MarketOrderQuote? _marketQuote;
  LimitOrderQuote? _limitQuote;
  String? _quoteError;
  bool _quoteLoading = false;
  bool _submitting = false;
  MarketOrderResult? _marketResult;
  OrderDetail? _limitResult;

  /// 이 주문 의도의 ID. 패널을 여는 순간 한 번만 만든다.
  String _clientOrderId = newClientOrderId();

  @override
  void initState() {
    super.initState();
    _quantityController.addListener(_onInputChanged);
    _priceController.addListener(_onInputChanged);
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _quoteToken?.cancel();
    _quantityController.dispose();
    _priceController.dispose();
    super.dispose();
  }

  String get _quantity => _quantityController.text.trim();

  String get _limitPrice => _priceController.text.trim();

  bool get _quantityValid {
    final q = num.tryParse(_quantity);
    return q != null && q > 0;
  }

  bool get _priceValid {
    final p = num.tryParse(_limitPrice);
    return p != null && p > 0;
  }

  bool get _inputsValid =>
      _quantityValid && (_orderType == 'MARKET' || _priceValid);

  /// 매도 수량이 보유를 넘는지. 보유 수량을 아직 못 받았으면(null) 0으로 본다 —
  /// 어차피 보유 없는 종목은 한 주도 못 판다.
  bool get _sellQuantityExceeded {
    if (_side != 'SELL') return false;
    final q = num.tryParse(_quantity);
    if (q == null || q <= 0) return false;
    return q > (widget.heldQuantity ?? 0);
  }

  void _onInputChanged() {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 400), _fetchQuote);
    setState(() {
      _marketQuote = null;
      _limitQuote = null;
      _quoteError = null;
      _marketResult = null;
      _limitResult = null;
    });
  }

  Future<void> _fetchQuote() async {
    if (!_inputsValid || _sellQuantityExceeded) {
      setState(() {
        _marketQuote = null;
        _limitQuote = null;
        _quoteLoading = false;
      });
      return;
    }
    _quoteToken?.cancel();
    final token = CancelToken();
    _quoteToken = token;
    setState(() => _quoteLoading = true);
    try {
      if (_orderType == 'MARKET') {
        final quote = await widget.orders.getMarketQuote(
          symbol: widget.detail.symbol,
          marketCountry: widget.detail.marketCountry,
          side: _side,
          quantity: _quantity,
          cancelToken: token,
        );
        if (!mounted || token.isCancelled) return;
        setState(() {
          _marketQuote = quote;
          _limitQuote = null;
          _quoteError = null;
          _quoteLoading = false;
        });
      } else {
        final quote = await widget.orders.getLimitQuote(
          symbol: widget.detail.symbol,
          marketCountry: widget.detail.marketCountry,
          side: _side,
          quantity: _quantity,
          limitPrice: _limitPrice,
          limitCurrency: widget.detail.currency ?? 'KRW',
          cancelToken: token,
        );
        if (!mounted || token.isCancelled) return;
        setState(() {
          _limitQuote = quote;
          _marketQuote = null;
          _quoteError = null;
          _quoteLoading = false;
        });
      }
    } on DioException catch (e) {
      if (e.type == DioExceptionType.cancel) return;
      if (!mounted) return;
      setState(() {
        _quoteError = '견적을 불러오지 못했어요';
        _quoteLoading = false;
      });
    } on ApiException catch (e) {
      if (!mounted || token.isCancelled) return;
      setState(() {
        _marketQuote = null;
        _limitQuote = null;
        _quoteError = e.message;
        _quoteLoading = false;
      });
    }
  }

  void _selectSide(String side) {
    if (side == _side) return;
    setState(() {
      _side = side;
      _marketQuote = null;
      _limitQuote = null;
      _quoteError = null;
      _marketResult = null;
      _limitResult = null;
    });
    _fetchQuote();
  }

  void _selectOrderType(String type) {
    if (type == _orderType) return;
    setState(() {
      _orderType = type;
      _marketQuote = null;
      _limitQuote = null;
      _quoteError = null;
      _marketResult = null;
      _limitResult = null;
    });
    _fetchQuote();
  }

  Future<void> _submit() async {
    final accountId = widget.session.account?.accountId;
    final executable = _orderType == 'MARKET'
        ? _marketQuote?.executable == true
        : _limitQuote?.acceptable == true;
    if (!executable || accountId == null || _submitting) return;

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(
          '${_orderType == 'MARKET' ? '시장가' : '지정가'} '
          '${_side == 'BUY' ? '매수' : '매도'} 주문',
        ),
        content: Text(_confirmText()),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('취소'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('주문'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;

    setState(() => _submitting = true);
    try {
      if (_orderType == 'MARKET') {
        final result = await widget.orders.placeMarketOrder(
          accountId: accountId,
          clientOrderId: _clientOrderId,
          symbol: widget.detail.symbol,
          marketCountry: widget.detail.marketCountry,
          side: _side,
          quantity: _quantity,
        );
        if (!mounted) return;
        setState(() {
          _marketResult = result;
          _submitting = false;
        });
      } else {
        final result = await widget.orders.placeLimitOrder(
          accountId: accountId,
          clientOrderId: _clientOrderId,
          symbol: widget.detail.symbol,
          marketCountry: widget.detail.marketCountry,
          side: _side,
          quantity: _quantity,
          limitPrice: _limitPrice,
          limitCurrency: widget.detail.currency ?? 'KRW',
        );
        if (!mounted) return;
        setState(() {
          _limitResult = result;
          _submitting = false;
        });
      }
      // 주문 뒤 계좌 잔고·보유 수량을 다시 불러온다.
      unawaited(widget.session.reloadAccount());
      widget.onOrderDone?.call();
    } on ApiException catch (e) {
      if (!mounted) return;
      // 서버 지시에 따라 재시도 정책을 적용한다.
      final policy = e.error.data?['retryPolicy'];
      if (policy == 'NEW_CLIENT_ORDER_ID') {
        _clientOrderId = newClientOrderId();
      }
      setState(() {
        _quoteError = e.message;
        _submitting = false;
      });
    }
  }

  String _confirmText() {
    final buffer = StringBuffer('${widget.detail.name} $_quantity주\n');
    if (_orderType == 'MARKET') {
      final quote = _marketQuote!;
      buffer.writeln(
        '예상 체결가: ${formatMoney(quote.executedPrice, widget.detail.currency)}',
      );
      buffer.writeln(
        '${_side == 'BUY' ? '결제 예정' : '입금 예정'}: ${formatMoney(quote.netAmount, 'KRW')}',
      );
      buffer.write('\n시장가로 주문할까요?');
    } else {
      final quote = _limitQuote!;
      buffer.writeln(
        '지정가: ${formatMoney(quote.requestedLimitPrice, quote.requestedLimitCurrency)}',
      );
      buffer.writeln(
        '예약 예정: ${formatMoney(quote.reservedCash ?? quote.netAmount, 'KRW')}',
      );
      buffer.write('\n지정가로 주문할까요?');
    }
    return buffer.toString();
  }

  String get _submitLabel {
    if (_submitting) return '주문 중...';
    if (_sellQuantityExceeded) return '보유 수량이 부족해요';
    if (_orderType == 'MARKET') {
      final quote = _marketQuote;
      if (quote != null && !quote.executable) {
        return tradableReasonLabel(quote.reason);
      }
      return '시장가 ${_side == 'BUY' ? '매수' : '매도'} 주문';
    }
    final quote = _limitQuote;
    if (quote != null && !quote.acceptable) {
      return tradableReasonLabel(quote.reason);
    }
    return '지정가 ${_side == 'BUY' ? '매수' : '매도'} 주문';
  }

  bool get _submitEnabled {
    if (_submitting ||
        _quoteLoading ||
        !_inputsValid ||
        _sellQuantityExceeded ||
        _marketResult != null ||
        _limitResult != null) {
      return false;
    }
    if (_orderType == 'MARKET') return _marketQuote?.executable == true;
    return _limitQuote?.acceptable == true;
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final detail = widget.detail;

    return SafeArea(
      child: SingleChildScrollView(
        padding: EdgeInsets.only(
          left: 20,
          right: 20,
          top: 20,
          bottom: MediaQuery.of(context).viewInsets.bottom + 20,
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('${detail.name} 주문', style: theme.textTheme.titleLarge),
            const SizedBox(height: 16),
            SegmentedButton<String>(
              segments: const [
                ButtonSegment(value: 'MARKET', label: Text('시장가')),
                ButtonSegment(value: 'LIMIT', label: Text('지정가')),
              ],
              selected: {_orderType},
              onSelectionChanged: (set) => _selectOrderType(set.first),
            ),
            const SizedBox(height: 8),
            SegmentedButton<String>(
              segments: const [
                ButtonSegment(value: 'BUY', label: Text('매수')),
                ButtonSegment(value: 'SELL', label: Text('매도')),
              ],
              selected: {_side},
              onSelectionChanged: (set) => _selectSide(set.first),
            ),
            const SizedBox(height: 12),
            if (_orderType == 'LIMIT')
              Padding(
                padding: const EdgeInsets.only(bottom: 12),
                child: TextField(
                  controller: _priceController,
                  keyboardType: const TextInputType.numberWithOptions(
                    decimal: true,
                  ),
                  decoration: InputDecoration(
                    labelText: '지정가',
                    suffixText: detail.currency ?? 'KRW',
                  ),
                ),
              ),
            TextField(
              controller: _quantityController,
              keyboardType: const TextInputType.numberWithOptions(
                decimal: true,
              ),
              decoration: const InputDecoration(
                labelText: '수량',
                suffixText: '주',
              ),
            ),
            // 매도는 보유 수량이 자연스러운 상한이다 — 버튼이 막기 전에 알려준다.
            if (_side == 'SELL') ...[
              const SizedBox(height: 6),
              Text(
                '보유 ${formatNumber(widget.heldQuantity ?? 0)}주',
                style: TextStyle(
                  fontSize: 12.5,
                  color: _sellQuantityExceeded
                      ? scheme.error
                      : scheme.onSurfaceVariant,
                ),
              ),
            ],
            const SizedBox(height: 12),
            if (_quoteLoading)
              const Center(
                child: Padding(
                  padding: EdgeInsets.all(8),
                  child: CircularProgressIndicator(),
                ),
              )
            else if (_marketQuote != null)
              _QuoteRows(
                rows: _marketRows(_marketQuote!),
                reason: _marketQuote!.executable
                    ? null
                    : _marketQuote!.reason,
              )
            else if (_limitQuote != null)
              _QuoteRows(
                rows: _limitRows(_limitQuote!),
                reason: _limitQuote!.acceptable
                    ? null
                    : _limitQuote!.reason,
              )
            else if (_quoteError != null)
              Text(_quoteError!, style: TextStyle(color: scheme.error)),
            if (_marketResult != null) ...[
              const SizedBox(height: 12),
              _MarketResultCard(result: _marketResult!),
            ],
            if (_limitResult != null) ...[
              const SizedBox(height: 12),
              _LimitResultCard(order: _limitResult!),
            ],
            const SizedBox(height: 16),
            FilledButton(
              onPressed: _submitEnabled ? _submit : null,
              child: Text(_submitLabel),
            ),
          ],
        ),
      ),
    );
  }

  List<(String, String)> _marketRows(MarketOrderQuote quote) => [
    ('예상 체결가', formatMoney(quote.executedPrice, widget.detail.currency)),
    ('주문 금액', formatMoney(quote.grossAmount, 'KRW')),
    ('수수료', formatMoney(quote.fee, 'KRW')),
    ('세금', formatMoney(quote.tax, 'KRW')),
    (
      quote.side == 'BUY' ? '결제 예정' : '입금 예정',
      formatMoney(quote.netAmount, 'KRW'),
    ),
    ('주문 가능 금액', formatMoney(quote.availableCash, 'KRW')),
  ];

  List<(String, String)> _limitRows(LimitOrderQuote quote) => [
    (
      '지정가',
      formatMoney(quote.requestedLimitPrice, quote.requestedLimitCurrency),
    ),
    ('주문 금액', formatMoney(quote.grossAmount, 'KRW')),
    ('수수료', formatMoney(quote.fee, 'KRW')),
    ('세금', formatMoney(quote.tax, 'KRW')),
    ('예약 예정', formatMoney(quote.reservedCash ?? quote.netAmount, 'KRW')),
    ('주문 가능 금액', formatMoney(quote.availableCash, 'KRW')),
    if (quote.availableQuantity != null) ('주문 가능 수량', quote.availableQuantity!),
    if (quote.expiresAt != null)
      ('만료', quote.expiresAt!.toLocal().toString().substring(0, 16)),
  ];
}

class _QuoteRows extends StatelessWidget {
  const _QuoteRows({required this.rows, this.reason});

  final List<(String, String)> rows;
  final String? reason;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      children: [
        for (final (label, value) in rows)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 3),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(label, style: theme.textTheme.bodyMedium),
                Text(
                  value,
                  style: theme.textTheme.bodyMedium?.copyWith(
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ],
            ),
          ),
        if (reason != null)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Text(
              tradableReasonLabel(reason),
              style: TextStyle(
                fontSize: 13,
                color: Theme.of(context).colorScheme.error,
              ),
            ),
          ),
      ],
    );
  }
}

class _MarketResultCard extends StatelessWidget {
  const _MarketResultCard({required this.result});

  final MarketOrderResult result;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final color = result.filled ? const Color(0xFF16A34A) : scheme.error;
    return _ResultCard(
      color: color,
      title: result.filled ? '체결 완료' : '주문 거절 (${result.status})',
      lines: [
        '주문번호 ${result.orderId} · 체결가 ${formatMoney(result.executedPrice, null)}',
        if (result.cashBalanceAfter != null)
          '남은 예수금 ${formatMoney(result.cashBalanceAfter, 'KRW')}',
      ],
      theme: theme,
    );
  }
}

class _LimitResultCard extends StatelessWidget {
  const _LimitResultCard({required this.order});

  final OrderDetail order;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final color = switch (order.status) {
      'FILLED' || 'PENDING' || 'PARTIALLY_FILLED' => const Color(0xFF16A34A),
      _ => scheme.error,
    };
    final title = switch (order.status) {
      'PENDING' => '주문 접수 완료 (미체결)',
      'PARTIALLY_FILLED' => '부분 체결됨',
      'FILLED' => '체결 완료',
      'REJECTED' => '주문 거절됨',
      _ => '주문 ${order.status}',
    };
    return _ResultCard(
      color: color,
      title: title,
      lines: [
        '주문번호 ${order.orderId} · 지정가 '
            '${formatMoney(order.requestedLimitPrice, order.requestedLimitCurrency)}',
        if (order.rejectReason != null) '사유: ${order.rejectReason}',
        if (order.expiresAt != null)
          '만료 ${order.expiresAt!.toLocal().toString().substring(0, 16)}',
      ],
      theme: theme,
    );
  }
}

class _ResultCard extends StatelessWidget {
  const _ResultCard({
    required this.color,
    required this.title,
    required this.lines,
    required this.theme,
  });

  final Color color;
  final String title;
  final List<String> lines;
  final ThemeData theme;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(12),
    decoration: BoxDecoration(
      color: color.withValues(alpha: 0.08),
      borderRadius: BorderRadius.circular(12),
      border: Border.all(color: color.withValues(alpha: 0.4)),
    ),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          title,
          style: TextStyle(fontWeight: FontWeight.w700, color: color),
        ),
        const SizedBox(height: 4),
        for (final line in lines)
          Text(line, style: theme.textTheme.bodyMedium),
      ],
    ),
  );
}
