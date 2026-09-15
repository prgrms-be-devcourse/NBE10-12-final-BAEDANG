import 'package:dio/dio.dart';

/// 오류가 난 지점. 화면 분기와 재시도 판단에만 쓴다.
enum ApiErrorKind {
  /// 서버가 `code`/`message`로 거절한 도메인 오류.
  domain,

  /// 연결 실패(오프라인, DNS, TLS).
  network,

  /// 응답 시간 초과.
  timeout,

  /// 클라이언트가 취소.
  cancelled,

  /// 서버 장애(5xx) 또는 프록시가 돌려준 비정형 응답.
  server,

  /// 그 밖의 예외와 응답 형식 불일치.
  unexpected,
}

/// 서버가 내려주는 오류 코드. 문자열 비교는 여기 상수만 쓴다.
abstract final class ApiErrorCodes {
  static const String invalidInput = 'INVALID_INPUT';
  static const String loginFailed = 'LOGIN_FAILED';
  static const String unauthorized = 'UNAUTHORIZED';
  static const String tokenExpired = 'TOKEN_EXPIRED';
  static const String invalidToken = 'INVALID_TOKEN';
  static const String sessionRevoked = 'SESSION_REVOKED';
  static const String refreshTokenReused = 'REFRESH_TOKEN_REUSED';
  static const String emailDuplicated = 'EMAIL_DUPLICATED';
  static const String nicknameDuplicated = 'NICKNAME_DUPLICATED';
  static const String userNotFound = 'USER_NOT_FOUND';
}

/// 사용자에게 보여줄 문구와, 화면이 분기할 코드/상태를 함께 담는다.
///
/// 토큰·헤더·요청 본문은 담지 않는다.
class ApiError {
  const ApiError({
    required this.kind,
    required this.message,
    this.code,
    this.statusCode,
    this.data,
  });

  /// 네트워크 연결 실패.
  static const ApiError networkFailure = ApiError(
    kind: ApiErrorKind.network,
    message: '네트워크에 연결할 수 없어요. 연결 상태를 확인하고 다시 시도해주세요',
  );

  /// 응답 시간 초과. 서버가 요청을 처리하지 않았다는 증거는 아니다.
  static const ApiError timeoutFailure = ApiError(
    kind: ApiErrorKind.timeout,
    message: '응답이 늦어지고 있어요. 잠시 후 다시 시도해주세요',
  );

  /// 사용자가 화면을 떠나 취소한 요청.
  static const ApiError cancelled = ApiError(
    kind: ApiErrorKind.cancelled,
    message: '요청이 취소됐어요',
  );

  /// ErrorResponse 형태가 아닌 응답이라 해석할 수 없을 때.
  static const ApiError unexpectedResponse = ApiError(
    kind: ApiErrorKind.unexpected,
    message: '서버 응답을 이해할 수 없어요. 잠시 후 다시 시도해주세요',
  );

  /// 로그아웃/새 로그인이 끼어들어 결과를 버릴 때.
  static const ApiError sessionSuperseded = ApiError(
    kind: ApiErrorKind.cancelled,
    message: '로그인 상태가 바뀌어 요청을 취소했어요',
  );

  final ApiErrorKind kind;

  /// 사용자에게 그대로 보여줄 문구. 서버가 준 message를 우선한다.
  final String message;

  /// 서버 `code`. 서버가 코드를 주지 않으면 null.
  final String? code;

  final int? statusCode;

  /// 서버 `data`(필드 오류, 부족 금액 등). 계약된 값만 보존한다.
  final Map<String, Object?>? data;

  /// 세션이 확정적으로 무효일 때만 true. 통신 실패·단순 UNAUTHORIZED는 false다.
  /// stateful 세션 도입으로 SESSION_REVOKED·REFRESH_TOKEN_REUSED가 추가됐다.
  bool get isSessionInvalid =>
      isTokenExpired ||
      isInvalidToken ||
      code == ApiErrorCodes.sessionRevoked ||
      code == ApiErrorCodes.refreshTokenReused;

  bool get isTokenExpired => code == ApiErrorCodes.tokenExpired;

  bool get isInvalidToken => code == ApiErrorCodes.invalidToken;

