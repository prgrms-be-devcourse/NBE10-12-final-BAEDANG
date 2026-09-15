import 'account_info.dart';
import 'json_read.dart';
import 'user_profile.dart';

/// POST /auth/signup(201), POST /auth/login(200) 응답.
///
/// 서버는 userId/email/nickname을 평탄하게 내려주고 account만 중첩한다.
/// user 중첩 객체로 파싱하지 않는다.
class AuthResponse {
  const AuthResponse({
    required this.profile,
    required this.accessToken,
    required this.refreshToken,
    required this.account,
  });

  final UserProfile profile;
  final String accessToken;
  final String refreshToken;
  final AccountInfo account;

  factory AuthResponse.fromJson(Map<String, Object?> json) {
    final account = json.objectOrNull('account');
    if (account == null) throw const FormatException('account가 없어요');
    return AuthResponse(
      profile: UserProfile.fromJson(json),
      accessToken: json.requireString('accessToken'),
      refreshToken: json.requireString('refreshToken'),
      account: AccountInfo.fromJson(account),
    );
  }
}
