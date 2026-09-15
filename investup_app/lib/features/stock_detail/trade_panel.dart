import 'dart:async';

import 'package:decimal/decimal.dart';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../core/api/api_error.dart';
import '../../core/api/exchange_rate_api.dart';
import '../../core/api/market_api.dart';
import '../../core/api/order_api.dart';
import '../../core/auth/auth_session.dart';
import '../../core/models/market_country.dart';
import '../../core/models/order_quote.dart';
import '../../core/models/stock_detail.dart';
import '../../formatters.dart';

/// 웹 StockDetailClient의 거래 패널을 Flutter로 옮긴 것 — 모달로 연다.
/// 레이아웃·문구·버튼 상태는 웹과 같은 순서/규칙을 따른다.
///
/// - 금액 계산은 전부 서버가 한다: 시장가는 `GET /orders/quote/market`,
///   지정가는 `GET /orders/quote/limit` 미리보기를 그대로 보여준다.
///   클라이언트에서 수수료·세금을 직접 계산하지 않는다.
/// - 주문 의도 하나에 clientOrderId를 고정한다. 입력이 바뀌면 null로 비우고,
///   서버가 `data.retryPolicy`로 NEW_CLIENT_ORDER_ID를 지시하면 새 ID를 만든다.
class TradePanel extends StatefulWidget {
  const TradePanel({
    super.key,
    required this.detail,
    required this.session,
    required this.orders,
    required this.exchangeRates,
    required this.market,
    this.usdKrwRate,
    this.exchangeRateUpdatedAt,
    this.heldQuantity,
    this.onOrderDone,
    this.onNeedAuth,
  });

  final StockDetail detail;
  final AuthSession session;
  final OrderApi orders;
  final ExchangeRateApi exchangeRates;
  final MarketApi market;

  /// 화면이 이미 받아 둔 USD/KRW. null이고 USD 종목이면 패널이 직접 조회한다.
  final double? usdKrwRate;
  final DateTime? exchangeRateUpdatedAt;

  /// 이 종목의 보유 수량. 매도 상한으로 쓴다 — 보유가 없으면 0.
  final num? heldQuantity;

  /// 주문이 끝나면 호출 — 부모가 보유 수량을 다시 조회한다.
  final VoidCallback? onOrderDone;

  /// 비로그인 제출 시 호출 — 부모가 로그인 화면으로 안내한다.
  final VoidCallback? onNeedAuth;

  /// 웹 INITIAL_CASH — 비로그인·계좌 미조회 상태의 미리보기 기준 예수금.
  static final _initialCash = Decimal.parse('50000000');

  @override
  State<TradePanel> createState() => _TradePanelState();
}

class _TradePanelState extends State<TradePanel> {
  String _orderType = 'MARKET'; // MARKET | LIMIT
  String _side = 'BUY';
  String _limitCurrency = 'KRW'; // US 종목 지정가 입력 통화 토글
  final _quantityController = TextEditingController();
  final _priceController = TextEditingController();
  Timer? _debounce;
  CancelToken? _quoteToken;

  MarketOrderQuote? _marketQuote;
  String? _marketQuoteError;
  bool _marketQuoteLoading = false;
  LimitOrderQuote? _limitQuote;
  String? _limitQuoteError;
  bool _limitQuoteLoading = false;
  bool _submitting = false;
  String? _orderError;
  String? _orderResult;
  ({String field, String message})? _limitFieldError;

  /// 실패 시도의 clientOrderId. null이면 다음 제출에서 새로 발급한다(웹 동일).
  String? _clientOrderId;

  double? _usdKrwRate;
  DateTime? _rateUpdatedAt;
  bool _rateError = false;
  bool _circuitBreakerActive = false;

  bool get _isUsd => widget.detail.currency == 'USD';

  @override
  void initState() {
    super.initState();
    _usdKrwRate = widget.usdKrwRate;
    _rateUpdatedAt = widget.exchangeRateUpdatedAt;
    if (_isUsd && _usdKrwRate == null) _loadExchangeRate();
    _loadMarketEvents();
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _quoteToken?.cancel();
    _quantityController.dispose();
    _priceController.dispose();
    super.dispose();
  }

  // ── 조회 ────────────────────────────────────────────────────────────────

  Future<void> _loadExchangeRate() async {
    try {
      final latest = await widget.exchangeRates.getLatest();
      if (!mounted) return;
      setState(() {
        _usdKrwRate = double.tryParse(latest.rate);
        _rateUpdatedAt = latest.validFrom;
      });
    } on ApiException {
      // 웹과 같이 마지막 정상값을 유지하고 갱신 실패만 표시한다.
      if (mounted) setState(() => _rateError = true);
    }
  }

  /// KOSPI/KOSDAQ만 KIND 시장조치 대상이다 — 다른 시장은 조회하지 않는다.
  Future<void> _loadMarketEvents() async {
    final market = widget.detail.market;
    if (market != 'KOSPI' && market != 'KOSDAQ') return;
    try {
      final events = await widget.market.getMarketEvents(market!, _todayKst());
      if (!mounted) return;
      final now = DateTime.now();
      setState(() {
        _circuitBreakerActive = events.items.any(
          (e) =>
              e.isCircuitBreaker &&
              e.active &&
              (e.haltUntil == null || e.haltUntil!.isAfter(now)),
        );
      });
    } on ApiException {
      // 조회 실패 시 막지 않는다 — 서버 응답이 최종 방어선이다(웹과 같은 원칙).
    }
  }

