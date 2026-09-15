import 'json_read.dart';

/// 원장 한 건 — 돈이 어떻게 움직였나. INITIAL_DEPOSIT은 주문이 없어
/// orderId/symbol/name이 없다.
class LedgerItem {
  const LedgerItem({
    required this.entryId,
    required this.entryType,
    required this.amount,
    this.balanceAfter,
    this.exchangeRate,
    this.memo,
    this.orderId,
    this.symbol,
    this.name,
    this.occurredAt,
  });

  final int entryId;

  /// `INITIAL_DEPOSIT` | `BUY` | `SELL`
  final String entryType;
  final String amount;
  final String? balanceAfter;
  final String? exchangeRate;
  final String? memo;
  final int? orderId;
  final String? symbol;
  final String? name;
  final DateTime? occurredAt;

  factory LedgerItem.fromJson(Map<String, Object?> json) => LedgerItem(
    entryId: json.requireInt('entryId'),
    entryType: json.requireString('entryType'),
    amount: json.requireString('amount'),
    balanceAfter: json.stringOrNull('balanceAfter'),
    exchangeRate: json.stringOrNull('exchangeRate'),
    memo: json.stringOrNull('memo'),
    orderId: json.intOrNull('orderId'),
    symbol: json.stringOrNull('symbol'),
    name: json.stringOrNull('name'),
    occurredAt: json.dateTimeOrNull('occurredAt'),
  );
}

/// GET /accounts/me/ledger 응답 페이지.
class LedgerPage {
  const LedgerPage({required this.items, this.nextCursor, this.hasNext = false});

  final List<LedgerItem> items;
  final String? nextCursor;
  final bool hasNext;

  factory LedgerPage.fromJson(Map<String, Object?> json) => LedgerPage(
    items: json
        .requireObjectList('items')
        .map(LedgerItem.fromJson)
        .toList(growable: false),
    nextCursor: json.stringOrNull('nextCursor'),
    hasNext: json.requireBool('hasNext'),
  );
}

/// POST /accounts/me/reset 응답 — 새 회차 계좌.
class AccountReset {
  const AccountReset({
    required this.accountId,
    required this.roundNo,
    this.initialCash,
    this.cashBalance,
  });

  final int accountId;
  final int roundNo;
  final String? initialCash;
  final String? cashBalance;

  factory AccountReset.fromJson(Map<String, Object?> json) => AccountReset(
    accountId: json.requireInt('accountId'),
    roundNo: json.requireInt('roundNo'),
    initialCash: json.stringOrNull('initialCash'),
    cashBalance: json.stringOrNull('cashBalance'),
  );
}
