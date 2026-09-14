import 'package:dio/dio.dart';

import '../auth/token_manager.dart';
import 'api_config.dart';
import 'api_error.dart';
import 'auth_interceptor.dart';
import 'auth_requirement.dart';

/// 얇은 Dio 래퍼.
///
/// 요청마다 인증 의도를 밝히고, 실패는 사용자 문구가 있는 [ApiException]으로 바꾼다.
/// 요청 본문·헤더·토큰을 로그에 남기지 않는다.
class ApiClient {
  ApiClient({
    required this.config,
    required this.tokens,
    Dio? dio,
    Dio? refreshDio,
  }) : _dio = dio ?? Dio(_baseOptions(config)),
       _refreshDio = refreshDio ?? Dio(_baseOptions(config)) {
    _dio.interceptors.add(
      AuthInterceptor(
        tokens: tokens,
        refreshAccessToken: refreshAccessToken,
        retry: (options) => _dio.fetch<dynamic>(options),
      ),
    );
  }

  final ApiConfig config;
  final TokenManager tokens;

  final Dio _dio;
  final Dio _refreshDio;

  Dio get dio => _dio;

  /// refresh 전용. 인증 인터셉터를 거치지 않아 갱신 실패가 재귀 갱신을 만들지 않는다.
  Dio get refreshDio => _refreshDio;

  Future<String>? _refreshInFlight;

  /// access token 갱신. 여러 요청이 동시에 만료되면 하나의 Future를 공유한다.
  Future<String> refreshAccessToken() {
    final inFlight = _refreshInFlight;
    if (inFlight != null) return inFlight;
    final future = _performRefresh();
    _refreshInFlight = future;
    return future;
  }

  Future<String> _performRefresh() async {
    try {
      final generation = tokens.generation;
      final refreshToken = tokens.refreshToken;
      if (refreshToken == null) {
        throw const ApiException(
          ApiError(
            kind: ApiErrorKind.domain,
            code: ApiErrorCodes.unauthorized,
            message: '로그인이 필요해요',
          ),
        );
      }
      final Response<dynamic> response;
      try {
        response = await _refreshDio.post<dynamic>(
          'auth/refresh',
          data: {'refreshToken': refreshToken},
        );
      } on DioException catch (error) {
        throw ApiException(ApiError.fromDioException(error));
      }
      final body = response.data;
      final accessToken = body is Map ? body['accessToken'] : null;
      if (accessToken is! String || accessToken.isEmpty) {
        // ErrorResponse 형태가 아닌 응답. 통신 실패와 달리 세션 유지로 단정하지 않는다.
        throw const ApiException(ApiError.unexpectedResponse);
      }
      // 현재 refresh 응답에는 accessToken만 있다. 기존 refresh token은 그대로 둔다.
      final applied = await tokens.applyRefreshedAccessToken(
        accessToken,
        generation: generation,
      );
      if (!applied) {
        throw const ApiException(ApiError.sessionSuperseded);
      }
      return accessToken;
    } finally {
      _refreshInFlight = null;
    }
  }

  /// JSON 객체를 기대하는 GET.
  Future<Map<String, Object?>> getObject(
    String path, {
    AuthRequirement auth = AuthRequirement.public,
    Map<String, dynamic>? query,
    CancelToken? cancelToken,
  }) async {
    final response = await _request(
      'GET',
      path,
      auth: auth,
      query: query,
      cancelToken: cancelToken,
    );
    return _objectOf(response);
  }

  /// JSON 객체를 기대하는 POST.
  Future<Map<String, Object?>> postObject(
    String path, {
    Object? body,
    AuthRequirement auth = AuthRequirement.public,
    CancelToken? cancelToken,
    String? bearerToken,
  }) async {
    final response = await _request(
      'POST',
      path,
      auth: auth,
      body: body,
      cancelToken: cancelToken,
      bearerToken: bearerToken,
    );
    return _objectOf(response);
  }

  /// JSON 객체를 기대하는 PATCH(주문 취소 등).
  Future<Map<String, Object?>> patchObject(
    String path, {
    Object? body,
    AuthRequirement auth = AuthRequirement.public,
    CancelToken? cancelToken,
  }) async {
    final response = await _request(
      'PATCH',
      path,
      auth: auth,
      body: body,
      cancelToken: cancelToken,
    );
    return _objectOf(response);
  }

  /// 성공 본문이 비어 있을 수 있는 POST(로그아웃 등).
  Future<void> postVoid(
    String path, {
    Object? body,
    AuthRequirement auth = AuthRequirement.public,
    CancelToken? cancelToken,
    String? bearerToken,
  }) async {
    await _request(
      'POST',
      path,
      auth: auth,
      body: body,
      cancelToken: cancelToken,
      bearerToken: bearerToken,
    );
  }

  /// 성공 본문이 비어 있는 DELETE(찜 해제 등).
  Future<void> deleteVoid(
    String path, {
    AuthRequirement auth = AuthRequirement.public,
    CancelToken? cancelToken,
  }) async {
    await _request(
      'DELETE',
      path,
      auth: auth,
      cancelToken: cancelToken,
    );
  }

  /// DTO 파싱 실패(필수 필드 누락, 형식 불일치)를 사용자 문구가 있는 예외로 바꾼다.
  T decode<T>(T Function() parse) {
    try {
      return parse();
    } on ApiException {
      rethrow;
    } catch (_) {
      throw const ApiException(ApiError.unexpectedResponse);
    }
  }

  Future<Response<dynamic>> _request(
    String method,
    String path, {
    required AuthRequirement auth,
    Object? body,
    Map<String, dynamic>? query,
    CancelToken? cancelToken,
    String? bearerToken,
  }) async {
    final options = Options(
      method: method,
      headers: <String, dynamic>{
        if (bearerToken != null) 'Authorization': 'Bearer $bearerToken',
      },
      extra: <String, dynamic>{RequestMeta.authRequirement: auth},
    );
    try {
      return await _dio.request<dynamic>(
        path.startsWith('/') ? path.substring(1) : path,
        data: body,
        queryParameters: query,
        cancelToken: cancelToken,
        options: options,
      );
    } on DioException catch (error) {
      throw ApiException(ApiError.fromDioException(error));
    }
  }

  Map<String, Object?> _objectOf(Response<dynamic> response) {
    final body = response.data;
    if (body is Map) {
      return body.map((key, value) => MapEntry(key.toString(), value));
    }
    throw const ApiException(ApiError.unexpectedResponse);
  }

  static BaseOptions _baseOptions(ApiConfig config) => BaseOptions(
    baseUrl: config.baseUrl,
    connectTimeout: config.connectTimeout,
    receiveTimeout: config.receiveTimeout,
    sendTimeout: config.sendTimeout,
    responseType: ResponseType.json,
    // 인증서 검증 우회와 본문 로깅 인터셉터는 넣지 않는다.
  );
}