  /// KST 기준 오늘 `yyyy-MM-dd`.
  static String _todayKst() {
    final kst = DateTime.now().toUtc().add(const Duration(hours: 9));
    String two(int v) => v.toString().padLeft(2, '0');
    return '${kst.year}-${two(kst.month)}-${two(kst.day)}';
  }

  // ── 입력 값 읽기 ────────────────────────────────────────────────────────

  int get _quantity => int.tryParse(_quantityController.text) ?? 0;

  Decimal? get _limitPriceDecimal => Decimal.tryParse(_priceController.text);

  bool get _limitPriceValid =>
      _limitPriceDecimal != null && _limitPriceDecimal! > Decimal.zero;

  Decimal get _availableCash =>
      Decimal.tryParse(widget.session.account?.cashBalance ?? '') ??
      TradePanel._initialCash;

  num get _availableQuantity => widget.heldQuantity ?? 0;

  /// 종목 통화 기준 지정가 — US 종목에 원화로 입력하면 환율로 대략 환산한다
  /// (실제 접수가는 서버가 접수 시점 환율로 확정).
  Decimal? get _limitPriceInStockCurrency {
    final price = _limitPriceDecimal;
    if (!_limitPriceValid || price == null) return null;
    if (!_isUsd || _limitCurrency == 'USD') return price;
    final rate = _usdKrwRate;
    if (rate == null || rate <= 0) return null;
    return (price / Decimal.parse('$rate')).toDecimal();
  }

  /// 시장가 금액 미리보기는 서버 견적만 믿는다 — `executable`/`reason`도
  /// 서버가 주문 정책으로 판단한 값이다.
  String? get _marketAmountReason {
    if (_quantity <= 0) return '수량은 1주 이상의 정수로 입력해주세요';
    if (_side == 'SELL' && _quantity > _availableQuantity) {
      return '보유 수량이 부족해요';
    }
    // 비로그인은 견적을 조회할 수 없다 — 버튼은 살려두고 제출 시 로그인 유도.
    if (!widget.session.isAuthenticated) return null;
    if (_marketQuoteLoading) return '미리보기 확인 중…';
    final quote = _marketQuote;
    if (quote != null && !quote.executable) {
      return tradableReasonLabelOrNull(quote.reason) ?? '지금은 주문할 수 없어요';
    }
    if (quote == null && _marketQuoteError != null) return _marketQuoteError;
    return null;
  }

  String? get _limitAmountReason {
    if (_quantity <= 0) return '수량은 1주 이상의 정수로 입력해주세요';
    if (!_limitPriceValid) return '지정가를 입력해주세요';
    if (_side == 'SELL' && _quantity > _availableQuantity) {
      return '보유 수량이 부족해요';
    }
    if (_limitQuoteLoading) return '미리보기 확인 중…';
    final quote = _limitQuote;
    if (quote != null && !quote.acceptable) {
      return tradableReasonLabelOrNull(quote.reason) ??
          '지금은 지정가 주문을 접수할 수 없어요';
    }
    if (quote == null && _limitQuoteError != null) return _limitQuoteError;
    return null;
  }

  /// 웹 resolveBlockReason과 같은 순서: 종목 상태 → 시장조치 → 금액 사유.
  String? _blockReason(String? amountReason) {
    final reason = widget.detail.tradableReason;
    if (reason == 'SUSPENDED') return '거래정지 종목이에요';
    if (reason == 'LIQUIDATION') return '정리매매 종목이에요';
    if (_circuitBreakerActive) return '서킷브레이커 발동 중이에요';
    if (!widget.detail.tradable) {
      return reason != null ? tradableReasonLabel(reason) : '지금은 거래할 수 없어요';
    }
    return amountReason;
  }

  // ── 서버 미리보기 ───────────────────────────────────────────────────────

  /// 입력이 멈추면(500ms) 서버 미리보기를 조회한다 — 금액 계산·접수 가능
  /// 여부·예약금·만료는 전부 서버 판단이라 클라이언트가 흉내내지 않는다
  /// (웹과 같은 디바운스). 보유 초과 매도는 웹처럼 클라이언트에서 먼저 막고
  /// 견적을 부르지 않는다.
  void _scheduleQuote() {
    _quoteToken?.cancel();
    _debounce?.cancel();
    final overHoldingSell = _side == 'SELL' && _quantity > _availableQuantity;
    final canQuery = widget.session.isAuthenticated &&
        widget.detail.tradable &&
        !overHoldingSell &&
        _quantity > 0 &&
        (_orderType == 'MARKET' || _limitPriceValid);
    if (!canQuery) {
      if (_marketQuote != null ||
          _marketQuoteError != null ||
          _marketQuoteLoading ||
          _limitQuote != null ||
          _limitQuoteError != null ||
          _limitQuoteLoading) {
        setState(() {
          _marketQuote = null;
          _marketQuoteError = null;
          _marketQuoteLoading = false;
          _limitQuote = null;
          _limitQuoteError = null;
          _limitQuoteLoading = false;
        });
      }
      return;
    }
    setState(() {
      if (_orderType == 'MARKET') {
        _marketQuote = null;
        _marketQuoteLoading = true;
      } else {
        _limitQuote = null;
        _limitQuoteLoading = true;
      }
    });
    _debounce = Timer(
      const Duration(milliseconds: 500),
      () => _orderType == 'MARKET' ? _fetchMarketQuote() : _fetchLimitQuote(),
    );
  }

