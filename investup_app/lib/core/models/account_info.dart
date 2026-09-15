import 'json_read.dart';

/// 가입/로그인 응답의 계좌 요약(1회차 계좌).
///
/// 금액은 서버가 보낸 문자열 그대로 보관한다(표시 정밀도 보존).
class AccountInfo {
  const AccountInfo({
    required this.accountId,
    required this.roundNo,
    required this.initialCash,
    required this.cashBalance,
  });

  final int accountId;
  final int roundNo;
  final String initialCash;
  final String cashBalance;

  factory AccountInfo.fromJson(Map<String, Object?> json) => AccountInfo(
    accountId: json.requireInt('accountId'),
    roundNo: json.requireInt('roundNo'),
    initialCash: json.requireString('initialCash'),
    cashBalance: json.requireString('cashBalance'),
  );
}
