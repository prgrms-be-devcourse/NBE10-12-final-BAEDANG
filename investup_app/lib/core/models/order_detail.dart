import 'json_read.dart';
import 'market_country.dart';

/// 주문 한 건의 상세. 시장가 주문 결과·주문 내역·지정가 주문 응답에 공통으로 쓴다.
class OrderDetail {
  const OrderDetail({
    required this.orderId,
    required this.symbol,
    required this.name,
    required this.marketCountry,
    required this.orderType,
    required this.side,
    required this.status,
    required this.quantity,
    this.filledQuantity,
    this.activeRemainingQuantity,
    this.requestedLimitPrice,
    this.requestedLimitCurrency,
    this.limitPrice,
    this.reservedCash,
    this.grossAmount,
    this.fee,
    this.tax,
    this.netAmount,
    this.rejectReason,
    this.orderedAt,
    this.expiresAt,
    this.closedAt,
  });

  final int orderId;
  final String symbol;
  final String name;
  final MarketCountry marketCountry;
  final String orderType; // MARKET | LIMIT
  final String side; // BUY | SELL
  final String status; // PENDING | PARTIALLY_FILLED | FILLED | REJECTED | CANCELED | EXPIRED
  final String quantity;
  final String? filledQuantity;
  final String? activeRemainingQuantity;
  final String? requestedLimitPrice;
  final String? requestedLimitCurrency;
  final String? limitPrice;
  final String? reservedCash;
  final String? grossAmount;
  final String? fee;
  final String? tax;
  final String? netAmount;
  final String? rejectReason;
  final DateTime? orderedAt;
  final DateTime? expiresAt;
  final DateTime? closedAt;

  /// 취소 가능한 건은 PENDING(또는 부분체결) 지정가다.
  bool get cancellable =>
      status == 'PENDING' || status == 'PARTIALLY_FILLED';

  factory OrderDetail.fromJson(Map<String, Object?> json) => OrderDetail(
    orderId: json.requireInt('orderId'),
    symbol: json.requireString('symbol'),
    name: json.requireString('name'),
    marketCountry: MarketCountry.fromWire(json.stringOrNull('marketCountry')),
    orderType: json.requireString('orderType'),
    side: json.requireString('side'),
    status: json.requireString('status'),
    quantity: json.requireString('quantity'),
    filledQuantity: json.stringOrNull('filledQuantity'),
    activeRemainingQuantity: json.stringOrNull('activeRemainingQuantity'),
    requestedLimitPrice: json.stringOrNull('requestedLimitPrice'),
    requestedLimitCurrency: json.stringOrNull('requestedLimitCurrency'),
    limitPrice: json.stringOrNull('limitPrice'),
    reservedCash: json.stringOrNull('reservedCash'),
    grossAmount: json.stringOrNull('grossAmount'),
    fee: json.stringOrNull('fee'),
    tax: json.stringOrNull('tax'),
    netAmount: json.stringOrNull('netAmount'),
    rejectReason: json.stringOrNull('rejectReason'),
    orderedAt: json.dateTimeOrNull('orderedAt'),
    expiresAt: json.dateTimeOrNull('expiresAt'),
    closedAt: json.dateTimeOrNull('closedAt'),
  );
}

/// 체결 한 건(부분 체결 포함). sequenceNo 오름차순.
class ExecutionItem {
  const ExecutionItem({
    required this.executionId,
    required this.sequenceNo,
    required this.quantity,
    this.price,
    this.exchangeRate,
    this.grossAmount,
    this.fee,
    this.tax,
    this.netAmount,
    this.balanceAfter,
    this.executedAt,
  });

  final int executionId;
  final int sequenceNo;
  final String quantity;
  final String? price;
  final String? exchangeRate;
  final String? grossAmount;
  final String? fee;
  final String? tax;
  final String? netAmount;
  final String? balanceAfter;
  final DateTime? executedAt;

  factory ExecutionItem.fromJson(Map<String, Object?> json) => ExecutionItem(
    executionId: json.requireInt('executionId'),
    sequenceNo: json.requireInt('sequenceNo'),
    quantity: json.requireString('quantity'),
    price: json.stringOrNull('price'),
    exchangeRate: json.stringOrNull('exchangeRate'),
    grossAmount: json.stringOrNull('grossAmount'),
    fee: json.stringOrNull('fee'),
    tax: json.stringOrNull('tax'),
    netAmount: json.stringOrNull('netAmount'),
    balanceAfter: json.stringOrNull('balanceAfter'),
    executedAt: json.dateTimeOrNull('executedAt'),
  );
}

/// GET /orders/{id}/executions 응답 페이지.
class OrderExecutions {
  const OrderExecutions({required this.items, this.nextCursor, this.hasNext = false});

  final List<ExecutionItem> items;
  final String? nextCursor;
  final bool hasNext;

  factory OrderExecutions.fromJson(Map<String, Object?> json) =>
      OrderExecutions(
        items: json
            .requireObjectList('items')
            .map(ExecutionItem.fromJson)
            .toList(growable: false),
        nextCursor: json.stringOrNull('nextCursor'),
        hasNext: json.requireBool('hasNext'),
      );
}

/// GET /accounts/me/orders 응답 페이지.
class OrderPage {
  const OrderPage({required this.items, this.nextCursor, this.hasNext = false});

  final List<OrderDetail> items;
  final String? nextCursor;
  final bool hasNext;

  factory OrderPage.fromJson(Map<String, Object?> json) => OrderPage(
    items: json
        .requireObjectList('items')
        .map(OrderDetail.fromJson)
        .toList(growable: false),
    nextCursor: json.stringOrNull('nextCursor'),
    hasNext: json.requireBool('hasNext'),
  );
}