  Future<void> _fetchMarketQuote() async {
    final token = CancelToken();
    _quoteToken = token;
    try {
      final quote = await widget.orders.getMarketQuote(
        symbol: widget.detail.symbol,
        marketCountry: widget.detail.marketCountry,
        side: _side,
        quantity: _quantityController.text,
        cancelToken: token,
      );
      if (!mounted || token.isCancelled) return;
      setState(() {
        _marketQuote = quote;
        _marketQuoteError = null;
        _marketQuoteLoading = false;
      });
    } on DioException catch (e) {
      if (e.type == DioExceptionType.cancel) return;
      if (!mounted || token.isCancelled) return;
      setState(() {
        _marketQuote = null;
        _marketQuoteError = '미리보기를 불러오지 못했어요';
        _marketQuoteLoading = false;
      });
    } on ApiException catch (e) {
      if (!mounted || token.isCancelled) return;
      setState(() {
        _marketQuote = null;
        _marketQuoteError = e.message;
        _marketQuoteLoading = false;
      });
    }
  }

  Future<void> _fetchLimitQuote() async {
    final token = CancelToken();
    _quoteToken = token;
    try {
      final quote = await widget.orders.getLimitQuote(
        symbol: widget.detail.symbol,
        marketCountry: widget.detail.marketCountry,
        side: _side,
        quantity: _quantityController.text,
        limitPrice: _priceController.text,
        limitCurrency: _isUsd ? _limitCurrency : 'KRW',
        cancelToken: token,
      );
      if (!mounted || token.isCancelled) return;
      setState(() {
        _limitQuote = quote;
        _limitQuoteError = null;
        _limitQuoteLoading = false;
      });
    } on DioException catch (e) {
      if (e.type == DioExceptionType.cancel) return;
      if (!mounted || token.isCancelled) return;
      setState(() {
        _limitQuote = null;
        _limitQuoteError = '미리보기를 불러오지 못했어요';
        _limitQuoteLoading = false;
      });
    } on ApiException catch (e) {
      if (!mounted || token.isCancelled) return;
      setState(() {
        _limitQuote = null;
        _limitQuoteError = e.message;
        _limitQuoteLoading = false;
      });
    }
  }

  // ── 입력 변경 ────────────────────────────────────────────────────────────

  /// 주문 내용이 바뀌면 이전 clientOrderId를 재사용하면 안 된다 —
  /// "같은 ID인데 다른 내용"은 NOT_RETRYABLE(DUPLICATE_ORDER)로 거절된다.
  void _resetAttempt() {
    _clientOrderId = null;
    _orderError = null;
    _orderResult = null;
    _limitFieldError = null;
  }

  void _onQuantityChanged(String _) {
    setState(_resetAttempt);
    _scheduleQuote();
  }

  /// 지정가 입력 필터 — 백엔드가 초과 정밀도를 거절하므로 타이핑 단계에서 막는다.
  /// KRW는 정수만, USD는 소수 둘째 자리까지(웹 sanitizeLimitPriceInput과 동일).
  String _sanitizeLimitPrice(String raw) {
    final cleaned = raw.replaceAll(RegExp('[^0-9.]'), '');
    if (_limitCurrency == 'KRW') return cleaned.replaceAll('.', '');
    final dot = cleaned.indexOf('.');
    if (dot == -1) return cleaned;
    final intPart = cleaned.substring(0, dot);
    final fracPart = cleaned.substring(dot + 1).replaceAll('.', '');
    return '$intPart.${fracPart.length > 2 ? fracPart.substring(0, 2) : fracPart}';
  }

  void _onLimitPriceChanged(String raw) {
    final sanitized = _sanitizeLimitPrice(raw);
    if (sanitized != raw) {
      _priceController.value = TextEditingValue(
        text: sanitized,
        selection: TextSelection.collapsed(offset: sanitized.length),
      );
    }
    setState(_resetAttempt);
    _scheduleQuote();
  }

  void _selectOrderType(String type) {
    if (type == _orderType) return;
    setState(() {
      _orderType = type;
      _resetAttempt();
    });
    _scheduleQuote();
  }

  void _selectSide(String side) {
    if (side == _side) return;
    setState(() {
      _side = side;
      _resetAttempt();
    });
    _scheduleQuote();
  }

  void _selectLimitCurrency(String currency) {
    if (currency == _limitCurrency) return;
    setState(() {
      _limitCurrency = currency;
      _priceController.clear();
      _resetAttempt();
    });
    _scheduleQuote();
  }

  // ── 제출 ────────────────────────────────────────────────────────────────

  /// 서버 `data.retryPolicy`에 따라 다음 시도의 clientOrderId를 정한다.
  /// 반환값이 null이면 이 ID로는 재시도하면 안 된다(웹 nextClientOrderId와 동일).
  String? _nextClientOrderId(String? policy, String current) =>
      switch (policy) {
        'NEW_CLIENT_ORDER_ID' => newClientOrderId(),
        'NOT_RETRYABLE' => null,
        _ => current,
      };

