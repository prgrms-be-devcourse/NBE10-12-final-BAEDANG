import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:investup_app/core/api/api_error.dart';

RequestOptions _options() => RequestOptions(path: '/api/users/me');

void main() {
  test('ErrorResponse 본문의 code/message/data를 보존한다', () {
    final error = ApiError.fromResponse(
      Response<dynamic>(
        requestOptions: _options(),
        statusCode: 422,
        data: <String, Object?>{
          'code': 'INSUFFICIENT_CASH',
          'message': '주문가능금액이 부족해요',
          'timestamp': '2026-09-15T10:00:00+09:00',
          'data': <String, Object?>{'available': '1200000'},
        },
      ),
    );

    expect(error.kind, ApiErrorKind.domain);
    expect(error.code, 'INSUFFICIENT_CASH');
    expect(error.message, '주문가능금액이 부족해요');
    expect(error.statusCode, 422);
    expect(error.data?['available'], '1200000');
  });

  test('ErrorResponse가 아닌 응답도 크래시 없이 문구를 만든다', () {
    final server = ApiError.fromResponse(
      Response<dynamic>(
        requestOptions: _options(),
        statusCode: 502,
        data: '<html>bad gateway</html>',
      ),
    );
    expect(server.kind, ApiErrorKind.server);
    expect(server.code, isNull);
    expect(server.message, '일시적인 오류가 발생했어요. 잠시 후 다시 시도해주세요');

    final unauthorized = ApiError.fromResponse(
      Response<dynamic>(requestOptions: _options(), statusCode: 401),
    );
    expect(unauthorized.statusCode, 401);
    expect(unauthorized.message, '로그인이 필요해요');
    expect(unauthorized.isSessionInvalid, isFalse);
  });

  test('Dio 예외 종류를 timeout/network/cancelled로 구분한다', () {
    ApiError from(DioExceptionType type) => ApiError.fromDioException(
      DioException(requestOptions: _options(), type: type),
    );

    expect(from(DioExceptionType.receiveTimeout).kind, ApiErrorKind.timeout);
    expect(from(DioExceptionType.sendTimeout).kind, ApiErrorKind.timeout);
    expect(from(DioExceptionType.connectionTimeout).kind, ApiErrorKind.timeout);
    expect(from(DioExceptionType.connectionError).kind, ApiErrorKind.network);
    expect(from(DioExceptionType.badCertificate).kind, ApiErrorKind.network);
    expect(from(DioExceptionType.cancel).kind, ApiErrorKind.cancelled);
  });

  test('세션 무효는 만료·폐기·재사용 코드로만 판정한다', () {
    const expired = ApiError(
      kind: ApiErrorKind.domain,
      code: ApiErrorCodes.tokenExpired,
      message: '만료',
    );
    const invalid = ApiError(
      kind: ApiErrorKind.domain,
      code: ApiErrorCodes.invalidToken,
      message: '무효',
    );
    const revoked = ApiError(
      kind: ApiErrorKind.domain,
      code: ApiErrorCodes.sessionRevoked,
      message: '폐기',
    );
    const reused = ApiError(
      kind: ApiErrorKind.domain,
      code: ApiErrorCodes.refreshTokenReused,
      message: '재사용',
    );
    const unauthorized = ApiError(
      kind: ApiErrorKind.domain,
      code: ApiErrorCodes.unauthorized,
      message: '로그인 필요',
    );

    expect(expired.isSessionInvalid, isTrue);
    expect(invalid.isSessionInvalid, isTrue);
    expect(revoked.isSessionInvalid, isTrue);
    expect(reused.isSessionInvalid, isTrue);
    expect(unauthorized.isSessionInvalid, isFalse);
    expect(ApiError.timeoutFailure.isSessionInvalid, isFalse);
  });

  test('codeOf는 ErrorResponse 형태일 때만 코드를 뽑는다', () {
    expect(
      ApiError.codeOf(<String, Object?>{'code': 'LOGIN_FAILED'}),
      'LOGIN_FAILED',
    );
    expect(ApiError.codeOf(<String, Object?>{'code': 401}), isNull);
    expect(ApiError.codeOf('<html>'), isNull);
    expect(ApiError.codeOf(null), isNull);
  });
}