  bool get isUnauthorized => code == ApiErrorCodes.unauthorized;

  bool get isLoginFailed => code == ApiErrorCodes.loginFailed;

  /// 서버가 필드별 검증 오류를 내려준 경우.
  bool get isFieldError =>
      kind == ApiErrorKind.domain &&
      code == ApiErrorCodes.invalidInput &&
      data != null;

  /// 통신 문제라 잠시 뒤 다시 시도할 가치가 있는 오류.
  bool get isRetryable =>
      kind == ApiErrorKind.network || kind == ApiErrorKind.timeout;

  /// 응답 본문에서 서버 오류 코드만 뽑는다. ErrorResponse 형태가 아니면 null.
  static String? codeOf(Object? body) {
    if (body is Map) {
      final code = body['code'];
      if (code is String) return code;
    }
    return null;
  }

  /// HTTP 응답을 사용자 문구와 계약 정보로 바꾼다.
  static ApiError fromResponse(Response<dynamic> response) {
    final status = response.statusCode;
    final body = response.data;
    if (body is Map) {
      final message = body['message'];
      if (message is String && message.isNotEmpty) {
        return ApiError(
          kind: (status ?? 0) >= 500
              ? ApiErrorKind.server
              : ApiErrorKind.domain,
          message: message,
          code: codeOf(body),
          statusCode: status,
          data: _dataOf(body['data']),
        );
      }
    }
    // 프록시 HTML, 빈 본문 등 ErrorResponse가 아닌 응답.
    return ApiError(
      kind: (status ?? 0) >= 500
          ? ApiErrorKind.server
          : ApiErrorKind.unexpected,
      message: _fallbackMessage(status),
      statusCode: status,
    );
  }

  /// Dio 예외를 사용자 문구가 있는 오류로 바꾼다.
  static ApiError fromDioException(DioException exception) {
    // 인터셉터가 갱신 실패 등 이미 해석된 오류를 실어 보낸 경우 그대로 쓴다.
    final nested = exception.error;
    if (nested is ApiException) return nested.error;
    final response = exception.response;
    if (response != null) return fromResponse(response);
    switch (exception.type) {
      case DioExceptionType.connectionTimeout:
      case DioExceptionType.sendTimeout:
      case DioExceptionType.receiveTimeout:
      case DioExceptionType.transformTimeout:
        return timeoutFailure;
      case DioExceptionType.cancel:
        return cancelled;
      case DioExceptionType.connectionError:
      case DioExceptionType.badCertificate:
        return networkFailure;
      case DioExceptionType.unknown:
        // 본문이 JSON이 아니어서 해석에 실패한 경우는 통신 문제와 구분한다.
        return exception.error is FormatException
            ? unexpectedResponse
            : networkFailure;
      case DioExceptionType.badResponse:
        return unexpectedResponse;
    }
  }

  static Map<String, Object?>? _dataOf(Object? raw) {
    if (raw is! Map) return null;
    return raw.map((key, value) => MapEntry(key.toString(), value));
  }

  static String _fallbackMessage(int? status) {
    if (status == null) return unexpectedResponse.message;
    if (status >= 500) return '일시적인 오류가 발생했어요. 잠시 후 다시 시도해주세요';
    return switch (status) {
      400 => '입력값이 올바르지 않아요',
      401 => '로그인이 필요해요',
      403 => '접근 권한이 없어요',
      404 => '요청한 정보를 찾을 수 없어요',
      409 => '요청이 현재 상태와 충돌해요',
      429 => '요청이 너무 많아요. 잠시 후 다시 시도해주세요',
      _ => '요청을 처리하지 못했어요 (HTTP $status)',
    };
  }

  @override
  String toString() =>
      'ApiError(${kind.name}, ${code ?? '-'}, ${statusCode ?? '-'})';
}

class ApiException implements Exception {
  const ApiException(this.error);

  final ApiError error;

  String get message => error.message;

  /// refresh token이 확정적으로 무효인지. 통신 실패는 false다.
  bool get isSessionInvalid => error.isSessionInvalid;

  /// 민감 정보를 넣지 않는다.
  @override
  String toString() =>
      'ApiException(${error.statusCode ?? '-'} ${error.code ?? error.kind.name})';
}