  Future<void> _submit() async {
    final blockReason = _blockReason(
      _orderType == 'MARKET' ? _marketAmountReason : _limitAmountReason,
    );
    if (blockReason != null || _submitting) return;
    if (!widget.session.isAuthenticated) {
      Navigator.of(context).pop();
      widget.onNeedAuth?.call();
      return;
    }

    setState(() {
      _submitting = true;
      _orderError = null;
      _limitFieldError = null;
    });

    final accountId = widget.session.account?.accountId;
    if (accountId == null) {
      try {
        await widget.session.reloadAccount();
      } on ApiException {
        if (mounted) {
          setState(() {
            _orderError = '계좌 정보를 불러오지 못했어요. 잠시 후 다시 시도해주세요.';
            _submitting = false;
          });
        }
        return;
      }
      if (!mounted) return;
      if (widget.session.account?.accountId == null) {
        setState(() {
          _orderError = '계좌 정보를 불러오지 못했어요. 잠시 후 다시 시도해주세요.';
          _submitting = false;
        });
        return;
      }
    }

    final idToUse = _clientOrderId ?? newClientOrderId();
    try {
      if (_orderType == 'MARKET') {
        final result = await widget.orders.placeMarketOrder(
          accountId: widget.session.account!.accountId,
          clientOrderId: idToUse,
          symbol: widget.detail.symbol,
          marketCountry: widget.detail.marketCountry,
          side: _side,
          quantity: _quantityController.text,
        );
        if (!mounted) return;
        setState(() {
          _orderResult =
              '${widget.detail.name} ${result.quantity}주 시장가 '
              '${_side == 'BUY' ? '매수' : '매도'} 체결 '
              '(체결가 ${result.executedPrice}${_isUsd ? '\$' : '원'}'
              ' · 총 ${_side == 'BUY' ? '차감' : '입금'}액 '
              '${formatNumber(result.netAmount)}원)';
          _clientOrderId = null;
          _submitting = false;
        });
      } else {
        final result = await widget.orders.placeLimitOrder(
          accountId: widget.session.account!.accountId,
          clientOrderId: idToUse,
          symbol: widget.detail.symbol,
          marketCountry: widget.detail.marketCountry,
          side: _side,
          quantity: _quantityController.text,
          limitPrice: _priceController.text,
          limitCurrency: _isUsd ? _limitCurrency : 'KRW',
        );
        if (!mounted) return;
        setState(() {
          if (result.status == 'REJECTED') {
            // 지정가는 정상 접수 흐름에서도 REJECTED로 201이 내려올 수 있다 — 명확한 실패로 안내.
            _orderError = result.rejectReason ?? '지정가 주문이 거절됐어요.';
          } else {
            final priceLabel = _limitCurrency == 'USD' && _isUsd
                ? '${_priceController.text}\$'
                : '${formatNumber(_priceController.text)}원';
            _orderResult =
                '${widget.detail.name} ${result.quantity}주 지정가($priceLabel) '
                '${_side == 'BUY' ? '매수' : '매도'} 주문을 접수했어요'
                '${result.status == 'PARTIALLY_FILLED' ? ' (일부 체결)' : ''}'
                '. 체결 전까지 예약금이 잠겨요.';
          }
          _clientOrderId = null;
          _submitting = false;
        });
      }
      // 체결 후 잔여 예수금·보유 수량 즉시 반영.
      unawaited(widget.session.reloadAccount());
      widget.onOrderDone?.call();
    } on ApiException catch (e) {
      if (!mounted) return;
      final field = e.error.data?['field'];
      if (_orderType == 'LIMIT' &&
          (field == 'limitPrice' ||
              field == 'limitCurrency' ||
              field == 'quantity')) {
        // 서버가 원인 필드를 콕 집어주면 공용 배너 대신 해당 입력 옆에 표시한다(웹 동일).
        _limitFieldError = (field: '$field', message: e.message);
      } else {
        _orderError = e.message;
      }
      _clientOrderId =
          _nextClientOrderId(e.error.data?['retryPolicy'] as String?, idToUse);
      if (e.error.code == 'ACCOUNT_ROUND_CHANGED' ||
          e.error.code == 'ACCOUNT_NOT_FOUND') {
        unawaited(widget.session.reloadAccount());
      }
      setState(() => _submitting = false);
    }
  }

  // ── 빌드 ────────────────────────────────────────────────────────────────

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final detail = widget.detail;
    final fill = scheme.surfaceContainerHighest;
    final ink = scheme.onSurface;
    final mut = scheme.onSurfaceVariant;
    final isLimit = _orderType == 'LIMIT';
    final isBuy = _side == 'BUY';

    // 웹은 이 버튼만 차트용 --up/--down 대신 옛 매수/매도 색을 고정한다 — 앱의
    // 상승/하락 고정색이 그 역할을 한다.
    final txPillColor =
        isBuy ? const Color(0xFFEF4444) : const Color(0xFF3B82F6);

    final lastPriceKrw =
        toKrw(detail.price?.lastPrice, detail.currency, '${_usdKrwRate ?? ''}');
    final priceLabel =
        '${formatNumber(lastPriceKrw)}'
        '${_isUsd ? ' (${formatUsd(detail.price?.lastPrice)})' : ''}';

    final blockReason = _blockReason(
      isLimit ? _limitAmountReason : _marketAmountReason,
    );
    final isLoggedIn = widget.session.isAuthenticated;

