import 'package:dio/dio.dart';

import '../auth/token_manager.dart';
import '../auth/token_storage.dart';
import 'api_error.dart';
import 'auth_requirement.dart';

/// 진행 중인 refresh를 공유하는 함수.
typedef AccessTokenRefresh = Future<String> Function();

/// 새 토큰으로 원래 요청을 한 번 다시 보내는 함수.
typedef RequestRetrier =
    Future<Response<dynamic>> Function(RequestOptions options);

/// 인증 헤더 부착, 만료 갱신, 인증 재시도 1회를 담당한다.
///
/// 토큰·본문을 로그에 남기지 않는다. refresh 요청은 이 인터셉터를 거치지 않는
/// 별도 Dio로 보내므로 갱신 실패가 재귀 갱신을 만들지 않는다.
class AuthInterceptor extends Interceptor {
  AuthInterceptor({
    required TokenManager tokens,
    required AccessTokenRefresh refreshAccessToken,
    required RequestRetrier retry,
  }) : _tokens = tokens,
       _refreshAccessToken = refreshAccessToken,
       _retry = retry;

  static const String _authorization = 'Authorization';

  final TokenManager _tokens;
  final AccessTokenRefresh _refreshAccessToken;
  final RequestRetrier _retry;

  @override
  void onRequest(RequestOptions options, RequestInterceptorHandler handler) {
    final requirement = _requirementOf(options);
    if (requirement == AuthRequirement.public) {
      // 공개 요청에 만료된 Authorization을 붙이면 필터가 401을 낸다.
      options.headers.remove(_authorization);
      _rememberSent(options, null);
      return handler.next(options);
    }
    // 호출부가 명시한 토큰(로그아웃 등)이 있으면 그것을 우선한다.
    final token =
        _bearerOf(options.headers[_authorization]) ?? _tokens.accessToken;
    if (token == null) {
      options.headers.remove(_authorization);
      _rememberSent(options, null);
      return handler.next(options);
    }
    options.headers[_authorization] = 'Bearer $token';
    _rememberSent(options, token);
    return handler.next(options);
  }

  @override
  void onError(DioException err, ErrorInterceptorHandler handler) async {
    final options = err.requestOptions;
    final response = err.response;
    if (_requirementOf(options) == AuthRequirement.public ||
        response == null ||
        response.statusCode != 401) {
      return handler.next(err);
    }
    if (options.extra[RequestMeta.retriedAfterRefresh] == true) {
      // 자동 인증 재시도는 요청당 1회다.
      return handler.next(err);
    }
    final generation = options.extra[RequestMeta.sessionGeneration];
    if (generation is int && generation != _tokens.generation) {
      // 로그아웃/새 로그인 뒤 도착한 응답. 이전 세대를 되살리지 않는다.
      return handler.next(err);
    }
    final sentToken = options.extra[RequestMeta.sentAccessToken];
    switch (ApiError.codeOf(response.data)) {
      case ApiErrorCodes.tokenExpired:
        return _retryOnceAfterRefresh(
          err,
          sentToken is String ? sentToken : null,
          handler,
        );
      case ApiErrorCodes.invalidToken:
        if (sentToken is String && sentToken == _tokens.accessToken) {
          // 현재 세션의 access token이 확정적으로 무효 → 갱신 반복 없이 정리.
          await _invalidateSession();
        }
        return handler.next(err);
      default:
        // LOGIN_FAILED, UNAUTHORIZED 등은 만료가 아니므로 갱신하지 않는다.
        return handler.next(err);
    }
  }

  Future<void> _retryOnceAfterRefresh(
    DioException err,
    String? sentToken,
    ErrorInterceptorHandler handler,
  ) async {
    final options = err.requestOptions;
    if (sentToken == null) {
      // 토큰 없이 보낸 요청은 갱신 대상이 아니다.
      return handler.next(err);
    }
    final String token;
    if (sentToken != _tokens.accessToken) {
      // 다른 요청이 이미 갱신했다. refresh 없이 새 토큰으로 1회만 다시 보낸다.
      final current = _tokens.accessToken;
      if (current == null) return handler.next(err);
      token = current;
    } else {
      try {
        token = await _refreshAccessToken();
      } on ApiException catch (refreshError) {
        if (refreshError.isSessionInvalid &&
            options.extra[RequestMeta.sessionGeneration] ==
                _tokens.generation) {
          // refresh token 자체가 무효 확정 → 재로그인이 필요하다.
          // 갱신을 기다리는 사이 로그아웃/새 로그인이 있었다면 이전 세대의
          // 실패가 새 세션을 무효화하지 않는다.
          await _invalidateSession();
        }
        // 일시적 갱신 실패를 원본 만료 오류로 바꿔치기하지 않는다. 호출부가
        // 토큰 무효와 통신/서버 장애를 구분할 수 있도록 실제 원인을 전한다.
        return handler.next(err.copyWith(error: refreshError));
      } on StorageException {
        return handler.next(err);
      }
    }
    if (options.extra[RequestMeta.sessionGeneration] != _tokens.generation) {
      // 갱신을 기다리는 사이 로그아웃/새 로그인이 있었다.
      return handler.next(err);
    }
    try {
      return handler.resolve(await _retry(_withToken(options, token)));
    } on DioException catch (retryError) {
      return handler.next(retryError);
    }
  }

  RequestOptions _withToken(RequestOptions options, String token) {
    return options.copyWith(
      headers: Map<String, dynamic>.of(options.headers)
        ..[_authorization] = 'Bearer $token',
      extra: Map<String, dynamic>.of(options.extra)
        ..[RequestMeta.retriedAfterRefresh] = true
        ..[RequestMeta.sentAccessToken] = token,
    );
  }

  Future<void> _invalidateSession() async {
    try {
      await _tokens.invalidateSession();
    } on StorageException {
      // 세션은 이미 끝났다. 저장소 정리는 다음 시작의 refresh 실패 경로에서 다시 시도된다.
    }
  }

  void _rememberSent(RequestOptions options, String? token) {
    options.extra[RequestMeta.sessionGeneration] = _tokens.generation;
    if (token == null) {
      options.extra.remove(RequestMeta.sentAccessToken);
    } else {
      options.extra[RequestMeta.sentAccessToken] = token;
    }
  }

  /// 기본값은 public이다. 의도를 밝히지 않은 요청에 토큰을 실어 보내지 않는다.
  AuthRequirement _requirementOf(RequestOptions options) {
    final raw = options.extra[RequestMeta.authRequirement];
    return raw is AuthRequirement ? raw : AuthRequirement.public;
  }

  String? _bearerOf(Object? header) {
    if (header is! String) return null;
    const prefix = 'Bearer ';
    if (!header.startsWith(prefix)) return null;
    final token = header.substring(prefix.length);
    return token.isEmpty ? null : token;
  }
}
