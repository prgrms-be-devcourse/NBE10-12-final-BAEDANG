import 'package:dio/dio.dart';

import '../models/auth_response.dart';
import '../models/user_profile.dart';
import 'api_client.dart';
import 'auth_requirement.dart';

/// 인증·프로필 API.
///
/// 비밀번호 재설정은 서버 계약이 아직 확정되지 않아 넣지 않았다.
class AuthApi {
  AuthApi(this._client);

  static const String _signUp = 'auth/signup';
  static const String _logIn = 'auth/login';
  static const String _logOut = 'auth/logout';
  static const String _me = 'users/me';

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

  /// 서버는 stateless라 토큰을 실제로 폐기하지 않는다. 통신 실패가 로컬 로그아웃을
  /// 막지 않게 호출부가 실패를 무시할 수 있다. 로그아웃 직후에는 메모리 토큰이 이미
  /// 지워져 있으므로, 사용한 access token을 [accessToken]으로 넘겨받는다.
  Future<void> logOut({String? accessToken}) => _client.postVoid(
    _logOut,
    auth: AuthRequirement.required,
    bearerToken: accessToken,
  );
}
