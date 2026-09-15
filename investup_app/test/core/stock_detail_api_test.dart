import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:investup_app/core/api/api_error.dart';
import 'package:investup_app/core/api/order_api.dart';
import 'package:investup_app/core/models/market_country.dart';

import 'fakes/fake_http_adapter.dart';
import 'fakes/test_harness.dart';

Map<String, Object?> _detailJson() => <String, Object?>{
  'symbol': '005930',
  'name': '삼성전자',
  'englishName': 'Samsung Electronics',
  'market': 'KOSPI',
  'marketCountry': 'KR',
  'currency': 'KRW',
  'isinCode': 'KR7005930003',
  'category': 'INDIVIDUAL',
  'leverageFactor': null,
  'isDividend': true,
  'price': <String, Object?>{
    'lastPrice': '74500',
    'prevClose': '74000',
    'changeAmount': '500',
    'changeRate': '0.006757',
    'upperLimit': '96200',
    'lowerLimit': '51800',
    'quoteAt': '2025-01-15T15:30:00+09:00',
    'realtime': true,
  },
  'info': <String, Object?>{
    'marketCap': '445000000000000',
    'sharesOutstanding': '5969782550',
    'listDate': '1975-06-11',
  },
  'warnings': <Object?>[
    <String, Object?>{'type': 'INVESTMENT_CAUTION', 'label': '투자주의'},
  ],
  'warningsStatus': 'AVAILABLE',
  'tradable': true,
  'tradableReason': null,
};

Map<String, Object?> _candlesJson() => <String, Object?>{
  'symbol': '005930',
  'interval': '1d',
  'range': '1M',
  'currency': 'KRW',
  'items': <Object?>[
    <String, Object?>{
      'at': '2025-01-15T00:00:00+09:00',
      'open': '74000',
      'high': '75000',
      'low': '73800',
      'close': '74500',
      'volume': '12345678',
    },
  ],
};

