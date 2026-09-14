import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:investup_app/core/models/market_country.dart';
import 'package:investup_app/features/auth/forgot_password_screen.dart';
import 'package:investup_app/features/guide/guide_screen.dart';
import 'package:investup_app/features/guide/wiki_terms.dart';
import 'package:investup_app/features/guide/wiki_terms_source.dart';
import 'package:investup_app/features/my/my_screen.dart';
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

Map<String, Object?> _limitQuoteJson() => <String, Object?>{
  'symbol': '005930',
  'marketCountry': 'KR',
  'side': 'BUY',
  'quantity': '2',
  'requestedLimitPrice': '74000',
  'requestedLimitCurrency': 'KRW',
  'limitPrice': '74000',
  'acceptanceExchangeRate': '1',
  'acceptable': true,
  'reason': null,
  'availableCash': '50000000',
  'availableQuantity': null,
  'expiresAt': '2026-09-16T10:00:00+09:00',
  'estimate': <String, Object?>{
    'grossAmount': '148000',
    'fee': '148',
    'tax': '0',
    'netAmount': '148148',
    'reservedCash': '148148',
  },
  'executionPreview': null,
};

Map<String, Object?> _limitOrderJson({String status = 'PENDING'}) =>
    <String, Object?>{
      'orderId': 77,
      'accountId': 3,
      'stockId': 101,
      'symbol': '005930',
      'name': '삼성전자',
      'marketCountry': 'KR',
      'orderType': 'LIMIT',
      'side': 'BUY',
      'status': status,
      'quantity': '2',
      'filledQuantity': '0',
      'activeRemainingQuantity': '2',
      'requestedLimitPrice': '74000',
      'requestedLimitCurrency': 'KRW',
      'limitPrice': '74000',
      'reservedCash': '148148',
      'orderedAt': '2026-09-15T10:00:01+09:00',
      'expiresAt': '2026-09-16T10:00:00+09:00',
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
  ],
};

Map<String, Object?> _bookJson() => <String, Object?>{
  'symbol': '005930',
  'marketCountry': 'KR',
  'basePrice': '74500',
  'currency': 'KRW',
  'virtual': true,
  'description': '현재가 기반 가상 호가·가상 잔량',
  'asks': <Object?>[],
  'bids': <Object?>[],
};

Map<String, Object?> _likesJson() => <String, Object?>{
  'items': <Object?>[
    <String, Object?>{
      'stockLikeId': 9,
      'stockId': 101,
      'symbol': '005930',
      'name': '삼성전자',
      'marketCountry': 'KR',
      'prevClose': '74000',
      'lastPrice': '74500',
      'changeRate': '0.006757',
    },
  ],
  'nextCursor': null,
  'hasNext': false,
};

Map<String, Object?> _ordersJson() => <String, Object?>{
  'items': <Object?>[_limitOrderJson()],
  'nextCursor': null,
  'hasNext': false,
};

Map<String, Object?> _rankingPage2Json() => <String, Object?>{
  'items': <Map<String, Object?>>[
    <String, Object?>{
      'rank': 2,
      'stockId': 102,
      'symbol': '000660',
      'name': 'SK하이닉스',
      'market': 'KOSPI',
      'category': 'INDIVIDUAL',
      'isDividend': false,
      'leverageFactor': null,
      'currency': 'KRW',
      'lastPrice': '1683000',
      'prevClose': '1660000',
      'changeAmount': '23000',
      'changeRate': '0.013855',
      'tradingAmount': '987000000',
      'quoteAt': '2026-09-15T09:30:00+09:00',
      'realtime': true,
      'stockLikeId': null,
    },
  ],
  'nextCursor': null,
  'hasNext': false,
};

Map<String, Object?> _marketEventsJson(String market) => <String, Object?>{
  'market': market,
  'date': '2026-09-15',
  'items': <Map<String, Object?>>[
    if (market == 'KOSPI')
      <String, Object?>{
        'eventId': 11,
        'eventType': 'CIRCUIT_BREAKER',
        'stage': 1,
        'direction': null,
        'triggeredAt': '2026-09-15T10:05:00+09:00',
        'haltUntil': '2026-09-15T10:25:00+09:00',
        'publishedAt': '2026-09-15T10:05:00+09:00',
        'receivedAt': '2026-09-15T10:05:30+09:00',
        'active': true,
        'title': '코스피 서킷브레이커 1단계 발동',
        'sourceUrl': 'https://example.com/kind/11',
      },
  ],
};

