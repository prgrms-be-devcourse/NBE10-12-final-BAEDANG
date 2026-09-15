import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:investup_app/core/models/market_country.dart';
import 'package:investup_app/features/rankings/rankings_screen.dart';
import 'package:investup_app/features/stock_detail/stock_detail_screen.dart';

import '../core/fakes/fake_http_adapter.dart';
import '../core/fakes/fake_token_storage.dart';
import '../core/fakes/fixtures.dart';
import '../core/fakes/test_harness.dart';

Map<String, Object?> _detailJson() => <String, Object?>{
  'symbol': '005930',
  'name': '삼성전자',
  'market': 'KOSPI',
  'marketCountry': 'KR',
  'currency': 'KRW',
  'category': 'INDIVIDUAL',
  'price': <String, Object?>{
    'lastPrice': '74500',
    'prevClose': '74000',
    'changeAmount': '500',
    'changeRate': '0.006757',
    'realtime': true,
  },
  'info': <String, Object?>{},
  'warnings': <Object?>[],
  'warningsStatus': 'AVAILABLE',
  'tradable': true,
  'tradableReason': null,
};

Map<String, Object?> _closedDetailJson() => <String, Object?>{
  ..._detailJson(),
  'tradable': false,
  'tradableReason': 'MARKET_CLOSED',
};

Map<String, Object?> _candlesJson() => <String, Object?>{
  'symbol': '005930',
  'interval': '1m',
  'range': '1D',
  'currency': 'KRW',
  'items': <Object?>[
    <String, Object?>{
      'at': '2026-09-15T09:00:00+09:00',
      'open': '74000',
      'high': '75000',
      'low': '73800',
      'close': '74500',
      'volume': '12345678',
    },
    <String, Object?>{
      'at': '2026-09-15T09:01:00+09:00',
      'open': '74500',
      'high': '74600',
      'low': '74300',
      'close': '74400',
      'volume': '2345678',
    },
  ],
};

Map<String, Object?> _bookJson() => <String, Object?>{
  'symbol': '005930',
  'marketCountry': 'KR',
  'basePrice': '74500',
  'currency': 'KRW',
  'virtual': true,
  'description': '현재가 기반 가상 호가·가상 잔량',
  'asks': <Object?>[
    <String, Object?>{'level': 1, 'price': '74600', 'quantity': '100'},
  ],
  'bids': <Object?>[
    <String, Object?>{'level': 1, 'price': '74400', 'quantity': '200'},
  ],
};

Map<String, Object?> _searchJson() => <String, Object?>{
  'items': <Object?>[
    <String, Object?>{
      'symbol': '005930',
      'name': '삼성전자',
      'englishName': 'SamsungElec',
      'market': 'KOSPI',
      'marketCountry': 'KR',
      'category': 'INDIVIDUAL',
    },
  ],
};

Map<String, Object?> _quoteJson() => <String, Object?>{
  'symbol': '005930',
  'marketCountry': 'KR',
  'side': 'BUY',
  'quantity': '2',
  'executedPrice': '74500',
  'exchangeRate': '1',
  'grossAmount': '149000',
  'fee': '149',
  'tax': '0',
  'netAmount': '149149',
  'availableCash': '50000000',
  'quoteAt': '2026-09-15T10:00:00+09:00',
  'executable': true,
  'reason': null,
};

Map<String, Object?> _orderResultJson() => <String, Object?>{
  'orderId': 42,
  'status': 'FILLED',
  'symbol': '005930',
  'marketCountry': 'KR',
  'side': 'BUY',
  'quantity': '2',
  'executedPrice': '74500',
  'grossAmount': '149000',
  'fee': '149',
  'tax': '0',
  'netAmount': '149149',
  'orderedAt': '2026-09-15T10:00:01+09:00',
  'account': <String, Object?>{'cashBalanceAfter': '49850851'},
};