    return SingleChildScrollView(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Text(
                '거래하기',
                style: TextStyle(
                  fontSize: 16,
                  fontWeight: FontWeight.w700,
                  color: ink,
                ),
              ),
              const Spacer(),
              // 모달이라 닫기 affordance가 필요하다 — 웹의 화면 가이드 버튼 자리.
              GestureDetector(
                onTap: () => Navigator.of(context).pop(),
                child: Padding(
                  padding: const EdgeInsets.all(4),
                  child: Icon(Icons.close, size: 18, color: mut),
                ),
              ),
            ],
          ),
          Text(
            isLimit ? '지정가 주문 · 조건 성립 시 체결' : '시장가 주문 · 즉시 체결',
            style: TextStyle(
              fontSize: 13.5,
              fontWeight: FontWeight.w700,
              color: mut,
            ),
          ),
          const SizedBox(height: 14),

          _TxPillTabs(
            options: const [('MARKET', '시장가'), ('LIMIT', '지정가')],
            value: _orderType,
            onChanged: _selectOrderType,
            pillColor: scheme.primary,
            fontSize: 13,
            verticalPadding: 6,
          ),
          const SizedBox(height: 12),
          _TxPillTabs(
            options: const [('BUY', '매수'), ('SELL', '매도')],
            value: _side,
            onChanged: _selectSide,
            pillColor: txPillColor,
            fontSize: 15,
            verticalPadding: 8,
          ),
          const SizedBox(height: 14),

          Text(
            '주문 수량',
            style: TextStyle(
              fontSize: 13.5,
              fontWeight: FontWeight.w700,
              color: mut,
            ),
          ),
          const SizedBox(height: 4),
          TextField(
            controller: _quantityController,
            keyboardType: TextInputType.number,
            inputFormatters: [
              FilteringTextInputFormatter.digitsOnly,
              LengthLimitingTextInputFormatter(9),
            ],
            onChanged: _onQuantityChanged,
            style: TextStyle(
              fontSize: 14.5,
              fontWeight: FontWeight.w700,
              color: ink,
            ),
            decoration: _fillInputDecoration(fill),
          ),
          if (_limitFieldError?.field == 'quantity')
            _fieldErrorText(_limitFieldError!.message, scheme),
          const SizedBox(height: 12),
          if (_side == 'SELL')
            _caption('보유 ${formatNumber(_availableQuantity)}주', mut),

          if (isLimit) ...[
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  '지정가',
                  style: TextStyle(
                    fontSize: 13.5,
                    fontWeight: FontWeight.w700,
                    color: mut,
                  ),
                ),
                if (_isUsd)
                  _CurrencyToggle(
                    value: _limitCurrency,
                    onChanged: _selectLimitCurrency,
                  ),
              ],
            ),
            const SizedBox(height: 4),
            TextField(
              controller: _priceController,
              keyboardType: const TextInputType.numberWithOptions(
                decimal: true,
              ),
              maxLength: 15,
              onChanged: _onLimitPriceChanged,
              style: TextStyle(
                fontSize: 14.5,
                fontWeight: FontWeight.w700,
                color: ink,
              ),
              decoration: _fillInputDecoration(fill).copyWith(
                counterText: '',
                hintText: _limitCurrency == 'USD' ? '예: 95.50' : '예: 72000',
              ),
            ),
            _caption(
              _isUsd
                  ? '가격 제한 없음'
                  : detail.price?.lowerLimit != null &&
                        detail.price?.upperLimit != null
                  ? '주문 가능 범위: ${formatNumber(detail.price!.lowerLimit)}원 ~ '
                        '${formatNumber(detail.price!.upperLimit)}원 (호가 단위 적용)'
                  : '당일 상하한가 확인 후 주문할 수 있어요',
              mut,
            ),
            if (_isUsd && _limitCurrency == 'KRW' && _limitPriceValid)
              _caption(
                '약 ${_limitPriceInStockCurrency?.toDouble().toStringAsFixed(2) ?? '-'}\$로 '
                '환산돼요(접수 시점 환율로 최종 확정)',
                mut,
              ),
            if (_limitFieldError?.field == 'limitPrice' ||
                _limitFieldError?.field == 'limitCurrency')
              _fieldErrorText(_limitFieldError!.message, scheme),
            const SizedBox(height: 2),
          ],

          Padding(
            padding: const EdgeInsets.only(bottom: 14),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  isLimit ? '참고 현재가' : '체결 예상 단가',
                  style: TextStyle(
                    fontSize: 13.5,
                    fontWeight: FontWeight.w700,
                    color: mut,
                  ),
                ),
                Flexible(
                  child: Text(
                    '$priceLabel${isLimit ? '' : ' (현재가)'}',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 13.5,
                      fontWeight: FontWeight.w700,
                      color: ink,
                    ),
                  ),
                ),
              ],
            ),
          ),

          _SummaryCard(
            fill: fill,
            ink: ink,
            mut: mut,
            scheme: scheme,
            isUsd: _isUsd,
            isLimit: isLimit,
            isBuy: isBuy,
            isKr: detail.marketCountry == MarketCountry.kr,
            rateLine: _rateLine(),
            displayAmount: _displayAmount(),
            limitQuote: _limitQuote,
            limitQuoteLoading: _limitQuoteLoading,
            isLoggedIn: isLoggedIn,
            currency: detail.currency,
          ),

          Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  '주문가능금액',
                  style: TextStyle(
                    fontSize: 13.5,
                    fontWeight: FontWeight.w700,
                    color: mut,
                  ),
                ),
                Text(
                  '${formatNumber(_availableCash.toString())}원',
                  style: TextStyle(
                    fontSize: 13.5,
                    fontWeight: FontWeight.w700,
                    color: ink,
                  ),
                ),
              ],
            ),
          ),

          if (blockReason != null)
            Container(
              width: double.infinity,
              padding: const EdgeInsets.symmetric(vertical: 12),
              decoration: BoxDecoration(
                color: fill,
                borderRadius: BorderRadius.circular(12),
              ),
              child: Text(
                blockReason,
                textAlign: TextAlign.center,
                style: TextStyle(
                  fontSize: 15,
                  fontWeight: FontWeight.w700,
                  color: scheme.outline,
                ),
              ),
            )
          else
            GestureDetector(
              onTap: _submitting ? null : _submit,
              child: AnimatedContainer(
                duration: const Duration(milliseconds: 150),
                width: double.infinity,
                padding: const EdgeInsets.symmetric(vertical: 12),
                decoration: BoxDecoration(
                  color: _submitting
                      ? scheme.primary.withValues(alpha: 0.6)
                      : scheme.primary,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Text(
                  _submitting
                      ? '처리 중…'
                      : isLimit
                      ? (isBuy ? '매수 주문 접수' : '매도 주문 접수')
                      : (isBuy ? '매수하기' : '매도하기'),
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                    fontSize: 15,
                    fontWeight: FontWeight.w700,
                    color: Colors.white,
                  ),
                ),
              ),
            ),
          if (!isLoggedIn)
            Padding(
              padding: const EdgeInsets.only(top: 6),
              child: Text(
                '비로그인 상태에서 누르면 회원가입으로 안내돼요',
                textAlign: TextAlign.center,
                style: TextStyle(fontSize: 12.5, color: mut),
              ),
            ),

          const SizedBox(height: 16),
          _DashedDivider(color: scheme.outlineVariant),
          const SizedBox(height: 16),
          Text(
            '참고 — 주문 불가 상태 예시',
            style: TextStyle(
              fontSize: 13,
              fontWeight: FontWeight.w700,
              color: mut,
            ),
          ),
          const SizedBox(height: 10),
          for (final example in const [
            '장 마감 · 09:00~15:30 거래 가능',
            '거래정지 종목',
            '주문가능금액 부족',
          ])
            Padding(
              padding: const EdgeInsets.only(bottom: 10),
              child: Container(
                width: double.infinity,
                padding: const EdgeInsets.symmetric(vertical: 12),
                decoration: BoxDecoration(
                  color: fill,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Text(
                  example,
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w700,
                    color: scheme.outline,
                  ),
                ),
              ),
            ),

          if (_orderError != null)
            Container(
              margin: const EdgeInsets.only(top: 12),
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
              decoration: BoxDecoration(
                color: scheme.error.withValues(alpha: 0.07),
                border: Border.all(
                  color: scheme.error.withValues(alpha: 0.3),
                ),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    _orderError!,
                    style: TextStyle(fontSize: 13.5, color: scheme.error),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    _clientOrderId != null
                        ? '같은 주문으로 다시 시도하시려면 버튼을 다시 눌러주세요.'
                        : '주문 내용을 확인한 뒤 다시 시도해주세요.',
                    style: TextStyle(fontSize: 12, color: mut),
                  ),
                ],
              ),
            ),
          if (_orderResult != null)
            Container(
              margin: const EdgeInsets.only(top: 12),
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
              decoration: BoxDecoration(
                color: scheme.primary.withValues(alpha: 0.1),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Text(
                _orderResult!,
                style: TextStyle(fontSize: 13.5, color: scheme.primary),
              ),
            ),
        ],
      ),
    );
  }

  /// 요약 카드의 환율 줄 — 견적이 실제 적용 환율을 내려주면 그 값을 우선한다.
  String _rateLine() {
    if (!_isUsd) return '';
    final limitQuote = _limitQuote;
    if (_orderType == 'LIMIT' && limitQuote != null) {
      return '접수 환율 ${formatNumber(limitQuote.acceptanceExchangeRate)}원 — '
          '실제 체결 시점 환율은 달라질 수 있어요';
    }
    final appliedRate =
        _orderType == 'MARKET' ? _marketQuote?.exchangeRate : null;
    final updated = _rateUpdatedAt;
    final base = updated != null
        ? '(${_fmtRateTime(updated)} 기준)'
        : '(환율 정보 없음)';
    return '적용 환율 ${formatNumber(appliedRate ?? '${_usdKrwRate ?? ''}')}원 $base'
        '${_rateError ? ' · 화면 환율 갱신 실패, 마지막 정상값이 있으면 유지' : ''}';
  }

  /// 'MM. dd. HH:mm' — 웹 toLocaleString(ko-KR, month/day/hour/minute)과 같은 형태.
  static String _fmtRateTime(DateTime t) {
    final l = t.toLocal();
    String two(int v) => v.toString().padLeft(2, '0');
    return '${two(l.month)}. ${two(l.day)}. ${two(l.hour)}:${two(l.minute)}';
  }

  /// 요약 카드에 보여줄 금액 — 전부 서버 견적 값이다(계산은 서버만 한다).
  ({String? gross, String? fee, String? tax, String? net}) _displayAmount() {
    if (_orderType == 'LIMIT') {
      final quote = _limitQuote;
      return (
        gross: quote?.grossAmount,
        fee: quote?.fee,
        tax: quote?.tax,
        net: quote?.netAmount,
      );
    }
    final quote = _marketQuote;
    return (
      gross: quote?.grossAmount,
      fee: quote?.fee,
      tax: quote?.tax,
      net: quote?.netAmount,
    );
  }

  InputDecoration _fillInputDecoration(Color fill) => InputDecoration(
    filled: true,
    fillColor: fill,
    isDense: true,
    contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
    border: OutlineInputBorder(
      borderRadius: BorderRadius.circular(12),
      borderSide: BorderSide.none,
    ),
    enabledBorder: OutlineInputBorder(
      borderRadius: BorderRadius.circular(12),
      borderSide: BorderSide.none,
    ),
    focusedBorder: OutlineInputBorder(
      borderRadius: BorderRadius.circular(12),
      borderSide: BorderSide.none,
    ),
  );

  Widget _caption(String text, Color mut) => Padding(
    padding: const EdgeInsets.only(bottom: 14),
    child: Text(text, style: TextStyle(fontSize: 12.5, color: mut)),
  );

  Widget _fieldErrorText(String message, ColorScheme scheme) => Padding(
    padding: const EdgeInsets.only(top: 4),
    child: Text(
      message,
      style: TextStyle(fontSize: 12, color: scheme.error),
    ),
  );
}

