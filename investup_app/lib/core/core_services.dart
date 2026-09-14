import 'package:dio/dio.dart';

import 'api/account_api.dart';
import 'api/api_client.dart';
import 'api/api_config.dart';
import 'api/auth_api.dart';
import 'api/market_api.dart';
import 'api/order_api.dart';
import 'api/stock_api.dart';
import 'auth/auth_session.dart';
import 'auth/token_manager.dart';
import 'auth/token_storage.dart';

/// core 조립 지점. 화면은 여기서 [AuthSession]과 필요한 API만 받아 쓴다.
///
/// 사용 예:
///   final core = CoreServices(config: ApiConfig(baseUrl: apiBaseUrl),
///       storage: SecureTokenStorage());
///   final session = core.createAuthSession();
///   await session.restore();
class CoreServices {
  factory CoreServices({
    required ApiConfig config,
    required TokenStorage storage,
    Dio? dio,
    Dio? refreshDio,
  }) {
    final tokens = TokenManager(storage: storage);
    final client = ApiClient(
      config: config,
      tokens: tokens,
      dio: dio,
      refreshDio: refreshDio,
    );
    return CoreServices._(
      tokens: tokens,
      client: client,
      auth: AuthApi(client),
      market: MarketApi(client),
      stocks: StockApi(client),
      account: AccountApi(client),
      orders: OrderApi(client),
    );
  }

  CoreServices._({
    required this.tokens,
    required this.client,
    required this.auth,
    required this.market,
    required this.stocks,
    required this.account,
    required this.orders,
  });

  final TokenManager tokens;
  final ApiClient client;
  final AuthApi auth;
  final MarketApi market;
  final StockApi stocks;
  final AccountApi account;
  final OrderApi orders;

  AuthSession createAuthSession() =>
      AuthSession(client: client, authApi: auth, accountApi: account);
}