Map<String, Object?> _usRankingJson() => <String, Object?>{
  'items': <Map<String, Object?>>[
    <String, Object?>{
      'rank': 1,
      'stockId': 201,
      'symbol': 'NVDA',
      'name': '엔비디아',
      'market': 'NASDAQ',
      'category': 'INDIVIDUAL',
      'isDividend': false,
      'leverageFactor': null,
      'currency': 'USD',
      'lastPrice': '182.40',
      'prevClose': '180.00',
      'changeAmount': '2.40',
      'changeRate': '0.013333',
      'tradingAmount': '987654321',
      'quoteAt': '2026-09-15T09:30:00+09:00',
      'realtime': true,
      'stockLikeId': null,
    },
  ],
  'nextCursor': null,
  'hasNext': false,
};

TestHarness _harness({bool signedIn = false}) => TestHarness(
  storage: signedIn ? FakeTokenStorage(initialRefreshToken: 'rt') : null,
  adapter: FakeHttpAdapter((options) async {
    final path = options.uri.path;
    if (path == '/api/stocks/rankings') {
      final market = options.uri.queryParameters['market'];
      final cursor = options.uri.queryParameters['cursor'];
      if (market == 'US') return FakeResponse.ok(_usRankingJson());
      return FakeResponse.ok(
        cursor == 'cursor-1' ? _rankingPage2Json() : rankingJson(),
      );
    }
    if (path == '/api/market/events') {
      return FakeResponse.ok(
        _marketEventsJson(options.uri.queryParameters['market'] ?? ''),
      );
    }
    if (path == '/api/exchange-rates/latest') {
      return FakeResponse.ok(exchangeRateJson());
    }
    if (path == '/api/exchange-rates/history') {
      return FakeResponse.ok(<String, Object?>{
        'items': <Map<String, Object?>>[
          <String, Object?>{
            'validFrom': '2026-08-15T00:00:00+09:00',
            'rate': '1390.0',
          },
          <String, Object?>{
            'validFrom': '2026-09-15T00:00:00+09:00',
            'rate': '1398.5',
          },
        ],
      });
    }
    if (path == '/api/stocks/005930') return FakeResponse.ok(_detailJson());
    if (path == '/api/stocks/005930/candles') {
      return FakeResponse.ok(_candlesJson());
    }
    if (path == '/api/stocks/005930/orderbook') {
      return FakeResponse.ok(_bookJson());
    }
    if (path == '/api/stocks/likes' && options.method == 'GET') {
      return FakeResponse.ok(_likesJson());
    }
    if (path == '/api/stocks/likes' && options.method == 'POST') {
      return FakeResponse.ok(<String, Object?>{'stockLikeId': 9});
    }
    if (path.startsWith('/api/stocks/likes/')) return FakeResponse(204, null);
    if (path == '/api/accounts/me/orders') {
      return FakeResponse.ok(_ordersJson());
    }
    if (path == '/api/accounts/me') {
      return FakeResponse.ok(accountSummaryJson());
    }
    if (path == '/api/orders/quote/limit') {
      return FakeResponse.ok(_limitQuoteJson());
    }
    if (path == '/api/orders/limit' && options.method == 'POST') {
      return FakeResponse.ok(_limitOrderJson());
    }
    if (path == '/api/orders/77' && options.method == 'PATCH') {
      return FakeResponse.ok(_limitOrderJson(status: 'CANCELED'));
    }
    if (path == '/api/auth/refresh') {
      return FakeResponse.ok(accessTokenJson('access-token'));
    }
    if (path == '/api/users/me') return FakeResponse.ok(profileJson());
    if (path == '/api/auth/password/forgot') return FakeResponse(200, null);
    if (path == '/api/auth/password/reset') return FakeResponse(200, null);
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
      path: '/my',
      builder: (context, state) => Scaffold(
        body: MyScreen(
          session: harness.session,
          stocks: harness.stocks,
          account: harness.account,
          orders: harness.orders,
        ),
      ),
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
          stockId: int.tryParse(params['stockId'] ?? ''),
          stockLikeId: int.tryParse(params['likeId'] ?? ''),
        );
      },
    ),
  ],
);