/// 웹 PillTabs를 거래 패널용으로 옮긴 것 — 둥근 사각 트랙 위에 선택 pill.
/// 트랙 `rounded-xl p-1`(≈12px/4px), pill 반경 9px, 선택 시 흰 글자.
class _TxPillTabs<T> extends StatelessWidget {
  const _TxPillTabs({
    required this.options,
    required this.value,
    required this.onChanged,
    required this.pillColor,
    required this.fontSize,
    required this.verticalPadding,
  });

  final List<(T, String)> options;
  final T value;
  final ValueChanged<T> onChanged;
  final Color pillColor;
  final double fontSize;
  final double verticalPadding;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.all(4),
      decoration: BoxDecoration(
        color: scheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          for (final (option, label) in options)
            Expanded(
              child: GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTap: () => onChanged(option),
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 150),
                  padding: EdgeInsets.symmetric(vertical: verticalPadding),
                  decoration: BoxDecoration(
                    color: option == value ? pillColor : Colors.transparent,
                    borderRadius: BorderRadius.circular(9),
                  ),
                  child: Text(
                    label,
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      fontSize: fontSize,
                      fontWeight: FontWeight.w700,
                      color: option == value
                          ? Colors.white
                          : scheme.onSurfaceVariant,
                    ),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

/// 지정가 통화 토글(원/$) — 웹의 작은 원형 pill 토글과 같은 모양.
class _CurrencyToggle extends StatelessWidget {
  const _CurrencyToggle({required this.value, required this.onChanged});

  final String value;
  final ValueChanged<String> onChanged;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      width: 76,
      padding: const EdgeInsets.all(2),
      decoration: BoxDecoration(
        color: scheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Row(
        children: [
          for (final (option, label) in const [('KRW', '원'), ('USD', '\$')])
            Expanded(
              child: GestureDetector(
                onTap: () => onChanged(option),
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 150),
                  padding: const EdgeInsets.symmetric(vertical: 2),
                  decoration: BoxDecoration(
                    color: option == value ? scheme.surface : null,
                    borderRadius: BorderRadius.circular(999),
                  ),
                  child: Text(
                    label,
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      fontSize: 11.5,
                      fontWeight: FontWeight.w700,
                      color: option == value
                          ? scheme.onSurface
                          : scheme.onSurfaceVariant,
                    ),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

/// 주문 요약 카드 — 환율 줄·금액 행들·지정가 미리보기를 웹과 같은 순서로 그린다.
class _SummaryCard extends StatelessWidget {
  const _SummaryCard({
    required this.fill,
    required this.ink,
    required this.mut,
    required this.scheme,
    required this.isUsd,
    required this.isLimit,
    required this.isBuy,
    required this.isKr,
    required this.rateLine,
    required this.displayAmount,
    required this.limitQuote,
    required this.limitQuoteLoading,
    required this.isLoggedIn,
    required this.currency,
  });

  final Color fill;
  final Color ink;
  final Color mut;
  final ColorScheme scheme;
  final bool isUsd;
  final bool isLimit;
  final bool isBuy;
  final bool isKr;
  final String rateLine;
  final ({String? gross, String? fee, String? tax, String? net}) displayAmount;
  final LimitOrderQuote? limitQuote;
  final bool limitQuoteLoading;
  final bool isLoggedIn;
  final String? currency;

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(bottom: 14),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: fill,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        children: [
          if (isUsd)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Align(
                alignment: Alignment.centerLeft,
                child: Text(
                  rateLine,
                  style: TextStyle(fontSize: 11.5, color: mut),
                ),
              ),
            ),
          if (!isLoggedIn)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 6),
              child: Text(
                '로그인하면 예상 금액을 확인할 수 있어요',
                textAlign: TextAlign.center,
                style: TextStyle(fontSize: 13, color: mut),
              ),
            )
          else ...[
          _row('주문 금액', formatNumber(displayAmount.gross), bold: true),
          _row('수수료 0.01%', formatNumber(displayAmount.fee)),
          _row(
            '세금 (${isBuy ? '매수는 없음' : isKr ? '증권거래세 0.2%' : 'SEC Fee'})',
            formatNumber(displayAmount.tax),
          ),
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: Divider(
              height: 1,
              color: scheme.outlineVariant,
            ),
          ),
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  isLimit
                      ? (isBuy ? '예상 동결 예수금' : '예상 입금액')
                      : (isBuy ? '총 차감액' : '총 입금액'),
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w700,
                    color: ink,
                  ),
                ),
                Text(
                  formatNumber(displayAmount.net),
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w700,
                    color: ink,
                  ),
                ),
              ],
            ),
          ),
          if (isLimit) ...[
            Align(
              alignment: Alignment.centerLeft,
              child: Padding(
                padding: const EdgeInsets.only(top: 6),
                child: Text(
                  limitQuoteLoading
                      ? '미리보기 확인 중…'
                      : limitQuote != null
                      ? '${_fmtExpires(limitQuote!.expiresAt)}까지 미체결이면 자동 만료돼요'
                      : '수량·지정가를 입력하면 접수 가능 여부를 확인해요',
                  style: TextStyle(fontSize: 11.5, color: mut),
                ),
              ),
            ),
            if (limitQuote?.executionPreview != null)
              _ExecutionPreviewBox(
                preview: limitQuote!.executionPreview!,
                isBuy: isBuy,
                currency: currency,
                scheme: scheme,
                mut: mut,
              ),
          ],
          ],
        ],
      ),
    );
  }

  /// ko-KR toLocaleString 형태: '2026. 9. 16. 오전 10:00:00'.
  static String _fmtExpires(DateTime? t) {
    if (t == null) return '-';
    final l = t.toLocal();
    final ampm = l.hour < 12 ? '오전' : '오후';
    final h12 = l.hour % 12 == 0 ? 12 : l.hour % 12;
    String two(int v) => v.toString().padLeft(2, '0');
    return '${l.year}. ${l.month}. ${l.day}. $ampm '
        '$h12:${two(l.minute)}:${two(l.second)}';
  }

  Widget _row(String label, String value, {bool bold = false}) => Padding(
    padding: const EdgeInsets.only(bottom: 4),
    child: Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        Text(label, style: TextStyle(fontSize: 13.5, color: mut)),
        Text(
          value,
          style: TextStyle(
            fontSize: 13.5,
            fontWeight: bold ? FontWeight.w700 : FontWeight.w400,
            color: ink,
          ),
        ),
      ],
    ),
  );
}

