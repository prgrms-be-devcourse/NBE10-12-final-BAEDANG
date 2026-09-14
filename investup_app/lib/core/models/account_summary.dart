import 'json_read.dart';

/// GET /accounts/me 응답.
///
/// 금액은 전부 원(KRW) 문자열이고, 값이 없는 항목은 응답에서 생략된다.
/// 이 응답에 lockedCash/status 필드가 있다고 가정하지 않는다.
class AccountSummary {
  const AccountSummary({
    required this.accountId,
    required this.roundNo,
    this.initialCash,
    this.cashBalance,
    this.stockValue,
    this.totalAsset,
    this.unrealizedPnl,
    this.unrealizedPnlRate,
    this.exchangeRate,
    this.asOf,
  });

  final int accountId;
  final int roundNo;
  final String? initialCash;
  final String? cashBalance;
  final String? stockValue;
  final String? totalAsset;
  final String? unrealizedPnl;

  /// 손익률. 소수점이 붙을 수 있다.
  final String? unrealizedPnlRate;

  /// USD 자산 평가에 쓴 환율.
  final String? exchangeRate;

  final DateTime? asOf;

  factory AccountSummary.fromJson(Map<String, Object?> json) => AccountSummary(
    accountId: json.requireInt('accountId'),
    roundNo: json.requireInt('roundNo'),
    initialCash: json.stringOrNull('initialCash'),
    cashBalance: json.stringOrNull('cashBalance'),
    stockValue: json.stringOrNull('stockValue'),
    totalAsset: json.stringOrNull('totalAsset'),
    unrealizedPnl: json.stringOrNull('unrealizedPnl'),
    unrealizedPnlRate: json.stringOrNull('unrealizedPnlRate'),
    exchangeRate: json.stringOrNull('exchangeRate'),
    asOf: json.dateTimeOrNull('asOf'),
  );
}
