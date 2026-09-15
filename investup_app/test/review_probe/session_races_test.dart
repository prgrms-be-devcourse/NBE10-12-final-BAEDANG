import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:investup_app/core/api/api_error.dart';
import 'package:investup_app/core/auth/auth_session.dart';

import '../core/fakes/fake_http_adapter.dart';
import '../core/fakes/fake_token_storage.dart';
import '../core/fakes/fixtures.dart';
import '../core/fakes/test_harness.dart';

void main() {
  test('이전 로그아웃 응답이 새 로그인 저장 토큰을 삭제하지 않아야 한다', () async {
    final logoutStarted = Completer<void>();
    final logoutResponse = Completer<FakeResponse>();
    final harness = TestHarness(
      adapter: FakeHttpAdapter((options) async {
        final path = options.uri.path;
        if (path.endsWith('/auth/logout')) {
          logoutStarted.complete();
          return logoutResponse.future;
        }
        if (path.endsWith('/auth/login')) {
          return FakeResponse.ok(authJson(refreshToken: 'new-refresh'));
        }
        return FakeResponse.ok(accountSummaryJson());
      }),
    );
    await harness.signIn();
    final logout = harness.session.logOut();
    await logoutStarted.future;
    await harness.session.logIn(
      email: 'new@example.com',
      password: 'fixture-password',
    );
    expect(harness.storage.stored, 'new-refresh');
    logoutResponse.complete(FakeResponse.empty());
    await logout;
    expect(harness.session.status, AuthStatus.authenticated);
    expect(harness.storage.stored, 'new-refresh');
  });

  test('늦게 끝난 이전 로그인이 최신 로그인 프로필을 덮지 않아야 한다', () async {
    final firstStarted = Completer<void>();
    final firstResponse = Completer<FakeResponse>();
    var loginCount = 0;
    final harness = TestHarness(
      adapter: FakeHttpAdapter((options) async {
        if (options.uri.path.endsWith('/auth/login')) {
          loginCount++;
          if (loginCount == 1) {
            firstStarted.complete();
            return firstResponse.future;
          }
          return FakeResponse.ok(
            authJson(userId: 2, refreshToken: 'second-refresh'),
          );
        }
        return FakeResponse.ok(accountSummaryJson());
      }),
    );
    final first = harness.session.logIn(
      email: 'first@example.com',
      password: 'fixture-password',
    );
    final outcome = first.then<Object>(
      (value) => value,
      onError: (Object error) => error,
    );
    await firstStarted.future;
    await harness.session.logIn(
      email: 'second@example.com',
      password: 'fixture-password',
    );
    expect(harness.session.profile?.userId, 2);
    firstResponse.complete(
      FakeResponse.ok(authJson(userId: 1, refreshToken: 'first-refresh')),
    );
    await outcome;
    expect(harness.session.profile?.userId, 2);
    expect(harness.storage.stored, 'second-refresh');
  });

  test('로그인 계좌 조회 중 로그아웃은 null 크래시 대신 취소로 끝나야 한다', () async {
    final accountStarted = Completer<void>();
    final accountResponse = Completer<FakeResponse>();
    final harness = TestHarness(
      adapter: FakeHttpAdapter((options) async {
        final path = options.uri.path;
        if (path.endsWith('/auth/login')) return FakeResponse.ok(authJson());
        if (path.endsWith('/accounts/me')) {
          accountStarted.complete();
          return accountResponse.future;
        }
        return FakeResponse.empty();
      }),
    );
    final login = harness.session.logIn(
      email: 'fixture@example.com',
      password: 'fixture-password',
    );
    final outcome = login.then<Object>(
      (value) => value,
      onError: (Object error) => error,
    );
    await accountStarted.future;
    await harness.session.logOut();
    accountResponse.complete(FakeResponse.ok(accountSummaryJson()));
    expect(await outcome, isA<ApiException>());
    expect(harness.session.status, AuthStatus.unauthenticated);
  });

  test('복원 중 내부 refresh의 503은 저장 토큰을 보존해야 한다', () async {
    var refreshCount = 0;
    final harness = TestHarness(
      storage: FakeTokenStorage(initialRefreshToken: 'valid-refresh'),
      adapter: FakeHttpAdapter((options) async {
        if (options.uri.path.endsWith('/auth/refresh')) {
          refreshCount++;
          if (refreshCount == 1) {
            return FakeResponse.ok(accessTokenJson('expired-access'));
          }
          return FakeResponse.error(
            503,
            code: 'INTERNAL_ERROR',
            message: '일시적인 오류',
          );
        }
        return FakeResponse.error(
          401,
          code: 'TOKEN_EXPIRED',
          message: 'access 만료',
        );
      }),
    );
    await harness.session.restore();
    expect(refreshCount, 2);
    // 첫 refresh가 회전시킨 토큰이 저장돼 있다 — 두 번째 실패가 덮지 않는다.
    expect(harness.storage.stored, 'rotated-refresh');
    expect(harness.session.status, AuthStatus.unavailable);
    expect(harness.session.restoreError?.kind, ApiErrorKind.server);
  });

  test('이전 세대 refresh 실패가 새 로그인 세션을 무효화하지 않아야 한다', () async {
    final refreshStarted = Completer<void>();
    final refreshResponse = Completer<FakeResponse>();
    final harness = TestHarness(
      adapter: FakeHttpAdapter((options) async {
        final path = options.uri.path;
        if (path.endsWith('/users/me')) {
          return FakeResponse.error(
            401,
            code: 'TOKEN_EXPIRED',
            message: 'access 만료',
          );
        }
        if (path.endsWith('/auth/refresh')) {
          refreshStarted.complete();
          return refreshResponse.future;
        }
        if (path.endsWith('/auth/login')) {
          return FakeResponse.ok(
            authJson(userId: 2, refreshToken: 'new-refresh'),
          );
        }
        if (path.endsWith('/accounts/me')) {
          return FakeResponse.ok(accountSummaryJson());
        }
        return FakeResponse.empty();
      }),
    );
    await harness.signIn();
    final oldRequest = harness.auth.getMe();
    final outcome = oldRequest.then<Object>(
      (value) => value,
      onError: (Object error) => error,
    );
    await refreshStarted.future;
    await harness.session.logOut();
    await harness.session.logIn(
      email: 'new@example.com',
      password: 'fixture-password',
    );
    refreshResponse.complete(
      FakeResponse.error(401, code: 'INVALID_TOKEN', message: '이전 refresh 무효'),
    );
    await outcome;
    expect(harness.session.status, AuthStatus.authenticated);
    expect(harness.session.profile?.userId, 2);
    expect(harness.storage.stored, 'new-refresh');
  });
}
