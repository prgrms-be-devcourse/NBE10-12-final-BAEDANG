import 'package:dio/dio.dart';

import '../models/market_event.dart';
import '../models/market_status.dart';
import 'api_client.dart';
import 'auth_requirement.dart';

/// 시장 운영 정보 API.
class MarketApi {
  MarketApi(this._client);

  static const String _status = 'market/status';
  static const String _events = 'market/events';

  final ApiClient _client;

  Future<MarketStatus> getMarketStatus({CancelToken? cancelToken}) async {
    final json = await _client.getObject(
      _status,
      auth: AuthRequirement.public,
      cancelToken: cancelToken,
    );
    return _client.decode(() => MarketStatus.fromJson(json));
  }

  /// `GET /api/market/events?market=&date=` — KOSPI/KOSDAQ 시장조치(공개).
  /// `date`는 KST 기준 `yyyy-MM-dd`.
  Future<MarketEvents> getMarketEvents(
    String market,
    String date, {
    CancelToken? cancelToken,
  }) async {
    final json = await _client.getObject(
      _events,
      query: <String, String>{'market': market, 'date': date},
      auth: AuthRequirement.public,
      cancelToken: cancelToken,
    );
    return _client.decode(() => MarketEvents.fromJson(json));
  }
}
