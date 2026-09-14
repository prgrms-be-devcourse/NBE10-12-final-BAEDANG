import 'package:dio/dio.dart';

import '../models/market_status.dart';
import 'api_client.dart';
import 'auth_requirement.dart';

/// 시장 운영 정보 API.
class MarketApi {
  MarketApi(this._client);

  static const String _status = 'market/status';

  final ApiClient _client;

  Future<MarketStatus> getMarketStatus({CancelToken? cancelToken}) async {
    final json = await _client.getObject(
      _status,
      auth: AuthRequirement.public,
      cancelToken: cancelToken,
    );
    return _client.decode(() => MarketStatus.fromJson(json));
  }
}
