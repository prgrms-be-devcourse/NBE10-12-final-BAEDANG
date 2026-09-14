import 'package:dio/dio.dart';

import '../models/market_country.dart';
import '../models/ranking.dart';
import 'api_client.dart';
import 'auth_requirement.dart';

/// 종목 조회 API. 검색·상세·차트는 화면이 필요해질 때 이어서 추가한다.
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
}
