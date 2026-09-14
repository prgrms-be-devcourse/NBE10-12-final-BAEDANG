import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';

import '../../core/api/api_error.dart';
import '../../core/api/order_api.dart';
import '../../core/auth/auth_session.dart';
import '../../core/models/order_quote.dart';
import '../../core/models/stock_detail.dart';
import '../../formatters.dart';

/// 시장가 매수/매도 패널. 바텀시트로 연다.
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
  });

  final StockDetail detail;
  final AuthSession session;
  final OrderApi orders;

  @override
  State<TradePanel> createState() => _TradePanelState();
}

class _TradePanelState extends State<TradePanel> {
  String _side = 'BUY';
  final _quantityController = TextEditingController();
  Timer? _debounce;
  CancelToken? _quoteToken;

  MarketOrderQuote? _quote;
  String? _quoteError;
  bool _quoteLoading = false;
  bool _submitting = false;
  MarketOrderResult? _result;

  /// 이 주문 의도의 ID. 패널을 여는 순간 한 번만 만든다.
  String _clientOrderId = newClientOrderId();

  @override
  void initState() {
    super.initState();
    _quantityController.addListener(_onQuantityChanged);
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _quoteToken?.cancel();
    _quantityController.dispose();
    super.dispose();
  }

  String get _quantity => _quantityController.text.trim();

  bool get _quantityValid {
    final q = num.tryParse(_quantity);
    return q != null && q > 0;
  }

  void _onQuantityChanged() {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 400), _fetchQuote);
    setState(() {
      _quote = null;
      _quoteError = null;
      _result = null;
    });
  }

  Future<void> _fetchQuote() async {
    if (!_quantityValid) {
      setState(() {
        _quote = null;
        _quoteLoading = false;
      });
      return;
    }
    _quoteToken?.cancel();
    final token = CancelToken();
    _quoteToken = token;
    setState(() => _quoteLoading = true);
    try {
      final quote = await widget.orders.getMarketQuote(
        symbol: widget.detail.symbol,
        marketCountry: widget.detail.marketCountry,
        side: _side,
        quantity: _quantity,
        cancelToken: token,
      );
      if (!mounted || token.isCancelled) return;
      setState(() {
        _quote = quote;
        _quoteError = null;
        _quoteLoading = false;
      });
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
        _quote = null;
        _quoteError = e.message;
        _quoteLoading = false;
      });
    }
  }

  void _selectSide(String side) {
    if (side == _side) return;
    setState(() {
      _side = side;
      _quote = null;
      _quoteError = null;
      _result = null;
    });
    _fetchQuote();
  }

  Future<void> _submit() async {
    final quote = _quote;
    final accountId = widget.session.account?.accountId;
    if (quote == null || !quote.executable || accountId == null) return;
    if (_submitting) return;

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(_side == 'BUY' ? '매수 주문' : '매도 주문'),
        content: Text(
          '${widget.detail.name} $_quantity주\n'
          '예상 체결가: ${formatMoney(quote.executedPrice, widget.detail.currency)}\n'
          '${_side == 'BUY' ? '결제 예정' : '입금 예정'}: ${formatMoney(quote.netAmount, 'KRW')}\n\n'
          '시장가로 주문할까요?',
        ),
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
        _result = result;
        _submitting = false;
      });
      // 체결 뒤 계좌 잔고를 다시 불러온다.
      unawaited(widget.session.reloadAccount());
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

  String get _submitLabel {
    if (_submitting) return '주문 중...';
    final quote = _quote;
    if (quote != null && !quote.executable) {
      return tradableReasonLabel(quote.reason);
    }
    return '시장가 ${_side == 'BUY' ? '매수' : '매도'} 주문';
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final detail = widget.detail;

    return SafeArea(
      child: Padding(
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
            Text(
              '${detail.name} 시장가 주문',
              style: theme.textTheme.titleLarge,
            ),
            const SizedBox(height: 16),
            SegmentedButton<String>(
              segments: const [
                ButtonSegment(value: 'BUY', label: Text('매수')),
                ButtonSegment(value: 'SELL', label: Text('매도')),
              ],
              selected: {_side},
              onSelectionChanged: (set) => _selectSide(set.first),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _quantityController,
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              decoration: const InputDecoration(
                labelText: '수량',
                suffixText: '주',
              ),
            ),
            const SizedBox(height: 12),
            if (_quoteLoading)
              const Center(
                child: Padding(
                  padding: EdgeInsets.all(8),
                  child: CircularProgressIndicator(),
                ),
              )
            else if (_quote != null)
              _QuoteSummary(quote: _quote!, currency: detail.currency)
            else if (_quoteError != null)
              Text(_quoteError!, style: TextStyle(color: scheme.error)),
            if (_result != null) ...[
              const SizedBox(height: 12),
              _OrderResultCard(result: _result!),
            ],
            const SizedBox(height: 16),
            FilledButton(
              onPressed:
                  _submitting ||
                          _quoteLoading ||
                          !_quantityValid ||
                          _quote == null ||
                          !_quote!.executable ||
                          _result != null
                      ? null
                      : _submit,
              child: Text(_submitLabel),
            ),
          ],
        ),
      ),
    );
  }
}

class _QuoteSummary extends StatelessWidget {
  const _QuoteSummary({required this.quote, required this.currency});

  final MarketOrderQuote quote;
  final String? currency;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final rows = [
      ('예상 체결가', formatMoney(quote.executedPrice, currency)),
      ('주문 금액', formatMoney(quote.grossAmount, 'KRW')),
      ('수수료', formatMoney(quote.fee, 'KRW')),
      ('세금', formatMoney(quote.tax, 'KRW')),
      (quote.side == 'BUY' ? '결제 예정' : '입금 예정', formatMoney(quote.netAmount, 'KRW')),
      ('주문 가능 금액', formatMoney(quote.availableCash, 'KRW')),
    ];
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
        if (!quote.executable && quote.reason != null)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Text(
              tradableReasonLabel(quote.reason),
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

class _OrderResultCard extends StatelessWidget {
  const _OrderResultCard({required this.result});

  final MarketOrderResult result;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final color = result.filled ? const Color(0xFF16A34A) : scheme.error;
    return Container(
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
            result.filled ? '체결 완료' : '주문 거절 (${result.status})',
            style: TextStyle(fontWeight: FontWeight.w700, color: color),
          ),
          const SizedBox(height: 4),
          Text(
            '주문번호 ${result.orderId} · 체결가 ${formatMoney(result.executedPrice, null)}',
            style: theme.textTheme.bodyMedium,
          ),
          if (result.cashBalanceAfter != null)
            Text(
              '남은 예수금 ${formatMoney(result.cashBalanceAfter, 'KRW')}',
              style: theme.textTheme.bodyMedium,
            ),
        ],
      ),
    );
  }
}
