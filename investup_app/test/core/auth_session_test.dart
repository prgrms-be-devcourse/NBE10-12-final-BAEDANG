import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:investup_app/core/api/api_error.dart';
import 'package:investup_app/core/auth/auth_session.dart';
import 'package:investup_app/core/auth/token_storage.dart';

import 'fakes/fake_http_adapter.dart';
import 'fakes/fake_token_storage.dart';
import 'fakes/fixtures.dart';
import 'fakes/test_harness.dart';

/// 정상 서버: refresh는 accessToken만, 나머지는 고정 응답.
FakeHttpAdapter healthyServer({
  String refreshedAccessToken = 'fresh-access',
  Duration refreshDelay = Duration.zero,
}) {
  return FakeHttpAdapter((options) async {
    final path = options.uri.path;
    if (path.endsWith('/auth/refresh')) {
      if (refreshDelay > Duration.zero) {
        await Future<void>.delayed(refreshDelay);
      }
      return FakeResponse.ok(accessTokenJson(refreshedAccessToken));
    }
    if (path.endsWith('/auth/login')) {
      return FakeResponse.ok(
        authJson(accessToken: 'login-access', refreshToken: 'login-refresh'),
      );
    }
    if (path.endsWith('/auth/logout')) {
      return FakeResponse.empty();
    }
    if (path.endsWith('/users/me')) {
      return FakeResponse.ok(profileJson());
    }
    if (path.endsWith('/accounts/me')) {
      return FakeResponse.ok(accountSummaryJson());
    }
    return FakeResponse.error(404, code: 'NOT_FOUND', message: '없는 경로');
  });
}

