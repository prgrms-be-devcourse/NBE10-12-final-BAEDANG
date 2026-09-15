import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:investup_app/core/models/market_country.dart';
import 'package:investup_app/features/auth/forgot_password_screen.dart';
import 'package:investup_app/features/guide/guide_screen.dart';
import 'package:investup_app/features/guide/wiki_terms.dart';
import 'package:investup_app/features/guide/wiki_terms_source.dart';
import 'package:investup_app/core/theme/theme_controller.dart';
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
    'upperLimit': '96850',
    'lowerLimit': '52150',
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
  'limitEstimate': <String, Object?>{
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

Map<String, Object?> _nvdaDetailJson() => <String, Object?>{
  'symbol': 'NVDA',
  'name': '엔비디아',
  'market': 'NASDAQ',
  'marketCountry': 'US',
  'currency': 'USD',
  'category': 'INDIVIDUAL',
  'price': <String, Object?>{
    'lastPrice': '182.40',
    'prevClose': '180.00',
    'changeAmount': '2.40',
    'changeRate': '0.013333',
    'realtime': false,
  },
  'info': <String, Object?>{},
  'warnings': <Object?>[],
  'warningsStatus': 'AVAILABLE',
  'tradable': true,
  'tradableReason': null,
};

Map<String, Object?> _financialsJson() => <String, Object?>{
  'symbol': '005930',
  'marketCountry': 'KR',
  'dataStatus': 'FRESH',
  'industry': <String, Object?>{
    'standard': <String, Object?>{'code': 'C26', 'name': '전기·전자'},
    'large': <String, Object?>{'code': 'C2', 'name': '제조업'},
    'medium': <String, Object?>{'code': 'C26', 'name': '전기·전자'},
    'small': <String, Object?>{'code': 'C264', 'name': '반도체'},
  },
  'valuation': <String, Object?>{
    'calculatedPer': '12.34',
    'basis': 'LATEST_ANNUAL_EPS',
  },
  'annual': <Object?>[
    _periodJson('202412', sales: '300000000000000', eps: '3000'),
    _periodJson('202312', sales: '250000000000000', eps: '2000'),
    _periodJson('202212', sales: '230000000000000', eps: '1800'),
  ],
  'quarterly': <Object?>[
    _periodJson('202506', sales: '80000000000000', eps: '700'),
    _periodJson('202503', sales: '75000000000000', eps: '650'),
  ],
  'syncedAt': <String, Object?>{
    'industry': null,
    'annual': null,
    'quarterly': null,
  },
};

Map<String, Object?> _periodJson(
  String ym, {
  required String sales,
  required String eps,
}) => <String, Object?>{
  'statementYearMonth': ym,
  'balanceSheet': <String, Object?>{
    'currentAssets': '1000000',
    'fixedAssets': '2000000',
    'totalAssets': '500000000000000',
    'currentLiabilities': '500000',
    'fixedLiabilities': '500000',
    'totalLiabilities': '150000000000000',
    'capitalStock': '100000',
    'capitalSurplus': '200000',
    'retainedEarnings': '300000',
    'totalEquity': '350000000000000',
  },
  'incomeStatement': <String, Object?>{
    'sales': sales,
    'operatingProfit': '30000000000000',
    'netIncome': '25000000000000',
  },
  'ratios': <String, Object?>{
    'salesGrowthRate': '5.0',
    'operatingProfitGrowthRate': '4.0',
    'netIncomeGrowthRate': '3.0',
    'roe': '8.50',
    'eps': eps,
    'salesPerShare': '50000',
    'bps': '60000',
    'reserveRatio': '100',
    'debtRatio': '42.86',
    'netProfitMargin': '8.33',
    'operatingProfitMargin': '10.00',
  },
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

Map<String, Object?> _executionsJson() => <String, Object?>{
  'orderId': 77,
  'stock': <String, Object?>{
    'symbol': '005930',
    'name': '삼성전자',
    'marketCountry': 'KR',
  },
  'items': <Map<String, Object?>>[
    <String, Object?>{
      'executionId': 501,
      'sequenceNo': 1,
      'quantity': '1',
      'price': '74000',
      'exchangeRate': '1',
      'grossAmount': '74000',
      'fee': '74',
      'tax': '0',
      'netAmount': '74074',
      'balanceAfter': '4925926',
      'executedAt': '2026-09-15T10:01:00+09:00',
    },
  ],
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

TestHarness _harness({bool signedIn = false, bool reportLocked = false}) =>
    TestHarness(
      storage: signedIn ? FakeTokenStorage(initialRefreshToken: 'rt') : null,
      adapter: FakeHttpAdapter((options) async {
    final path = options.uri.path;
    if (path == '/api/market/status') {
      return FakeResponse.ok(marketStatusJson());
    }
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
    if (path == '/api/stocks/005930/financials') {
      return FakeResponse.ok(_financialsJson());
    }
    if (path == '/api/stocks/NVDA') return FakeResponse.ok(_nvdaDetailJson());
    if (path == '/api/stocks/NVDA/candles') {
      return FakeResponse.ok(_candlesJson());
    }
    if (path == '/api/stocks/NVDA/orderbook') {
      return FakeResponse.ok(_bookJson());
    }
    if (path == '/api/stocks/NVDA/financials') {
      return FakeResponse(422, const <String, Object?>{
        'code': 'FINANCIALS_NOT_SUPPORTED',
        'message': '이 종목은 재무 정보를 지원하지 않아요',
      });
    }
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
    if (path == '/api/accounts/me/ledger') {
      return FakeResponse.ok(ledgerJson());
    }
    if (path == '/api/accounts/me/reset' && options.method == 'POST') {
      return FakeResponse.ok(<String, Object?>{
        'accountId': 4,
        'roundNo': 2,
        'initialCash': '50000000',
        'cashBalance': '50000000',
      });
    }
    if (path == '/api/accounts/me/holdings') {
      return FakeResponse.ok(holdingsJson());
    }
    if (path == '/api/accounts/me/orders') {
      return FakeResponse.ok(_ordersJson());
    }
    if (path == '/api/reports/me') {
      return FakeResponse.ok(personalityReportJson(locked: reportLocked));
    }
    if (path == '/api/reports/leaderboard/types') {
      return FakeResponse.ok(leaderboardTypesJson());
    }
    if (path == '/api/reports/leaderboard') {
      return FakeResponse.ok(leaderboardJson());
    }
    if (path == '/api/accounts/me') {
      return FakeResponse.ok(accountSummaryJson());
    }
    if (path == '/api/orders/quote/market') {
      return FakeResponse.ok(<String, Object?>{
        'symbol': '005930',
        'marketCountry': 'KR',
        'side': 'SELL',
        'quantity': '5',
        'executedPrice': '74500',
        'exchangeRate': '1',
        'grossAmount': '372500',
        'fee': '37',
        'tax': '745',
        'netAmount': '371718',
        'availableCash': '50000000',
        'quoteAt': '2026-09-15T10:00:00+09:00',
        'executable': true,
        'reason': null,
      });
    }
    if (path == '/api/orders/quote/limit') {
      return FakeResponse.ok(_limitQuoteJson());
    }
    if (path == '/api/orders/limit' && options.method == 'POST') {
      return FakeResponse.ok(_limitOrderJson());
    }
    if (path == '/api/orders/77/executions') {
      return FakeResponse.ok(_executionsJson());
    }
    if (path == '/api/orders/77' && options.method == 'PATCH') {
      return FakeResponse.ok(_limitOrderJson(status: 'CANCELED'));
    }
    if (path == '/api/auth/refresh') {
      return FakeResponse.ok(accessTokenJson('access-token'));
    }
    if (path == '/api/users/me' && options.method == 'PATCH') {
      return FakeResponse.ok(
        profileJson(nickname: '${options.data?['nickname'] ?? '사용자'}'),
      );
    }
    if (path == '/api/users/me/password' && options.method == 'PUT') {
      return FakeResponse.ok(profileJson());
    }
    if (path == '/api/users/me' && options.method == 'DELETE') {
      return FakeResponse(204, null);
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
          exchangeRates: harness.exchangeRates,
          reports: harness.reports,
          theme: ThemeController(),
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
          account: harness.account,
          exchangeRates: harness.exchangeRates,
          market: harness.market,
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

      // 필드 순서: 주문 수량 → 지정가. 라벨은 입력창 밖 텍스트다(웹과 동일).
      expect(
        find.text('주문 가능 범위: 52,150원 ~ 96,850원 (호가 단위 적용)'),
        findsOneWidget,
      );
      await tester.enterText(find.byType(TextField).last, '74000');
      await tester.enterText(find.byType(TextField).first, '2');
      await tester.pump(const Duration(milliseconds: 600));
      await _settle(tester);
      expect(find.text('예상 동결 예수금'), findsOneWidget);
      expect(find.textContaining('까지 미체결이면 자동 만료돼요'), findsOneWidget);

      // 모달 내용이 길어 버튼이 뷰포트 아래에 있을 수 있어 먼저 보이게 한다.
      await tester.ensureVisible(find.text('매수 주문 접수'));
      await tester.tap(find.text('매수 주문 접수'));
      await _settle(tester);

      expect(find.textContaining('매수 주문을 접수했어요'), findsOneWidget);
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
                    exchangeRates: harness.exchangeRates,
                    reports: harness.reports,
                    theme: ThemeController(),
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

      // 보유 종목: 수량·평균단가·현재가·평가금액·평가손익이 나온다.
      await tester.scrollUntilVisible(find.text('보유 종목'),  300, scrollable: find.byType(Scrollable).first);
      await _settle(tester);
      expect(find.text('보유 종목'), findsOneWidget);
      expect(find.text('삼성전자 005930'), findsOneWidget);
      expect(find.text('10주'), findsOneWidget);
      expect(find.text('745,000원'), findsOneWidget);
      expect(find.textContaining('+5,000'), findsOneWidget);

      // 관심 종목은 보유 종목 아래에 있다 — 스크롤해서 찾는다.
      await tester.scrollUntilVisible(find.text('관심 종목'),  300, scrollable: find.byType(Scrollable).first);
      await _settle(tester);
      expect(find.text('관심 종목'), findsOneWidget);
      // 주문 내역 섹션은 스크롤 아래에 있다.
      await tester.scrollUntilVisible(find.text('주문 내역'),  300, scrollable: find.byType(Scrollable).first);
      await _settle(tester);
      expect(find.text('매수 삼성전자'), findsOneWidget);
      expect(find.text('미체결'), findsOneWidget);

      // 행을 탭하면 상세 시트가 열리고 체결 내역을 불러온다.
      await tester.tap(find.text('매수 삼성전자'));
      await _settle(tester);
      expect(find.text('접수'), findsOneWidget);
      expect(find.text('체결 내역'), findsOneWidget);
      expect(find.text('#1'), findsOneWidget);
      expect(find.textContaining('1주 @'), findsOneWidget);
      expect(harness.countTo('/api/orders/77/executions'), 1);

      // 시트 안에서 취소 → PATCH {status: CANCELED}
      await tester.tap(find.text('주문 취소'));
      await _settle(tester);
      final cancels = harness.requestsTo('/api/orders/77');
      expect(cancels, hasLength(1));
      expect(cancels.single.data, <String, dynamic>{'status': 'CANCELED'});

      // 찜 해제 → DELETE /stocks/likes/9 (위로 스크롤해서 찾는다)
      await tester.scrollUntilVisible(find.byTooltip('찜 해제'),  -300, scrollable: find.byType(Scrollable).first);
      await _settle(tester);
      await tester.tap(find.byTooltip('찜 해제'));
      await _settle(tester);
      expect(harness.countTo('/api/stocks/likes/9'), 1);
    });

    testWidgets('체결 내역이 나오고 포트폴리오 초기화는 확인 후 새 회차로 간다', (
      tester,
    ) async {
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
                    exchangeRates: harness.exchangeRates,
                    reports: harness.reports,
                    theme: ThemeController(),
                  ),
                ),
              ),
            ],
          ),
        ),
      );
      await _settle(tester);

      // 체결 내역: 초기지급·매수 배지와 증감액이 나온다.
      await tester.scrollUntilVisible(find.text('체결 내역'),  300, scrollable: find.byType(Scrollable).first);
      await _settle(tester);
      expect(find.text('초기지급'), findsOneWidget);
      expect(find.text('모의 투자금 지급'), findsOneWidget);
      expect(find.text('삼성전자 매수'), findsOneWidget);
      expect(find.textContaining('-148,148원'), findsOneWidget);

      // 포트폴리오 초기화: 확인 대화상자를 거쳐 POST가 나간다.
      await tester.scrollUntilVisible(
        find.widgetWithText(OutlinedButton, '포트폴리오 초기화'),  300, scrollable: find.byType(Scrollable).first);
      await _settle(tester);
      await tester.tap(
        find.widgetWithText(OutlinedButton, '포트폴리오 초기화'),
      );
      await _settle(tester);
      expect(find.text('포트폴리오를 정말 초기화할까요?'), findsOneWidget);

      await tester.tap(find.text('초기화할게요'));
      await _settle(tester);
      final resets = harness.requestsTo('/api/accounts/me/reset');
      expect(resets, hasLength(1));
      expect(resets.single.data, <String, dynamic>{'accountId': 3});
    });

    testWidgets('닉네임·비밀번호 변경과 회원 탈퇴가 계정 설정에서 된다', (tester) async {
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
                    exchangeRates: harness.exchangeRates,
                    reports: harness.reports,
                    theme: ThemeController(),
                  ),
                ),
              ),
            ],
          ),
        ),
      );
      await _settle(tester);

      // 닉네임 변경 → PATCH users/me + 세션 프로필 갱신.
      await tester.scrollUntilVisible(find.text('계정 설정'),  300, scrollable: find.byType(Scrollable).first);
      await _settle(tester);
      await tester.enterText(
        find.widgetWithText(TextField, '새 닉네임'),
        '새닉네임',
      );
      await tester.tap(find.text('변경').first);
      await _settle(tester);
      final nick = harness.requestsTo('/api/users/me');
      expect(
        nick.any((r) => r.method == 'PATCH' && r.data['nickname'] == '새닉네임'),
        isTrue,
      );
      expect(find.text('닉네임을 변경했어요.'), findsOneWidget);
      expect(harness.session.profile?.nickname, '새닉네임');

      // 비밀번호 변경 → PUT users/me/password.
      // 카드를 통째로 보이게 한 뒤 입력해야 입력→탭 사이에 lazy 항목이
      // dispose·재생성돼 값이 날아가는 걸 막을 수 있다.
      await tester.scrollUntilVisible(
        find.widgetWithText(OutlinedButton, '회원 탈퇴'),
        300,
        scrollable: find.byType(Scrollable).first,
      );
      await _settle(tester);
      await tester.enterText(
        find.widgetWithText(TextField, '현재 비밀번호'),
        'oldpassword1',
      );
      await tester.enterText(
        find.widgetWithText(TextField, '새 비밀번호 (8자 이상)'),
        'newpassword1',
      );
      await tester.enterText(
        find.widgetWithText(TextField, '새 비밀번호 확인'),
        'newpassword1',
      );
      // scrollUntilVisible은 lazy 항목을 빌드할 때까지만 스크롤하고,
      // ensureVisible이 빌드된 요소를 뷰 안으로 온전히 올린다.
      FocusManager.instance.primaryFocus?.unfocus();
      await _settle(tester);
      await tester.scrollUntilVisible(
        find.widgetWithText(FilledButton, '비밀번호 변경'),
        300,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.ensureVisible(
        find.widgetWithText(FilledButton, '비밀번호 변경'),
      );
      await _settle(tester);
      await tester.tap(
        find.widgetWithText(FilledButton, '비밀번호 변경'),
      );
      await _settle(tester);
      expect(harness.countTo('/api/users/me/password'), 1);
      expect(find.text('비밀번호를 변경했어요.'), findsOneWidget);

      // 회원 탈퇴: 비밀번호 대화상자 → DELETE → 로컬 세션 종료.
      await tester.scrollUntilVisible(
        find.widgetWithText(OutlinedButton, '회원 탈퇴'),
        300,
        scrollable: find.byType(Scrollable).first,
      );
      await _settle(tester);
      await tester.tap(
        find.widgetWithText(OutlinedButton, '회원 탈퇴'),
      );
      await _settle(tester);
      expect(find.text('정말 탈퇴할까요?'), findsOneWidget);

      await tester.enterText(
        find.descendant(
          of: find.byType(AlertDialog),
          matching: find.widgetWithText(TextField, '현재 비밀번호'),
        ),
        'oldpassword1',
      );
      await tester.tap(find.text('탈퇴할게요'));
      await _settle(tester);
      expect(harness.countTo('/api/users/me'), greaterThanOrEqualTo(1));
      expect(harness.session.isAuthenticated, isFalse);
    });
  });

  group('투자 성향 리포트', () {
    MaterialApp myApp(TestHarness harness) => MaterialApp.router(
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
                exchangeRates: harness.exchangeRates,
                reports: harness.reports,
                theme: ThemeController(),
              ),
            ),
          ),
        ],
      ),
    );

    testWidgets('분류된 리포트는 유형·4축·스탯과 리더보드 모달을 보여준다', (
      tester,
    ) async {
      final harness = _harness(signedIn: true);
      unawaited(harness.session.restore());
      await tester.pumpWidget(myApp(harness));
      await _settle(tester);

      await tester.scrollUntilVisible(
        find.text('투자 성향 리포트'),
        300,
        scrollable: find.byType(Scrollable).first,
      );
      await _settle(tester);
      expect(find.text('공개'), findsOneWidget);
      expect(find.text('국장 균형러'), findsOneWidget); // DKSB 별명
      expect(find.text('분산·국내·개별주·안정'), findsOneWidget);
      expect(find.text('+4.00%'), findsOneWidget);
      expect(find.text('4주 이상 보유한 종목'), findsOneWidget);

      // 전체 랭킹 모달: 상위권 + 내 순위 주변 + 내 순위 요약.
      await tester.scrollUntilVisible(
        find.text('전체 랭킹 보기'),
        300,
        scrollable: find.byType(Scrollable).first,
      );
      await _settle(tester);
      await tester.tap(find.text('전체 랭킹 보기'));
      await _settle(tester);
      expect(find.text('리더보드'), findsOneWidget);
      expect(find.text('투*왕'), findsOneWidget);
      expect(find.text('이*어'), findsOneWidget); // 내 순위 주변
      expect(find.textContaining('내 순위 12위'), findsOneWidget);
      expect(find.textContaining('유형 안에서 3위'), findsOneWidget);
      await tester.tap(find.text('닫기'));
      await _settle(tester);

      // 유형별 비교 모달: 별명으로 표시하고 내 유형을 표시한다.
      await tester.tap(find.text('유형별 비교 보기'));
      await _settle(tester);
      expect(find.text('유형별 비교'), findsOneWidget);
      expect(find.text('서학개미 스나이퍼'), findsOneWidget); // CGSA 별명
      expect(find.text('내 유형'), findsOneWidget); // DKSB 행
    });

    testWidgets('잠긴 리포트는 잠금 카드와 남은 기간 진행률을 보여준다', (
      tester,
    ) async {
      final harness = _harness(signedIn: true, reportLocked: true);
      unawaited(harness.session.restore());
      await tester.pumpWidget(myApp(harness));
      await _settle(tester);

      await tester.scrollUntilVisible(
        find.text('투자 성향 리포트'),
        300,
        scrollable: find.byType(Scrollable).first,
      );
      await _settle(tester);
      expect(find.text('잠김'), findsOneWidget);
      expect(find.text('투자 성향 리포트는 4주 뒤에 열려요'), findsOneWidget);
      expect(find.text('아직 공개 전이에요'), findsOneWidget);
      expect(find.text('지금은 볼 수 없어요'), findsOneWidget);
      // 잠김 상태에서는 리더보드 버튼이 나오지 않는다.
      expect(find.text('전체 랭킹 보기'), findsNothing);
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

  group('매도 한도', () {
    testWidgets('보유 수량을 넘는 매도는 클라이언트에서 막힌다', (tester) async {
      final harness = _harness(signedIn: true);
      unawaited(harness.session.restore());
      await tester.pumpWidget(
        MaterialApp.router(routerConfig: _router(harness)),
      );
      await _settle(tester);

      await tester.tap(find.text('삼성전자'));
      await _settle(tester);
      await tester.tap(find.text('거래하기'));
      await _settle(tester);

      // 보유 10주 — 11주 매도는 '보유 수량이 부족해요'로 막힌다.
      await tester.tap(find.text('매도'));
      await _settle(tester);
      expect(find.text('보유 10주'), findsOneWidget);

      await tester.enterText(find.byType(TextField).first, '11');
      await _settle(tester);
      expect(find.text('보유 수량이 부족해요'), findsWidgets);

      // 보유 안의 수량은 서버 견적이 executable=true를 내려 '매도하기'가 살아난다.
      await tester.enterText(find.byType(TextField).first, '5');
      await _settle(tester);
      expect(find.text('매도하기'), findsOneWidget);
      expect(harness.countTo('/api/orders/quote/market'), 1);
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

  group('재무제표', () {
    testWidgets('국내 종목은 계산 PER·차트 3개·크게 보기 표를 보여준다', (
      tester,
    ) async {
      final harness = _harness();
      await tester.pumpWidget(
        MaterialApp.router(routerConfig: _router(harness)),
      );
      await _settle(tester);

      await tester.tap(find.text('삼성전자'));
      await _settle(tester);

      await tester.scrollUntilVisible(
        find.text('재무제표'),
        300,
        scrollable: find.byType(Scrollable).first,
      );
      await _settle(tester);

      expect(find.text('재무제표'), findsOneWidget);
      expect(find.text('반도체'), findsOneWidget);
      expect(find.text('계산 PER'), findsOneWidget);
      expect(find.text('12.34배'), findsOneWidget);
      expect(find.text('실적 추이'), findsOneWidget);
      expect(find.text('수익성 추이'), findsOneWidget);
      expect(find.text('재무상태 추이'), findsOneWidget);

      // 크게 보기 → 차트 + 전체 기간 표.
      await tester.tap(find.text('크게 보기').first);
      await _settle(tester);
      expect(find.text('기준월'), findsOneWidget);
      expect(find.text('2024.12'), findsWidgets);
      expect(find.text('영업이익'), findsWidgets);
      await tester.tap(find.text('닫기'));
      await _settle(tester);
    });

    // 폰 너비에서 RenderFlex 오버플로가 나면 pump 단계에서 테스트가 실패한다.
    testWidgets('폰 너비에서 오버플로 없이 렌더링된다', (tester) async {
      tester.view.physicalSize = const Size(1170, 2532);
      tester.view.devicePixelRatio = 3;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      final harness = _harness();
      await tester.pumpWidget(
        MaterialApp.router(routerConfig: _router(harness)),
      );
      await _settle(tester);
      await tester.tap(find.text('삼성전자'));
      await _settle(tester);
      await tester.scrollUntilVisible(
        find.text('재무제표'),
        300,
        scrollable: find.byType(Scrollable).first,
      );
      await _settle(tester);

      await tester.tap(find.text('크게 보기').first);
      await _settle(tester);
      expect(find.text('기준월'), findsOneWidget);
      await tester.tap(find.text('닫기'));
      await _settle(tester);
    });

    testWidgets('미지원 종목(NVDA)은 재무제표 섹션이 숨는다', (tester) async {
      final harness = _harness();
      await tester.pumpWidget(
        MaterialApp.router(routerConfig: _router(harness)),
      );
      await _settle(tester);

      await tester.tap(find.text('해외 주식'));
      await _settle(tester);
      await tester.tap(find.text('엔비디아'));
      await _settle(tester);

      expect(find.text('엔비디아'), findsWidgets);
      expect(find.text('재무제표'), findsNothing);
    });
  });

  group('캔들 차트', () {
    testWidgets('봉 단위·기간 토글이 웹과 같은 조합으로 쿼리한다', (tester) async {
      final harness = _harness();
      await tester.pumpWidget(
        MaterialApp.router(routerConfig: _router(harness)),
      );
      await _settle(tester);

      await tester.tap(find.text('삼성전자'));
      await _settle(tester);

      // 웹 초기값: 일봉 · 6개월. 단위 탭 5개 + 일봉의 기간 탭 3개.
      expect(find.text('1분봉'), findsOneWidget);
      expect(find.text('5분봉'), findsOneWidget);
      expect(find.text('10분봉'), findsOneWidget);
      expect(find.text('일봉'), findsOneWidget);
      expect(find.text('1주봉'), findsOneWidget);
      expect(find.text('1개월'), findsOneWidget);
      expect(find.text('6개월'), findsOneWidget);
      expect(find.text('1년'), findsOneWidget);
      // 마지막 봉 날짜 라벨(픽스처 at=2026-09-15 KST).
      expect(find.textContaining('종가까지'), findsOneWidget);

      final initial = harness
          .requestsTo('/api/stocks/005930/candles')
          .last;
      expect(initial.uri.queryParameters['interval'], '1d');
      expect(initial.uri.queryParameters['range'], '6M');

      // 5분봉 → 1일/1주일 기간 탭, interval=5m.
      await tester.tap(find.text('5분봉'));
      await _settle(tester);
      expect(find.text('1일'), findsOneWidget);
      expect(find.text('1주일'), findsOneWidget);
      expect(find.text('1개월'), findsNothing);
      var req = harness.requestsTo('/api/stocks/005930/candles').last;
      expect(req.uri.queryParameters['interval'], '5m');
      expect(req.uri.queryParameters['range'], '1D');

      await tester.tap(find.text('1주일'));
      await _settle(tester);
      req = harness.requestsTo('/api/stocks/005930/candles').last;
      expect(req.uri.queryParameters['interval'], '5m');
      expect(req.uri.queryParameters['range'], '1W');

      // 1분봉은 기간이 하나뿐이라 토글이 숨고 "최근 N봉" 라벨이 뜬다.
      await tester.tap(find.text('1분봉'));
      await _settle(tester);
      expect(find.text('1일'), findsNothing);
      expect(find.textContaining('최근'), findsOneWidget);
      req = harness.requestsTo('/api/stocks/005930/candles').last;
      expect(req.uri.queryParameters['interval'], '1m');
      expect(req.uri.queryParameters['range'], '1D');
    });

    testWidgets('차트 크게보기 모달이 같은 토글 상태를 공유한다', (tester) async {
      final harness = _harness();
      await tester.pumpWidget(
        MaterialApp.router(routerConfig: _router(harness)),
      );
      await _settle(tester);

      await tester.tap(find.text('삼성전자'));
      await _settle(tester);

      await tester.tap(find.text('차트 크게보기'));
      await _settle(tester);
      // 웹 모달처럼 헤더에 종목명+심볼이 뜬다.
      expect(find.byType(Dialog), findsOneWidget);
      expect(find.text('닫기'), findsOneWidget);

      // 모달에서 단위를 바꾸면 쿼리가 나가고, 닫은 뒤에도 상태가 유지된다.
      // 카드에도 같은 탭이 있어 모달 안쪽을 명시한다.
      await tester.tap(
        find.descendant(
          of: find.byType(Dialog),
          matching: find.text('1분봉'),
        ),
      );
      await _settle(tester);
      var req = harness.requestsTo('/api/stocks/005930/candles').last;
      expect(req.uri.queryParameters['interval'], '1m');

      await tester.tap(find.text('닫기'));
      await _settle(tester);
      expect(find.byType(Dialog), findsNothing);
      // 모달에서 고른 1분봉이 카드에도 남아 있다(기간 토글 숨김 상태).
      expect(find.text('1일'), findsNothing);
      expect(find.textContaining('최근'), findsOneWidget);
    });
  });

  group('시세 폴링', () {
    testWidgets('장이 열린 랭킹은 5초마다 다시 조회하고 마감 땐 조용하다', (
      tester,
    ) async {
      final harness = _harness();
      await tester.pumpWidget(
        MaterialApp.router(routerConfig: _router(harness)),
      );
      await _settle(tester);

      // fixture의 KR은 open=true — 5초 뒤 랭킹 재조회.
      final krBefore = harness.countTo('/api/stocks/rankings');
      await tester.pump(const Duration(seconds: 5));
      await _settle(tester);
      expect(
        harness.countTo('/api/stocks/rankings'),
        greaterThan(krBefore),
      );

      // US는 open=false — 해외 탭으로 바꿔도 랭킹 재조회가 5초마다 나가지 않는다.
      await tester.tap(find.text('해외 주식'));
      await _settle(tester);
      final usBefore = harness.countTo('/api/stocks/rankings');
      await tester.pump(const Duration(seconds: 6));
      await _settle(tester);
      expect(harness.countTo('/api/stocks/rankings'), usBefore);
    });

    testWidgets('실시간 종목 상세는 5초마다 갱신하고 종가 종목은 갱신하지 않는다', (
      tester,
    ) async {
      final harness = _harness();
      await tester.pumpWidget(
        MaterialApp.router(routerConfig: _router(harness)),
      );
      await _settle(tester);

      // realtime=true 삼성전자 → 5초 폴링.
      await tester.tap(find.text('삼성전자'));
      await _settle(tester);
      final krBefore = harness.countTo('/api/stocks/005930');
      await tester.pump(const Duration(seconds: 5));
      await _settle(tester);
      expect(harness.countTo('/api/stocks/005930'), greaterThan(krBefore));

      // realtime=false 엔비디아(장 마감) → 폴링하지 않는다.
      await tester.pageBack();
      await _settle(tester);
      await tester.tap(find.text('해외 주식'));
      await _settle(tester);
      await tester.tap(find.text('엔비디아'));
      await _settle(tester);
      final usBefore = harness.countTo('/api/stocks/NVDA');
      await tester.pump(const Duration(seconds: 6));
      await _settle(tester);
      expect(harness.countTo('/api/stocks/NVDA'), usBefore);
    });
  });
}
