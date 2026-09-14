import 'package:dio/dio.dart';

import '../models/candle.dart';
import '../models/json_read.dart';
import '../models/market_country.dart';
import '../models/order_book.dart';
import '../models/ranking.dart';
import '../models/stock_detail.dart';
import '../models/stock_search.dart';
import 'api_client.dart';
import 'auth_requirement.dart';

/// 종목 조회·찜 API.
class StockApi {
  StockApi(this._client);

  static const String _rankings = 'stocks/rankings';

  final ApiClient _client;

  /// 랭킹은 선택적 인증이다. 로그인 상태면 찜 여부(stockLikeId)까지 내려온다.
  Future<RankingPage> getRankings({
    required MarketCountry market,
    int size = 20,
    String? cursor,
    CancelToken? cancelToken,
  }) async {
    if (market == MarketCountry.unknown) {
      throw ArgumentError.value(market, 'market', 'KR 또는 US여야 해요');
    }
    final json = await _client.getObject(
      _rankings,
      auth: AuthRequirement.optional,
      query: <String, dynamic>{
        'market': market.wireValue,
        'size': size,
        'cursor': ?cursor,
      },
      cancelToken: cancelToken,
    );
    return _client.decode(() => RankingPage.fromJson(json));
  }

  /// 종목 상세. 비로그인도 조회 가능하다.
  Future<StockDetail> getDetail({
    required String symbol,
    required MarketCountry marketCountry,
    CancelToken? cancelToken,
  }) async {
    final json = await _client.getObject(
      'stocks/$symbol',
      query: {'marketCountry': marketCountry.wireValue},
      cancelToken: cancelToken,
    );
    return _client.decode(() => StockDetail.fromJson(json));
  }

  /// 종목 검색. 비로그인도 조회 가능하다.
  Future<List<StockSearchItem>> search({
    required String query,
    int size = 10,
    CancelToken? cancelToken,
  }) async {
    final json = await _client.getObject(
      'stocks/search',
      query: {'q': query, 'size': size},
      cancelToken: cancelToken,
    );
    return _client.decode(
      () => json
          .requireObjectList('items')
          .map(StockSearchItem.fromJson)
          .toList(growable: false),
    );
  }

  /// 캔들 차트 데이터. 비로그인도 조회 가능하다.
  Future<CandleSeries> getCandles({
    required String symbol,
    required MarketCountry marketCountry,
    required String interval,
    required String range,
    CancelToken? cancelToken,
  }) async {
    final json = await _client.getObject(
      'stocks/$symbol/candles',
      query: {
        'marketCountry': marketCountry.wireValue,
        'interval': interval,
        'range': range,
      },
      cancelToken: cancelToken,
    );
    return _client.decode(() => CandleSeries.fromJson(json));
  }

  /// 호가창. 비로그인도 조회 가능하다.
  Future<OrderBook> getOrderBook({
    required String symbol,
    required MarketCountry marketCountry,
    CancelToken? cancelToken,
  }) async {
    final json = await _client.getObject(
      'stocks/$symbol/orderbook',
      query: {'marketCountry': marketCountry.wireValue},
      cancelToken: cancelToken,
    );
    return _client.decode(() => OrderBook.fromJson(json));
  }

  /// 찜 추가. 로그인이 필요하고 stockLikeId를 돌려준다.
  Future<int> like({
    required int stockId,
    CancelToken? cancelToken,
  }) async {
    final json = await _client.postObject(
      'stocks/likes',
      body: {'stockId': stockId},
      auth: AuthRequirement.required,
      cancelToken: cancelToken,
    );
    return _client.decode(() => json.requireInt('stockLikeId'));
  }

  /// 찜 해제. 로그인이 필요하다.
  Future<void> unlike({
    required int stockLikeId,
    CancelToken? cancelToken,
  }) async {
    await _client.deleteVoid(
      'stocks/likes/$stockLikeId',
      auth: AuthRequirement.required,
      cancelToken: cancelToken,
    );
  }
}
