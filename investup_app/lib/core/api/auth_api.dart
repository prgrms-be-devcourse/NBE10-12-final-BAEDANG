import 'package:dio/dio.dart';

import '../models/auth_response.dart';
import '../models/user_profile.dart';
import 'api_client.dart';
import 'auth_requirement.dart';

/// 인증·프로필 API.
class AuthApi {
  AuthApi(this._client);

  static const String _signUp = 'auth/signup';
  static const String _logIn = 'auth/login';
  static const String _logOut = 'auth/logout';
  static const String _me = 'users/me';
  static const String _passwordForgot = 'auth/password/forgot';
  static const String _passwordReset = 'auth/password/reset';

  final ApiClient _client;

  /// 201. 가입과 동시에 1회차 계좌가 만들어진다.
  Future<AuthResponse> signUp({
    required String email,
    required String password,
    required String nickname,
  }) async {
    final json = await _client.postObject(
      _signUp,
      body: {'email': email, 'password': password, 'nickname': nickname},
      auth: AuthRequirement.public,
    );
    return _client.decode(() => AuthResponse.fromJson(json));
  }

  Future<AuthResponse> logIn({
    required String email,
    required String password,
  }) async {
    final json = await _client.postObject(
      _logIn,
      body: {'email': email, 'password': password},
      auth: AuthRequirement.public,
    );
    return _client.decode(() => AuthResponse.fromJson(json));
  }

  /// 로그인한 사용자의 프로필. 인증 상태 복원에 쓴다.
  Future<UserProfile> getMe({CancelToken? cancelToken}) async {
    final json = await _client.getObject(
      _me,
      auth: AuthRequirement.required,
      cancelToken: cancelToken,
    );
    return _client.decode(() => UserProfile.fromJson(json));
  }

  /// 서버가 stateful 세션을 폐기한다 — 본문의 refresh token이 필수다.
  /// 통신 실패가 로컬 로그아웃을 막지 않게 호출부가 실패를 무시할 수 있다.
  /// 호출부는 메모리 토큰을 지우기 전에 refresh token을 넘겨준다.
  Future<void> logOut({required String refreshToken}) => _client.postVoid(
    _logOut,
    body: {'refreshToken': refreshToken},
    auth: AuthRequirement.public,
  );

  /// 비밀번호 재설정 메일 요청. 계정 존재 여부와 무관하게 200을 돌려준다.
  Future<void> forgotPassword({required String email}) => _client.postVoid(
    _passwordForgot,
    body: {'email': email},
    auth: AuthRequirement.public,
  );

  /// 재설정 토큰으로 새 비밀번호를 등록한다.
  Future<void> resetPassword({
    required String token,
    required String newPassword,
  }) => _client.postVoid(
    _passwordReset,
    body: {'token': token, 'newPassword': newPassword},
    auth: AuthRequirement.public,
  );

  /// 닉네임 변경. 중복이면 NICKNAME_DUPLICATED.
  Future<UserProfile> updateNickname({required String nickname}) async {
    final json = await _client.patchObject(
      _me,
      body: {'nickname': nickname},
      auth: AuthRequirement.required,
    );
    return _client.decode(() => UserProfile.fromJson(json));
  }

  /// 비밀번호 변경. 현재 비밀번호가 틀리면 INVALID_PASSWORD.
  Future<UserProfile> changePassword({
    required String currentPassword,
    required String newPassword,
  }) async {
    final json = await _client.putObject(
      'users/me/password',
      body: {'currentPassword': currentPassword, 'newPassword': newPassword},
      auth: AuthRequirement.required,
    );
    return _client.decode(() => UserProfile.fromJson(json));
  }

  /// 회원 탈퇴. 상태만 WITHDRAWN·CLOSED로 바뀌고 stateless 토큰은 살아있으니
  /// 호출부가 로컬 로그인 상태를 반드시 지워야 한다.
  Future<void> withdraw({required String currentPassword}) =>
      _client.deleteVoid(
        _me,
        body: {'currentPassword': currentPassword},
        auth: AuthRequirement.required,
      );
}