Future<void> _settle(WidgetTester tester) async {
  for (var i = 0; i < 10; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

class _FakeTermsSource implements WikiTermsSource {
  const _FakeTermsSource(this.terms);

  final List<WikiTerm> terms;

  @override
  Future<List<WikiTerm>> fetch() async => terms;
}

void main() {
  group('지정가 주문', () {
    testWidgets('지정가를 입력하면 지정가 견적이 나오고 주문 접수된다', (tester) async {
      final harness = _harness(signedIn: true);
      unawaited(harness.session.restore());
      await tester.pumpWidget(
        MaterialApp.router(routerConfig: _router(harness)),
      );
      await _settle(tester);
      expect(harness.session.isAuthenticated, isTrue);

      await tester.tap(find.text('삼성전자'));
      await _settle(tester);
      // 앱바 제목은 티커가 아니라 종목명이다.
      expect(
        find.descendant(
          of: find.byType(AppBar),
          matching: find.text('삼성전자'),
        ),
        findsOneWidget,
      );

      await tester.tap(find.text('거래하기'));
      await _settle(tester);
      await tester.tap(find.text('지정가'));
      await _settle(tester);

      await tester.enterText(
        find.widgetWithText(TextField, '지정가'),
        '74000',
      );
      await tester.enterText(find.widgetWithText(TextField, '수량'), '2');
      await tester.pump(const Duration(milliseconds: 500));
      await _settle(tester);
      expect(find.text('예약 예정'), findsOneWidget);

      await tester.tap(find.text('지정가 매수 주문'));
      await _settle(tester);
      await tester.tap(find.widgetWithText(FilledButton, '주문'));
      await _settle(tester);

      expect(find.text('주문 접수 완료 (미체결)'), findsOneWidget);
      final orders = harness.requestsTo('/api/orders/limit');
      expect(orders, hasLength(1));
      expect(orders.single.data['limitPrice'], '74000');
      expect(orders.single.data['limitCurrency'], 'KRW');
      expect(orders.single.data['clientOrderId'], isA<String>());
    });
  });

  group('마이페이지', () {
    testWidgets('관심 종목과 주문 내역이 나오고 취소·찜 해제가 된다', (tester) async {
      final harness = _harness(signedIn: true);
      unawaited(harness.session.restore());
      await tester.pumpWidget(
        MaterialApp.router(
          routerConfig: GoRouter(
            initialLocation: '/my',
            routes: [
              GoRoute(
                path: '/my',
                builder: (context, state) => Scaffold(
                  body: MyScreen(
                    session: harness.session,
                    stocks: harness.stocks,
                    account: harness.account,
                    orders: harness.orders,
                  ),
                ),
              ),
              GoRoute(
                path: '/stocks/:symbol',
                builder: (context, state) => const Scaffold(body: Text('상세')),
              ),
            ],
          ),
        ),
      );
      await _settle(tester);

      expect(find.text('관심 종목'), findsOneWidget);
      // 주문 내역 섹션은 스크롤 아래에 있다.
      await tester.scrollUntilVisible(find.text('주문 내역'), 300);
      await _settle(tester);
      expect(find.text('매수 삼성전자'), findsOneWidget);
      expect(find.text('미체결'), findsOneWidget);

      // 주문 취소 → PATCH {status: CANCELED}
      await tester.tap(find.text('취소'));
      await _settle(tester);
      final cancels = harness.requestsTo('/api/orders/77');
      expect(cancels, hasLength(1));
      expect(cancels.single.data, <String, dynamic>{'status': 'CANCELED'});

      // 찜 해제 → DELETE /stocks/likes/9 (위로 스크롤해서 찾는다)
      await tester.scrollUntilVisible(find.byTooltip('찜 해제'), -300);
      await _settle(tester);
      await tester.tap(find.byTooltip('찜 해제'));
      await _settle(tester);
      expect(harness.countTo('/api/stocks/likes/9'), 1);
    });
  });

  group('가이드 검색', () {
    test('terms.md 마크다운을 파싱해 용어·별칭·초성을 만든다', () {
      final terms = parseWikiTerms('''
## 삼성전자
> 국내 대표 전자 회사
> alias: 삼전, Samsung Elec

본문 내용입니다.

## 배당
> 회사가 이익을 나눠주는 것

배당 설명 본문.
''');
      expect(terms, hasLength(2));
      expect(terms[0].name, '삼성전자');
      expect(terms[0].summary, '국내 대표 전자 회사');
      expect(terms[0].aliases, ['삼전', 'Samsung Elec']);
      expect(terms[0].chosung, 'ㅅㅅㅈㅈ');
      expect(terms[0].aliasChosungs, ['ㅅㅈ', 'Samsung Elec']);
      expect(terms[1].chosung, 'ㅂㄷ');
    });

    testWidgets('용어를 검색하면 결과가 나오고 누르면 본문이 열린다', (tester) async {
      const source = _FakeTermsSource([
        WikiTerm(
          name: '배당',
          summary: '회사가 이익을 나눠주는 것',
          aliases: ['배당금'],
          body: '배당 상세 설명 본문입니다.',
          chosung: 'ㅂㄷ',
          aliasChosungs: ['ㅂㄷㄱ'],
        ),
        WikiTerm(
          name: '시가총액',
          summary: '회사의 전체 가치',
          aliases: [],
          body: '시가총액 본문.',
          chosung: 'ㅅㄱㅊㅇ',
          aliasChosungs: [],
        ),
      ]);
      await tester.pumpWidget(
        const MaterialApp(home: Scaffold(body: GuideScreen(termsSource: source))),
      );
      await _settle(tester);

      // 위키 패널로 전환한다.
      await tester.tap(find.text('금융 용어 위키'));
      await _settle(tester);

      // 초성 검색: ㅂㄷ → 배당만.
      await tester.enterText(find.byType(TextField), 'ㅂㄷ');
      await _settle(tester);
      expect(find.text('배당'), findsOneWidget);
      expect(find.text('시가총액'), findsNothing);

      await tester.tap(find.text('배당'));
      await _settle(tester);
      expect(find.text('배당 상세 설명 본문입니다.'), findsOneWidget);
    });

    testWidgets('별칭으로만 걸린 결과는 매칭된 별칭을 보여준다', (tester) async {
      const source = _FakeTermsSource([
        WikiTerm(
          name: '결제일',
          summary: '돈이 오가는 날',
          aliases: ['T+2', '보통거래', '결제'],
          body: '결제일 본문.',
          chosung: 'ㄱㅈㅇ',
          aliasChosungs: ['T+2', 'ㅂㅌㄱㄹ', 'ㄱㅈ'],
        ),
        WikiTerm(
          name: '시가총액',
          summary: '회사의 전체 가치',
          aliases: [],
          body: '시가총액 본문.',
          chosung: 'ㅅㄱㅊㅇ',
          aliasChosungs: [],
        ),
      ]);
      await tester.pumpWidget(
        const MaterialApp(home: Scaffold(body: GuideScreen(termsSource: source))),
      );
      await _settle(tester);

      await tester.tap(find.text('금융 용어 위키'));
      await _settle(tester);

      // '보'는 별칭 '보통거래'에만 포함 → 결제일이 별칭 이유와 함께 나와야 한다.
      await tester.enterText(find.byType(TextField), '보');
      await _settle(tester);
      expect(find.text('결제일'), findsOneWidget);
      expect(find.text('별칭: 보통거래'), findsOneWidget);
      expect(find.text('시가총액'), findsNothing);
    });
  });

  group('랭킹 페이지네이션', () {
    testWidgets('더 보기로 다음 페이지를 붙이고 마지막이면 안내를 보인다', (
      tester,
    ) async {
      final harness = _harness();
      await tester.pumpWidget(
        MaterialApp.router(routerConfig: _router(harness)),
      );
      await _settle(tester);

      expect(find.text('삼성전자'), findsOneWidget);
      // 거래대금 열이 표시된다.
      expect(find.textContaining('거래대금'), findsWidgets);
      expect(find.text('더 보기'), findsOneWidget);

      await tester.tap(find.text('더 보기'));
      await _settle(tester);

      // cursor로 다음 페이지를 요청했고 결과가 이어 붙는다.
      final reqs = harness.requestsTo('/api/stocks/rankings');
      expect(reqs, hasLength(2));
      expect(reqs.last.uri.queryParameters['cursor'], 'cursor-1');
      expect(find.text('SK하이닉스'), findsOneWidget);
      expect(find.text('모든 종목을 불러왔어요'), findsOneWidget);
    });
  });

  group('시장조치 배너', () {
    testWidgets('국내 탭에서 활성 서킷브레이커가 보이고 해외 탭에선 조회하지 않는다', (
      tester,
    ) async {
      final harness = _harness();
      await tester.pumpWidget(
        MaterialApp.router(routerConfig: _router(harness)),
      );
      await _settle(tester);

      // KOSPI의 활성 CB → "매매거래 일시중단" 제목과 발동 중 pill.
      expect(find.text('지금 매매거래 일시중단 중이에요'), findsOneWidget);
      expect(find.text('서킷브레이커 1단계 · 10:05'), findsOneWidget);
      expect(find.text('발동 중'), findsOneWidget);

      // 해외 탭으로 전환하면 시장조치 조회를 하지 않는다.
      await tester.tap(find.text('해외 주식'));
      await _settle(tester);
      expect(find.text('지금 매매거래 일시중단 중이에요'), findsNothing);
      expect(
        harness.requestsTo('/api/market/events').length,
        2, // KOSPI + KOSDAQ 한 번씩만.
      );
    });
  });

  group('환율', () {
    testWidgets('배너에 USD/KRW가 나오고 해외 종목은 원화 환산을 보여준다', (
      tester,
    ) async {
      final harness = _harness();
      await tester.pumpWidget(
        MaterialApp.router(routerConfig: _router(harness)),
      );
      await _settle(tester);

      // 배너에 최신 환율이 표시된다.
      expect(find.text('USD / KRW'), findsOneWidget);
      expect(find.textContaining('1,399'), findsWidgets);

      // 해외 주식 탭으로 전환하면 USD 종목이 원화 환산으로 나온다.
      await tester.tap(find.text('해외 주식'));
      await _settle(tester);
      // 1398.5 × 182.40 ≈ 255,086원
      expect(find.textContaining('255,08'), findsWidgets);
    });

    testWidgets('배너를 누르면 환율 추이 그래프가 열린다', (tester) async {
      final harness = _harness();
      await tester.pumpWidget(
        MaterialApp.router(routerConfig: _router(harness)),
      );
      await _settle(tester);

      await tester.tap(find.text('USD / KRW'));
      await _settle(tester);
      expect(find.text('USD / KRW 환율 추이'), findsOneWidget);
      expect(find.text('1일'), findsWidgets);
      expect(find.text('1년'), findsWidgets);
      expect(
        harness.countTo('/api/exchange-rates/history'),
        greaterThanOrEqualTo(1),
      );
    });
  });

  group('비밀번호 찾기', () {
    testWidgets('메일 요청 후 토큰과 새 비밀번호로 재설정한다', (tester) async {
      final harness = _harness();
      await tester.pumpWidget(
        MaterialApp(
          home: ForgotPasswordScreen(auth: harness.auth),
        ),
      );
      await _settle(tester);

      await tester.enterText(find.byType(TextField).first, 'a@b.com');
      await tester.tap(find.text('재설정 메일 보내기'));
      await _settle(tester);

      final forgot = harness.requestsTo('/api/auth/password/forgot');
      expect(forgot, hasLength(1));
      expect(forgot.single.data, <String, dynamic>{'email': 'a@b.com'});
      expect(find.text('재설정 토큰'), findsOneWidget);

      await tester.enterText(
        find.widgetWithText(TextField, '재설정 토큰'),
        'reset-token-1',
      );
      await tester.enterText(
        find.widgetWithText(TextField, '새 비밀번호 (8~64자)'),
        'newpassword1',
      );
      await tester.enterText(
        find.widgetWithText(TextField, '새 비밀번호 확인'),
        'newpassword1',
      );
      await tester.tap(find.text('비밀번호 재설정'));
      await _settle(tester);

      final reset = harness.requestsTo('/api/auth/password/reset');
      expect(reset, hasLength(1));
      expect(reset.single.data, <String, dynamic>{
        'token': 'reset-token-1',
        'newPassword': 'newpassword1',
      });
      expect(find.text('비밀번호가 변경됐어요'), findsOneWidget);
    });
  });
}