TestHarness _harness({bool tradable = true, bool signedIn = false}) =>
    TestHarness(
      storage: signedIn ? FakeTokenStorage(initialRefreshToken: 'rt') : null,
      adapter: FakeHttpAdapter((options) async {
    final path = options.uri.path;
    if (path == '/api/stocks/rankings') {
      return FakeResponse.ok(rankingJson());
    }
    if (path == '/api/exchange-rates/latest') {
      return FakeResponse.ok(exchangeRateJson());
    }
    if (path == '/api/stocks/search') return FakeResponse.ok(_searchJson());
    if (path == '/api/stocks/005930') {
      return FakeResponse.ok(tradable ? _detailJson() : _closedDetailJson());
    }
    if (path == '/api/stocks/005930/candles') {
      return FakeResponse.ok(_candlesJson());
    }
    if (path == '/api/stocks/005930/orderbook') {
      return FakeResponse.ok(_bookJson());
    }
    if (path == '/api/stocks/likes' && options.method == 'POST') {
      return FakeResponse.ok(<String, Object?>{'stockLikeId': 9});
    }
    if (path.startsWith('/api/stocks/likes/')) return FakeResponse(204, null);
    if (path == '/api/auth/login') return FakeResponse.ok(authJson());
    if (path == '/api/auth/refresh') {
      return FakeResponse.ok(accessTokenJson('access-token'));
    }
    if (path == '/api/users/me') return FakeResponse.ok(profileJson());
    if (path == '/api/accounts/me') {
      return FakeResponse.ok(accountSummaryJson());
    }
    if (path == '/api/accounts/me/holdings') {
      return FakeResponse.ok(holdingsJson());
    }
    if (path == '/api/orders/quote/market') {
      return FakeResponse.ok(_quoteJson());
    }
    if (path == '/api/orders/market') {
      return FakeResponse.ok(_orderResultJson());
    }
    return FakeResponse(404, const {'code': 'NOT_FOUND', 'message': '없음'});
  }),
);

GoRouter _router(TestHarness harness) => GoRouter(
  initialLocation: '/',
  routes: [
    GoRoute(
      path: '/',
      builder: (context, state) => Scaffold(
        body: RankingsScreen(
          stocks: harness.stocks,
          session: harness.session,
          exchangeRates: harness.exchangeRates,
          market: harness.market,
        ),
      ),
    ),
    GoRoute(
      path: '/login',
      builder: (context, state) => const Scaffold(body: Text('로그인 화면')),
    ),
    GoRoute(
      path: '/stocks/:symbol',
      builder: (context, state) {
        final params = state.uri.queryParameters;
        return StockDetailScreen(
          symbol: state.pathParameters['symbol']!,
          marketCountry: MarketCountry.fromWire(params['marketCountry']),
          stocks: harness.stocks,
          session: harness.session,
          orders: harness.orders,
          account: harness.account,
          stockId: int.tryParse(params['stockId'] ?? ''),
          stockLikeId: int.tryParse(params['likeId'] ?? ''),
        );
      },
    ),
  ],
);