void main() {
  test('저장된 refresh token으로 세션을 복원하고 저장 토큰은 그대로 둔다', () async {
    final harness = TestHarness(
      adapter: healthyServer(),
      storage: FakeTokenStorage(initialRefreshToken: 'stored-refresh'),
    );

    await harness.session.restore();

    expect(harness.session.status, AuthStatus.authenticated);
    expect(harness.session.profile?.nickname, '사용자');
    expect(harness.session.account?.cashBalance, '49000000');
    expect(harness.tokens.accessToken, 'fresh-access');
    expect(harness.tokens.refreshToken, 'stored-refresh');
    expect(harness.storage.stored, 'stored-refresh');
    expect(harness.storage.writeCount, 0);
    expect(harness.countTo('/auth/refresh'), 1);
    expect(harness.session.restoreError, isNull);
  });

  test('저장된 토큰이 없으면 요청 없이 비로그인으로 끝난다', () async {
    final harness = TestHarness(adapter: healthyServer());

    await harness.session.restore();

    expect(harness.session.status, AuthStatus.unauthenticated);
    expect(harness.session.profile, isNull);
    expect(harness.adapter.requests, isEmpty);
  });

  test('보안 저장소 읽기가 실패하면 비로그인으로 단정하지 않고 복구 대기로 둔다', () async {
    final harness = TestHarness(
      adapter: healthyServer(),
      storage: FakeTokenStorage(failReads: true),
    );

    await harness.session.restore();

    expect(harness.session.status, AuthStatus.unavailable);
    expect(harness.session.restoreError?.message, '읽기 실패');
    expect(harness.adapter.requests, isEmpty);
    expect(harness.storage.deleteCount, 0);
  });

  test('refresh가 TOKEN_EXPIRED면 세션을 정리하고 저장소를 비운다', () async {
    final harness = TestHarness(
      adapter: FakeHttpAdapter(
        (_) async => FakeResponse.error(
          401,
          code: ApiErrorCodes.tokenExpired,
          message: '로그인이 만료됐어요. 다시 로그인해주세요',
        ),
      ),
      storage: FakeTokenStorage(initialRefreshToken: 'expired-refresh'),
    );

    await harness.session.restore();

    expect(harness.session.status, AuthStatus.unauthenticated);
    expect(harness.storage.stored, isNull);
    expect(harness.tokens.accessToken, isNull);
    expect(harness.countTo('/users/me'), 0);
    expect(harness.countTo('/accounts/me'), 0);
  });

  test('refresh가 INVALID_TOKEN이어도 세션을 정리한다', () async {
    final harness = TestHarness(
      adapter: FakeHttpAdapter(
        (_) async => FakeResponse.error(
          401,
          code: ApiErrorCodes.invalidToken,
          message: '인증 정보가 올바르지 않아요',
        ),
      ),
      storage: FakeTokenStorage(initialRefreshToken: 'stale-refresh'),
    );

    await harness.session.restore();

    expect(harness.session.status, AuthStatus.unauthenticated);
    expect(harness.storage.stored, isNull);
  });

  test('refresh 통신 실패는 저장 토큰을 지우지 않고 복구 대기 상태로 둔다', () async {
    final harness = TestHarness(
      adapter: FakeHttpAdapter(
        (options) async => throw DioException.connectionError(
          requestOptions: options,
          reason: 'offline',
        ),
      ),
      storage: FakeTokenStorage(initialRefreshToken: 'stored-refresh'),
    );

    await harness.session.restore();

    expect(harness.session.status, AuthStatus.unavailable);
    expect(harness.session.restoreError?.kind, ApiErrorKind.network);
    expect(harness.storage.stored, 'stored-refresh');
    expect(harness.storage.deleteCount, 0);
  });

  test('refresh는 성공했지만 프로필 조회가 5xx면 복구 대기 상태로 둔다', () async {
    final harness = TestHarness(
      adapter: FakeHttpAdapter((options) async {
        if (options.uri.path.endsWith('/auth/refresh')) {
          return FakeResponse.ok(accessTokenJson('fresh-access'));
        }
        return FakeResponse.error(
          500,
          code: 'INTERNAL_ERROR',
          message: '일시적인 오류가 발생했어요. 잠시 후 다시 시도해주세요',
        );
      }),
      storage: FakeTokenStorage(initialRefreshToken: 'stored-refresh'),
    );

    await harness.session.restore();

    expect(harness.session.status, AuthStatus.unavailable);
    expect(harness.session.restoreError?.kind, ApiErrorKind.server);
    expect(harness.storage.stored, 'stored-refresh');
    expect(harness.tokens.accessToken, 'fresh-access');
  });

  test('로그인은 refresh token 저장에 성공한 뒤에만 인증 상태를 공개한다', () async {
    final harness = TestHarness(adapter: healthyServer());
    await harness.session.restore();
    expect(harness.session.status, AuthStatus.unauthenticated);

    final profile = await harness.session.logIn(
      email: 'user@example.com',
      password: 'password1',
    );

    expect(profile.nickname, '사용자');
    expect(harness.session.status, AuthStatus.authenticated);
    expect(harness.tokens.accessToken, 'login-access');
    expect(harness.storage.stored, 'login-refresh');
    expect(harness.session.account?.cashBalance, '49000000');
  });

  test('저장에 실패한 로그인은 인증 상태로 넘어가지 않는다', () async {
    final harness = TestHarness(
      adapter: healthyServer(),
      storage: FakeTokenStorage(failWrites: true),
    );
    await harness.session.restore();

    await expectLater(
      harness.session.logIn(email: 'user@example.com', password: 'password1'),
      throwsA(isA<StorageException>()),
    );

    expect(harness.session.status, isNot(AuthStatus.authenticated));
    expect(harness.session.profile, isNull);
    expect(harness.tokens.accessToken, isNull);
    expect(harness.storage.stored, isNull);
  });

  test('로그아웃 뒤 도착한 로그인 응답은 토큰을 저장하지 않는다', () async {
    final harness = TestHarness(
      adapter: FakeHttpAdapter((options) async {
        final path = options.uri.path;
        if (path.endsWith('/auth/login')) {
          await Future<void>.delayed(const Duration(milliseconds: 30));
          return FakeResponse.ok(
            authJson(accessToken: 'late-access', refreshToken: 'late-refresh'),
          );
        }
        return FakeResponse.empty();
      }),
    );

    final pending = harness.session.logIn(
      email: 'user@example.com',
      password: 'password1',
    );
    await Future<void>.delayed(const Duration(milliseconds: 5));
    await harness.session.logOut();

    await expectLater(pending, throwsA(isA<ApiException>()));
    expect(harness.session.status, AuthStatus.unauthenticated);
    expect(harness.session.profile, isNull);
    expect(harness.storage.stored, isNull);
    expect(harness.tokens.accessToken, isNull);
  });

  test('갱신 중 로그아웃하면 늦은 access token을 반영하지 않는다', () async {
    final harness = TestHarness(
      adapter: healthyServer(refreshDelay: const Duration(milliseconds: 30)),
      storage: FakeTokenStorage(initialRefreshToken: 'stored-refresh'),
    );

    final pending = harness.session.restore();
    await Future<void>.delayed(const Duration(milliseconds: 5));
    await harness.session.logOut();
    await pending;

    expect(harness.session.status, AuthStatus.unauthenticated);
    expect(harness.tokens.accessToken, isNull);
    expect(harness.tokens.refreshToken, isNull);
    expect(harness.storage.stored, isNull);
    expect(harness.countTo('/users/me'), 0);
  });

  test('로그아웃은 서버 호출이 실패해도 로컬 세션을 유지하지 않는다', () async {
    final harness = TestHarness(
      adapter: FakeHttpAdapter((options) async {
        if (options.uri.path.endsWith('/auth/logout')) {
          return FakeResponse.error(
            500,
            code: 'INTERNAL_ERROR',
            message: '일시적인 오류가 발생했어요',
          );
        }
        return FakeResponse.ok(accountSummaryJson());
      }),
    );
    await harness.signIn(
      accessToken: 'access-token',
      refreshToken: 'refresh-token',
    );

    await harness.session.logOut();

    expect(harness.session.status, AuthStatus.unauthenticated);
    expect(harness.storage.stored, isNull);
    expect(harness.tokens.accessToken, isNull);
    expect(harness.countTo('/auth/logout'), 1);
  });

  test('로그아웃은 로그인에 쓴 access token을 서버로 보낸다', () async {
    final harness = TestHarness(adapter: healthyServer());
    await harness.signIn(
      accessToken: 'access-token',
      refreshToken: 'refresh-token',
    );

    await harness.session.logOut();

    expect(
      bearerTokenOf(harness.requestsTo('/auth/logout').single),
      'access-token',
    );
  });

  test('저장소 삭제에 실패하면 로그아웃을 알린다', () async {
    final harness = TestHarness(
      adapter: healthyServer(),
      storage: FakeTokenStorage(
        initialRefreshToken: 'refresh-token',
        failDeletes: true,
      ),
    );
    await harness.signIn(
      accessToken: 'access-token',
      refreshToken: 'refresh-token',
    );

    await expectLater(
      harness.session.logOut(),
      throwsA(isA<StorageException>()),
    );

    expect(harness.session.status, AuthStatus.unauthenticated);
    expect(harness.tokens.accessToken, isNull);
  });

  test('현재 세션의 INVALID_TOKEN 응답은 화면 상태를 비로그인으로 되돌린다', () async {
    var invalid = false;
    final harness = TestHarness(
      adapter: FakeHttpAdapter((options) async {
        final path = options.uri.path;
        if (path.endsWith('/auth/refresh')) {
          return FakeResponse.ok(accessTokenJson('fresh-access'));
        }
        if (path.endsWith('/users/me')) {
          if (invalid) {
            return FakeResponse.error(
              401,
              code: ApiErrorCodes.invalidToken,
              message: '인증 정보가 올바르지 않아요',
            );
          }
          return FakeResponse.ok(profileJson());
        }
        return FakeResponse.ok(accountSummaryJson());
      }),
      storage: FakeTokenStorage(initialRefreshToken: 'stored-refresh'),
    );
    await harness.session.restore();
    expect(harness.session.status, AuthStatus.authenticated);
    invalid = true;

    await expectLater(
      harness.session.reloadProfile(),
      throwsA(isA<ApiException>()),
    );

    expect(harness.session.status, AuthStatus.unauthenticated);
    expect(harness.session.profile, isNull);
    expect(harness.storage.stored, isNull);
    expect(harness.tokens.accessToken, isNull);
  });
}