Map<String, Object?> _orderBookJson() => <String, Object?>{
  'symbol': '005930',
  'marketCountry': 'KR',
  'bookVersion': 2,
  'revision': 5,
  'basePrice': '74500',
  'currency': 'KRW',
  'quoteAt': '2025-01-15T15:30:00Z',
  'generatedAt': '2025-01-15T15:30:00Z',
  'virtual': true,
  'description': '현재가 기반 가상 호가·가상 잔량',
  'asks': <Object?>[
    <String, Object?>{'level': 1, 'price': '74600', 'quantity': '100'},
  ],
  'bids': <Object?>[
    <String, Object?>{'level': 1, 'price': '74400', 'quantity': '200'},
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
  'quoteAt': '2025-01-15T15:30:00+09:00',
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
  'exchangeRate': '1',
  'grossAmount': '149000',
  'fee': '149',
  'tax': '0',
  'netAmount': '149149',
  'quoteAt': '2025-01-15T15:30:00+09:00',
  'orderedAt': '2025-01-15T15:30:01+09:00',
  'account': <String, Object?>{'cashBalanceAfter': '49850851'},
};

void main() {
  test('종목 상세는 marketCountry 쿼리와 함께 공개 조회한다', () async {
    RequestOptions? recorded;
    final harness = TestHarness(
      adapter: FakeHttpAdapter((options) async {
        recorded = options;
        return FakeResponse.ok(_detailJson());
      }),
    );
    // 로그인 상태여도 공개 요청은 토큰을 붙이지 않는다.
    await harness.signIn(accessToken: 'access-token');

    final detail = await harness.stocks.getDetail(
      symbol: '005930',
      marketCountry: MarketCountry.kr,
    );

    expect(recorded!.uri.path, '/api/stocks/005930');
    expect(recorded!.uri.queryParameters['marketCountry'], 'KR');
    expect(recorded!.headers.containsKey('Authorization'), isFalse);
    expect(detail.name, '삼성전자');
    expect(detail.tradable, isTrue);
    expect(detail.price!.lastPrice, '74500');
    expect(detail.price!.realtime, isTrue);
    expect(detail.warnings.single.label, '투자주의');
  });

  test('검색은 q/size 쿼리를 보내고 items를 파싱한다', () async {
    RequestOptions? recorded;
    final harness = TestHarness(
      adapter: FakeHttpAdapter((options) async {
        recorded = options;
        return FakeResponse.ok(<String, Object?>{
          'items': <Object?>[
            <String, Object?>{
              'symbol': '005930',
              'name': '삼성전자',
              'englishName': 'Samsung Electronics',
              'market': 'KOSPI',
              'marketCountry': 'KR',
              'category': 'INDIVIDUAL',
            },
          ],
        });
      }),
    );

    final items = await harness.stocks.search(query: '삼성', size: 5);

    expect(recorded!.uri.path, '/api/stocks/search');
    expect(recorded!.uri.queryParameters['q'], '삼성');
    expect(recorded!.uri.queryParameters['size'], '5');
    expect(items.single.symbol, '005930');
    expect(items.single.marketCountry, MarketCountry.kr);
  });

  test('캔들은 interval/range 쿼리를 보낸다', () async {
    RequestOptions? recorded;
    final harness = TestHarness(
      adapter: FakeHttpAdapter((options) async {
        recorded = options;
        return FakeResponse.ok(_candlesJson());
      }),
    );

    final series = await harness.stocks.getCandles(
      symbol: '005930',
      marketCountry: MarketCountry.kr,
      interval: '1d',
      range: '1M',
    );

    expect(recorded!.uri.path, '/api/stocks/005930/candles');
    expect(recorded!.uri.queryParameters['interval'], '1d');
    expect(recorded!.uri.queryParameters['range'], '1M');
    expect(series.items.single.close, '74500');
  });

  test('캔들 items 누락은 빈 차트가 아니라 파싱 오류다', () async {
    final harness = TestHarness(
      adapter: FakeHttpAdapter(
        (_) async => FakeResponse.ok(<String, Object?>{
          'symbol': '005930',
          'interval': '1d',
          'range': '1M',
        }),
      ),
    );

    expect(
      () => harness.stocks.getCandles(
        symbol: '005930',
        marketCountry: MarketCountry.kr,
        interval: '1d',
        range: '1M',
      ),
      throwsA(
        isA<ApiException>().having(
          (e) => e.error.kind,
          'kind',
          ApiErrorKind.unexpected,
        ),
      ),
    );
  });

  test('호가는 virtual 여부와 asks/bids를 파싱한다', () async {
    final harness = TestHarness(
      adapter: FakeHttpAdapter((_) async => FakeResponse.ok(_orderBookJson())),
    );

    final book = await harness.stocks.getOrderBook(
      symbol: '005930',
      marketCountry: MarketCountry.kr,
    );

    expect(book.virtual, isTrue);
    expect(book.basePrice, '74500');
    expect(book.asks.single.price, '74600');
    expect(book.bids.single.quantity, '200');
  });

  test('찜 추가는 인증 POST, 해제는 인증 DELETE다', () async {
    final requests = <RequestOptions>[];
    final harness = TestHarness(
      adapter: FakeHttpAdapter((options) async {
        requests.add(options);
        if (options.method == 'POST') {
          return FakeResponse.ok(<String, Object?>{'stockLikeId': 77});
        }
        return FakeResponse(204, null);
      }),
    );
    await harness.signIn(accessToken: 'access-token');

    final likeId = await harness.stocks.like(stockId: 101);
    await harness.stocks.unlike(stockLikeId: likeId);

    expect(requests[0].method, 'POST');
    expect(requests[0].uri.path, '/api/stocks/likes');
    expect(requests[0].data, <String, dynamic>{'stockId': 101});
    expect(bearerTokenOf(requests[0]), 'access-token');
    expect(requests[1].method, 'DELETE');
    expect(requests[1].uri.path, '/api/stocks/likes/77');
    expect(bearerTokenOf(requests[1]), 'access-token');
  });

  test('시장가 견적은 인증과 함께 symbol/side/quantity 쿼리를 보낸다', () async {
    RequestOptions? recorded;
    final harness = TestHarness(
      adapter: FakeHttpAdapter((options) async {
        recorded = options;
        return FakeResponse.ok(_quoteJson());
      }),
    );
    await harness.signIn(accessToken: 'access-token');

    final quote = await harness.orders.getMarketQuote(
      symbol: '005930',
      marketCountry: MarketCountry.kr,
      side: 'BUY',
      quantity: '2',
    );

    expect(recorded!.uri.path, '/api/orders/quote/market');
    expect(recorded!.uri.queryParameters['side'], 'BUY');
    expect(recorded!.uri.queryParameters['quantity'], '2');
    expect(bearerTokenOf(recorded!), 'access-token');
    expect(quote.executable, isTrue);
    expect(quote.netAmount, '149149');
  });

  test('시장가 주문은 accountId와 clientOrderId를 본문에 담는다', () async {
    RequestOptions? recorded;
    final harness = TestHarness(
      adapter: FakeHttpAdapter((options) async {
        recorded = options;
        return FakeResponse.ok(_orderResultJson());
      }),
    );
    await harness.signIn(accessToken: 'access-token');

    final result = await harness.orders.placeMarketOrder(
      accountId: 7,
      clientOrderId: '018f2c9e-4a1b-7c3d-9e5f-1a2b3c4d5e6f',
      symbol: '005930',
      marketCountry: MarketCountry.kr,
      side: 'BUY',
      quantity: '2',
    );

    expect(recorded!.uri.path, '/api/orders/market');
    expect(recorded!.data, <String, dynamic>{
      'accountId': 7,
      'clientOrderId': '018f2c9e-4a1b-7c3d-9e5f-1a2b3c4d5e6f',
      'symbol': '005930',
      'marketCountry': 'KR',
      'side': 'BUY',
      'quantity': '2',
    });
    expect(result.orderId, 42);
    expect(result.filled, isTrue);
    expect(result.cashBalanceAfter, '49850851');
  });

  test('clientOrderId는 UUID v4 형식이고 호출마다 다르다', () {
    final pattern = RegExp(
      r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$',
    );
    final a = newClientOrderId();
    final b = newClientOrderId();
    expect(a, matches(pattern));
    expect(b, matches(pattern));
    expect(a, isNot(b));
  });
}
