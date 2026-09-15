import 'package:dio/dio.dart';

import '../models/exchange_rate.dart';
import 'api_client.dart';

/// 환율 조회 API. 비로그인도 조회 가능하다.
class ExchangeRateApi {
  ExchangeRateApi(this._client);

  final ApiClient _client;

  /// 최신 환율(랭킹 배너). base/quote 생략 시 USD/KRW.
  /// 대문자로 맞춰 보낸다 — `?base=usd`와 `?base=USD`가 서로 다른 캐시 키로
  /// 취급돼 캐시가 갈라지는 것을 막기 위해서다(웹 구현과 같은 규칙).
  Future<ExchangeRateLatest> getLatest({
    String base = 'USD',
    String quote = 'KRW',
    CancelToken? cancelToken,
  }) async {
    final json = await _client.getObject(
      'exchange-rates/latest',
      query: {'base': base.toUpperCase(), 'quote': quote.toUpperCase()},
      cancelToken: cancelToken,
    );
    return _client.decode(() => ExchangeRateLatest.fromJson(json));
  }

  /// 환율 추이 그래프. 백엔드는 MVP에서 USD/KRW만 다루고 period는 필수다.
  /// period: 1d | 1w | 1m | 3m | 1y
  Future<ExchangeRateHistory> getHistory({
    required String period,
    CancelToken? cancelToken,
  }) async {
    final json = await _client.getObject(
      'exchange-rates/history',
      query: {'period': period},
      cancelToken: cancelToken,
    );
    return _client.decode(() => ExchangeRateHistory.fromJson(json));
  }
}
