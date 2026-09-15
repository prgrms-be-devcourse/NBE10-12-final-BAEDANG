import 'json_read.dart';

/// GET /users/me 응답이자 가입/로그인 응답의 프로필 부분.
class UserProfile {
  const UserProfile({
    required this.userId,
    required this.email,
    required this.nickname,
  });

  final int userId;
  final String email;
  final String nickname;

  factory UserProfile.fromJson(Map<String, Object?> json) => UserProfile(
    userId: json.requireInt('userId'),
    email: json.requireString('email'),
    nickname: json.requireString('nickname'),
  );
}