/// 비동기 응답을 처리할 만큼만 pump한다. 무한 애니메이션 위젯 때문에
/// pumpAndSettle은 쓰지 않는다.
Future<void> _settle(WidgetTester tester) async {
  for (var i = 0; i < 10; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

void main() {
  testWidgets('랭킹에서 종목을 누르면 상세 화면에 가격·차트·호가가 나온다', (tester) async {
    final harness = _harness();
    await tester.pumpWidget(MaterialApp.router(routerConfig: _router(harness)));
    await _settle(tester);

    expect(find.text('삼성전자'), findsOneWidget);

    await tester.tap(find.text('삼성전자'));
    await _settle(tester);

    expect(find.text('74,500원'), findsWidgets);
    expect(find.text('실시간'), findsOneWidget);
    expect(find.text('거래하기'), findsOneWidget);

    // 호가 카드는 스크롤 아래에 있다.
    await tester.scrollUntilVisible(find.text('호가'), 300);
    await _settle(tester);
    expect(find.text('가상 호가'), findsOneWidget);
    expect(find.textContaining('기준가'), findsOneWidget);
  });

  testWidgets('비로그인 사용자가 찜을 누르면 로그인으로 간다', (tester) async {
    final harness = _harness();
    await tester.pumpWidget(MaterialApp.router(routerConfig: _router(harness)));
    await _settle(tester);

    await tester.tap(find.byTooltip('찜하기'));
    await _settle(tester);

    expect(find.text('로그인 화면'), findsOneWidget);
    expect(harness.countTo('/api/stocks/likes'), 0);
  });

  testWidgets('로그인 사용자가 찜을 누르면 like POST 후 채워진 하트가 된다', (tester) async {
    final harness = _harness(signedIn: true);
    // 저장 토큰 복원은 pump 안에서 진행된다(실제 앱의 스플래시와 같은 방식).
    unawaited(harness.session.restore());
    await tester.pumpWidget(MaterialApp.router(routerConfig: _router(harness)));
    await _settle(tester);
    expect(harness.session.isAuthenticated, isTrue);

    await tester.tap(find.byTooltip('찜하기'));
    await _settle(tester);

    final requests = harness.requestsTo('/api/stocks/likes');
    expect(requests, hasLength(1));
    expect(requests.single.data, <String, dynamic>{'stockId': 101});
    expect(find.byIcon(Icons.favorite), findsOneWidget);
  });

  testWidgets('검색어를 입력하면 검색 결과가 나오고 결과를 누르면 상세로 간다', (tester) async {
    final harness = _harness();
    await tester.pumpWidget(MaterialApp.router(routerConfig: _router(harness)));
    await _settle(tester);

    await tester.enterText(find.byType(TextField).first, '삼성');
    await tester.pump(const Duration(milliseconds: 500));
    await _settle(tester);

    expect(find.text('005930 · KOSPI · INDIVIDUAL'), findsOneWidget);

    await tester.tap(find.text('삼성전자'));
    await _settle(tester);

    expect(find.text('거래하기'), findsOneWidget);
  });

  testWidgets('장이 닫힌 종목은 거래하기 대신 사유 문구가 나온다', (tester) async {
    final harness = _harness(tradable: false);
    await tester.pumpWidget(MaterialApp.router(routerConfig: _router(harness)));
    await _settle(tester);

    await tester.tap(find.text('삼성전자'));
    await _settle(tester);

    expect(find.text('장 마감 · 거래 시간이 아니에요'), findsOneWidget);
    expect(find.text('거래하기'), findsNothing);
  });

  testWidgets('로그인 사용자가 시장가 매수 주문까지 완료한다', (tester) async {
    final harness = _harness(signedIn: true);
    unawaited(harness.session.restore());
    await tester.pumpWidget(MaterialApp.router(routerConfig: _router(harness)));
    await _settle(tester);
    expect(harness.session.isAuthenticated, isTrue);

    await tester.tap(find.text('삼성전자'));
    await _settle(tester);
    await tester.tap(find.text('거래하기'));
    await _settle(tester);

    // 수량 입력 → 디바운스 뒤 견적이 표시된다.
    await tester.enterText(find.byType(TextField).last, '2');
    await tester.pump(const Duration(milliseconds: 500));
    await _settle(tester);
    expect(find.text('결제 예정'), findsWidgets);

    // 주문 → 확인 다이얼로그 → 주문 실행.
    await tester.tap(find.text('시장가 매수 주문'));
    await _settle(tester);
    await tester.tap(find.widgetWithText(FilledButton, '주문'));
    await _settle(tester);

    expect(find.text('체결 완료'), findsOneWidget);
    final orders = harness.requestsTo('/api/orders/market');
    expect(orders, hasLength(1));
    expect(orders.single.data['clientOrderId'], isA<String>());
    expect(orders.single.data['accountId'], 3);
  });
}