/// 지정가 주문의 호가 기반 즉시 체결 미리보기 박스.
class _ExecutionPreviewBox extends StatelessWidget {
  const _ExecutionPreviewBox({
    required this.preview,
    required this.isBuy,
    required this.currency,
    required this.scheme,
    required this.mut,
  });

  final LimitExecutionPreview preview;
  final bool isBuy;
  final String? currency;
  final ColorScheme scheme;
  final Color mut;

  @override
  Widget build(BuildContext context) {
    final filled = num.tryParse(preview.expectedFilledQuantity ?? '') ?? 0;
    final remaining = num.tryParse(preview.remainingQuantity ?? '') ?? 0;
    final released = num.tryParse(preview.releasedCash ?? '') ?? 0;

    return Container(
      margin: const EdgeInsets.only(top: 10),
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: scheme.surface,
        border: Border.all(color: scheme.outlineVariant),
        borderRadius: BorderRadius.circular(8),
      ),
      child: switch (preview.status) {
        'AVAILABLE' when filled > 0 => Column(
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  '호가 기준 즉시 체결 예상',
                  style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w700,
                    color: scheme.primary,
                  ),
                ),
                Text(
                  '${formatNumber(preview.expectedFilledQuantity)}주',
                  style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w700,
                    color: scheme.primary,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 4),
            _previewRow(
              '예상 평균 체결가',
              currency == 'USD'
                  ? formatUsd(preview.avgExecutionPrice)
                  : '${formatNumber(preview.avgExecutionPrice)}원',
            ),
            if (remaining > 0)
              _previewRow(
                '잔여 미체결 대기',
                '${formatNumber(preview.remainingQuantity)}주',
              ),
            if (isBuy && released > 0)
              Padding(
                padding: const EdgeInsets.only(top: 4),
                child: Container(
                  decoration: BoxDecoration(
                    border: Border(
                      top: BorderSide(color: scheme.outlineVariant),
                    ),
                  ),
                  padding: const EdgeInsets.only(top: 4),
                  child: Align(
                    alignment: Alignment.centerLeft,
                    child: Text(
                      '체결 후 약 ${formatNumber(preview.releasedCash)}원의 '
                      '예약금이 예수금으로 환급돼요',
                      style: const TextStyle(
                        fontSize: 11,
                        color: Color(0xFFEF4444),
                      ),
                    ),
                  ),
                ),
              ),
          ],
        ),
        'AVAILABLE' => _previewNote(
          preview.reason == 'PRICE_LIMIT'
              ? '현재 호가 범위를 벗어나 있어, 조건 부합 시까지 미체결 대기해요.'
              : '현재 체결 가능한 호가 잔량이 없어 미체결 대기로 접수돼요.',
        ),
        _ => _previewNote(
          preview.status == 'UNAVAILABLE'
              ? '실시간 호가 확인 중이에요. 접수 후 조건 성립 시 자동 체결돼요.'
              : '주문 조건을 확인해주세요.',
        ),
      },
    );
  }

  Widget _previewNote(String text) => Align(
    alignment: Alignment.centerLeft,
    child: Text(text, style: TextStyle(fontSize: 11.5, color: mut)),
  );

  Widget _previewRow(String label, String value) => Padding(
    padding: const EdgeInsets.only(top: 4),
    child: Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        Text(label, style: TextStyle(fontSize: 11.5, color: mut)),
        Text(value, style: TextStyle(fontSize: 11.5, color: mut)),
      ],
    ),
  );
}

/// 웹의 dashed border-top을 그린다.
class _DashedDivider extends StatelessWidget {
  const _DashedDivider({required this.color});

  final Color color;

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) {
      final count = (constraints.maxWidth / 6).floor();
      return Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          for (var i = 0; i < count; i++)
            Container(width: 3, height: 1, color: color),
        ],
      );
    },
  );
}
