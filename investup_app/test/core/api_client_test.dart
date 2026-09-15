import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:investup_app/core/api/api_error.dart';
import 'package:investup_app/core/models/market_country.dart';

import 'fakes/fake_http_adapter.dart';
import 'fakes/fixtures.dart';
import 'fakes/test_harness.dart';

void main() {
  test('최종 요청 URI가 /api/users/me로 조합된다', () async {
    final harness = TestHarness(
      adapter: FakeHttpAdapter((_) async => FakeResponse.ok(profileJson())),
    );

    await harness.auth.getMe();

    expect(harness.adapter.requests, hasLength(1));
    expect(
      harness.adapter.requests.single.uri.toString(),
      'http://10.0.2.2:8080/api/users/me',
    );
  });

  test('공개 요청에는 로그인 상태여도 Authorization을 붙이지 않는다', () async {
    RequestOptions? recorded;
    final harness = TestHarness(
      adapter: FakeHttpAdapter((options) async {
        recorded = options;
        return FakeResponse.ok(authJson());
      }),
    );
    await harness.signIn();

    await harness.auth.logIn(email: 'user@example.com', password: 'password1');

    expect(recorded!.uri.path, '/api/auth/login');
    expect(recorded!.headers.containsKey('Authorization'), isFalse);
    expect(recorded!.data, <String, dynamic>{
      'email': 'user@example.com',
      'password': 'password1',
    });
  });

  test('필수 인증 요청에는 Bearer access token을 붙인다', () async {
    final harness = TestHarness(
      adapter: FakeHttpAdapter((_) async => FakeResponse.ok(profileJson())),
    );
    await harness.signIn(accessToken: 'access-token');

    await harness.auth.getMe();

    expect(bearerTokenOf(harness.adapter.requests.single), 'access-token');
  });

  test('선택 인증(랭킹)은 로그인 시 토큰과 market/size 쿼리를 보낸다', () async {
    final harness = TestHarness(
      adapter: FakeHttpAdapter((_) async => FakeResponse.ok(rankingJson())),
    );
    await harness.signIn(accessToken: 'access-token');

    final page = await harness.stocks.getRankings(market: MarketCountry.kr);

    final request = harness.adapter.requests.single;
    expect(request.uri.path, '/api/stocks/rankings');
    expect(request.uri.queryParameters['market'], 'KR');
    expect(request.uri.queryParameters['size'], '20');
    expect(request.uri.queryParameters.containsKey('cursor'), isFalse);
    expect(bearerTokenOf(request), 'access-token');
    expect(page.items.single.name, '삼성전자');
  });

  test('비로그인 랭킹 조회는 Authorization 없이 보낸다', () async {
    final harness = TestHarness(
      adapter: FakeHttpAdapter((_) async => FakeResponse.ok(rankingJson())),
    );

    await harness.stocks.getRankings(
      market: MarketCountry.us,
      cursor: 'cursor-1',
    );

    final request = harness.adapter.requests.single;
    expect(request.headers.containsKey('Authorization'), isFalse);
    expect(request.uri.queryParameters['market'], 'US');
    expect(request.uri.queryParameters['cursor'], 'cursor-1');
  });

  test('서버 오류 본문의 code/message를 사용자 문구로 전달한다', () async {
    final harness = TestHarness(
      adapter: FakeHttpAdapter(
        (_) async => FakeResponse.error(
          401,
          code: ApiErrorCodes.loginFailed,
          message: '이메일 또는 비밀번호가 올바르지 않아요',
        ),
      ),
    );

    await expectLater(
      harness.auth.logIn(email: 'user@example.com', password: 'wrong-password'),
      throwsA(
        isA<ApiException>()
            .having((e) => e.error.code, 'code', ApiErrorCodes.loginFailed)
            .having((e) => e.message, 'message', '이메일 또는 비밀번호가 올바르지 않아요')
            .having(
              (e) => e.error.isSessionInvalid,
              'isSessionInvalid',
              isFalse,
            ),
      ),
    );
  });

  test('ErrorResponse가 아닌 응답은 unexpected로 처리하고 크래시하지 않는다', () async {
    final harness = TestHarness(
      adapter: FakeHttpAdapter(
        (_) async => const FakeResponse.rawBody(200, '<html>proxy</html>'),
      ),
    );

    await expectLater(
      harness.market.getMarketStatus(),
      throwsA(
        isA<ApiException>().having(
          (e) => e.error,
          'error',
          ApiError.unexpectedResponse,
        ),
      ),
    );
  });

  test('필수 필드가 깨진 응답도 unexpected로 바꾸고 원본 예외를 노출하지 않는다', () async {
    final harness = TestHarness(
      adapter: FakeHttpAdapter(
        (_) async => FakeResponse.ok(<String, Object?>{
          'userId': 'not-a-number',
          'email': 'user@example.com',
          'nickname': '사용자',
        }),
      ),
    );

    await expectLater(
      harness.auth.getMe(),
      throwsA(
        isA<ApiException>().having(
          (e) => e.error.kind,
          'kind',
          ApiErrorKind.unexpected,
        ),
      ),
    );
  });

  test('취소한 요청은 cancelled 예외로 끝난다', () async {
    final harness = TestHarness(
      adapter: FakeHttpAdapter(
        (_) async => FakeResponse.ok(profileJson()),
        delay: const Duration(milliseconds: 20),
      ),
    );
    final cancelToken = CancelToken();

    final future = harness.auth.getMe(cancelToken: cancelToken);
    cancelToken.cancel();

    await expectLater(
      future,
      throwsA(
        isA<ApiException>().having(
          (e) => e.error.kind,
          'kind',
          ApiErrorKind.cancelled,
        ),
      ),
    );
  });

  test('시장 상태는 공개 요청으로 조회한다', () async {
    final harness = TestHarness(
      adapter: FakeHttpAdapter(
        (_) async => FakeResponse.ok(marketStatusJson()),
      ),
    );

    final status = await harness.market.getMarketStatus();

    expect(status.sessionOf(MarketCountry.kr)!.open, isTrue);
    expect(harness.adapter.requests.single.uri.path, '/api/market/status');
    expect(
      harness.adapter.requests.single.headers.containsKey('Authorization'),
      isFalse,
    );
  });

  test('필수 필드가 깨진 응답은 빈 결과 대신 unexpected 오류로 끝난다', () async {
    final broken = marketStatusJson()..remove('markets');
    final harness = TestHarness(
      adapter: FakeHttpAdapter((_) async => FakeResponse.ok(broken)),
    );

    await expectLater(
      harness.market.getMarketStatus(),
      throwsA(
        isA<ApiException>().having(
          (e) => e.error.kind,
          'kind',
          ApiErrorKind.unexpected,
        ),
      ),
    );
  });
}
