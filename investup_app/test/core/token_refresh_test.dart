import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:investup_app/core/api/api_error.dart';

import 'fakes/fake_http_adapter.dart';
import 'fakes/fixtures.dart';
import 'fakes/test_harness.dart';

/// 만료된 access token만 거절하고, 갱신된 토큰은 통과시키는 서버 흉내.
FakeHttpAdapter expiringServer({
  required String expiredToken,
  required String refreshedAccessToken,
  Duration delay = const Duration(milliseconds: 2),
}) {
  return FakeHttpAdapter((options) async {
    final path = options.uri.path;
    if (path.endsWith('/auth/refresh')) {
      return FakeResponse.ok(accessTokenJson(refreshedAccessToken));
    }
    if (bearerTokenOf(options) == expiredToken) {
      return FakeResponse.error(
        401,
        code: ApiErrorCodes.tokenExpired,
        message: '로그인이 만료됐어요. 다시 로그인해주세요',
      );
    }
    return FakeResponse.ok(profileJson());
  }, delay: delay);
}

void main() {
  test('동시에 만료된 요청 여러 개도 refresh는 1회만 수행한다', () async {
    final harness = TestHarness(
      adapter: expiringServer(
        expiredToken: 'expired-access',
        refreshedAccessToken: 'fresh-access',
      ),
    );
    await harness.signIn(
      accessToken: 'expired-access',
      refreshToken: 'stored-refresh',
    );

    final results = await Future.wait(<Future<Object?>>[
      harness.auth.getMe(),
      harness.auth.getMe(),
      harness.auth.getMe(),
    ]);

    expect(results, hasLength(3));
    expect(harness.countTo('/auth/refresh'), 1);
    // 요청마다 인증 재시도는 1회뿐이므로 프로필 조회는 총 6회다.
    expect(harness.countTo('/users/me'), 6);
    expect(harness.tokens.accessToken, 'fresh-access');
  });

  test(
    'refresh 요청은 저장된 refresh token을 본문에 담고 Authorization을 보내지 않는다',
    () async {
      final harness = TestHarness(
        adapter: expiringServer(
          expiredToken: 'expired-access',
          refreshedAccessToken: 'fresh-access',
        ),
      );
      await harness.signIn(
        accessToken: 'expired-access',
        refreshToken: 'stored-refresh',
      );

      await harness.auth.getMe();

      final refresh = harness.requestsTo('/auth/refresh').single;
      expect(refresh.data, <String, dynamic>{'refreshToken': 'stored-refresh'});
      expect(refresh.headers.containsKey('Authorization'), isFalse);
    },
  );

  test('refresh 응답에는 accessToken만 있으므로 저장된 refresh token을 유지한다', () async {
    final harness = TestHarness(
      adapter: expiringServer(
        expiredToken: 'expired-access',
        refreshedAccessToken: 'fresh-access',
      ),
    );
    await harness.signIn(
      accessToken: 'expired-access',
      refreshToken: 'stored-refresh',
    );

    await harness.auth.getMe();

    expect(harness.tokens.refreshToken, 'stored-refresh');
    expect(harness.storage.stored, 'stored-refresh');
    expect(harness.storage.writeCount, 1);
  });

  test('재시도한 요청도 만료면 자동 재시도를 반복하지 않는다', () async {
    // 항상 만료로 답하는 서버. 갱신은 되지만 재시도 요청도 만료다.
    final harness = TestHarness(
      adapter: FakeHttpAdapter((options) async {
        if (options.uri.path.endsWith('/auth/refresh')) {
          return FakeResponse.ok(accessTokenJson('fresh-access'));
        }
        return FakeResponse.error(
          401,
          code: ApiErrorCodes.tokenExpired,
          message: '로그인이 만료됐어요. 다시 로그인해주세요',
        );
      }),
    );
    await harness.signIn(
      accessToken: 'expired-access',
      refreshToken: 'stored-refresh',
    );

    await expectLater(
      harness.auth.getMe(),
      throwsA(
        isA<ApiException>().having(
          (e) => e.error.isTokenExpired,
          'isTokenExpired',
          isTrue,
        ),
      ),
    );

    expect(harness.countTo('/auth/refresh'), 1);
    expect(harness.countTo('/users/me'), 2);
  });

  test('refresh가 INVALID_TOKEN으로 거절되면 세션을 정리한다', () async {
    final harness = TestHarness(
      adapter: FakeHttpAdapter((options) async {
        if (options.uri.path.endsWith('/auth/refresh')) {
          return FakeResponse.error(
            401,
            code: ApiErrorCodes.invalidToken,
            message: '인증 정보가 올바르지 않아요',
          );
        }
        return FakeResponse.error(
          401,
          code: ApiErrorCodes.tokenExpired,
          message: '로그인이 만료됐어요. 다시 로그인해주세요',
        );
      }),
    );
    await harness.signIn(
      accessToken: 'expired-access',
      refreshToken: 'stale-refresh',
    );

    await expectLater(harness.auth.getMe(), throwsA(isA<ApiException>()));

    expect(harness.countTo('/auth/refresh'), 1);
    expect(harness.tokens.hasSession, isFalse);
    expect(harness.tokens.accessToken, isNull);
    expect(harness.storage.stored, isNull);
  });

  test('refresh가 통신 실패하면 저장된 refresh token을 지우지 않는다', () async {
    final harness = TestHarness(
      adapter: FakeHttpAdapter((options) async {
        if (options.uri.path.endsWith('/auth/refresh')) {
          throw DioException.connectionError(
            requestOptions: options,
            reason: 'offline',
          );
        }
        return FakeResponse.error(
          401,
          code: ApiErrorCodes.tokenExpired,
          message: '로그인이 만료됐어요. 다시 로그인해주세요',
        );
      }),
    );
    await harness.signIn(
      accessToken: 'expired-access',
      refreshToken: 'stored-refresh',
    );

    await expectLater(harness.auth.getMe(), throwsA(isA<ApiException>()));

    expect(harness.tokens.hasSession, isTrue);
    expect(harness.tokens.refreshToken, 'stored-refresh');
    expect(harness.storage.stored, 'stored-refresh');
  });

  test('현재 토큰에 INVALID_TOKEN이 오면 갱신 없이 세션을 정리한다', () async {
    final harness = TestHarness(
      adapter: FakeHttpAdapter(
        (_) async => FakeResponse.error(
          401,
          code: ApiErrorCodes.invalidToken,
          message: '인증 정보가 올바르지 않아요',
        ),
      ),
    );
    await harness.signIn(
      accessToken: 'broken-access',
      refreshToken: 'stored-refresh',
    );

    await expectLater(harness.auth.getMe(), throwsA(isA<ApiException>()));

    expect(harness.countTo('/auth/refresh'), 0);
    expect(harness.tokens.hasSession, isFalse);
    expect(harness.storage.stored, isNull);
  });

  test('UNAUTHORIZED와 LOGIN_FAILED는 만료가 아니므로 갱신하지 않는다', () async {
    final harness = TestHarness(
      adapter: FakeHttpAdapter(
        (_) async => FakeResponse.error(
          401,
          code: ApiErrorCodes.unauthorized,
          message: '로그인이 필요해요',
        ),
      ),
    );
    await harness.signIn(
      accessToken: 'some-access',
      refreshToken: 'stored-refresh',
    );

    await expectLater(harness.auth.getMe(), throwsA(isA<ApiException>()));

    expect(harness.countTo('/auth/refresh'), 0);
    expect(harness.tokens.hasSession, isTrue);
    expect(harness.storage.stored, 'stored-refresh');
  });

  test('로그아웃 뒤 도착한 만료 응답은 이전 세션을 되살리지 않는다', () async {
    final adapter = FakeHttpAdapter((options) async {
      if (options.uri.path.endsWith('/auth/refresh')) {
        await Future<void>.delayed(const Duration(milliseconds: 30));
        return FakeResponse.ok(accessTokenJson('late-access'));
      }
      return FakeResponse.error(
        401,
        code: ApiErrorCodes.tokenExpired,
        message: '로그인이 만료됐어요. 다시 로그인해주세요',
      );
    });
    final harness = TestHarness(adapter: adapter);
    await harness.signIn(
      accessToken: 'expired-access',
      refreshToken: 'stored-refresh',
    );

    final pending = harness.auth.getMe();
    await Future<void>.delayed(const Duration(milliseconds: 5));
    harness.tokens.invalidate();

    await expectLater(pending, throwsA(isA<ApiException>()));

    expect(harness.tokens.accessToken, isNull);
    expect(harness.tokens.refreshToken, isNull);
  });
}
