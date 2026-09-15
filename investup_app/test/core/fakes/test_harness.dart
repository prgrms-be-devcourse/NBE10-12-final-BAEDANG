import 'package:dio/dio.dart';
import 'package:investup_app/core/api/account_api.dart';
import 'package:investup_app/core/api/api_client.dart';
import 'package:investup_app/core/api/api_config.dart';
import 'package:investup_app/core/api/auth_api.dart';
import 'package:investup_app/core/api/exchange_rate_api.dart';
import 'package:investup_app/core/api/market_api.dart';
import 'package:investup_app/core/api/order_api.dart';
import 'package:investup_app/core/api/report_api.dart';
import 'package:investup_app/core/api/stock_api.dart';
import 'package:investup_app/core/auth/auth_session.dart';
import 'package:investup_app/core/auth/token_manager.dart';

import 'fake_http_adapter.dart';
import 'fake_token_storage.dart';

/// 테스트용 조립. 실제 Dio 파이프라인과 인터셉터를 그대로 쓴다.
class TestHarness {
  TestHarness({
    FakeHttpAdapter? adapter,
    FakeTokenStorage? storage,
    String baseUrl = 'http://10.0.2.2:8080/api',
  }) : storage = storage ?? FakeTokenStorage(),
       adapter =
           adapter ??
           FakeHttpAdapter((_) async => FakeResponse(200, const {})) {
    tokens = TokenManager(storage: this.storage);
    client = ApiClient(
      config: ApiConfig(baseUrl: baseUrl),
      tokens: tokens,
    );
    client.dio.httpClientAdapter = this.adapter;
    client.refreshDio.httpClientAdapter = this.adapter;
    auth = AuthApi(client);
    market = MarketApi(client);
    stocks = StockApi(client);
    account = AccountApi(client);
    orders = OrderApi(client);
    exchangeRates = ExchangeRateApi(client);
    reports = ReportApi(client);
    session = AuthSession(client: client, authApi: auth, accountApi: account);
  }

  final FakeTokenStorage storage;
  final FakeHttpAdapter adapter;

  late final TokenManager tokens;
  late final ApiClient client;
  late final AuthApi auth;
  late final MarketApi market;
  late final StockApi stocks;
  late final AccountApi account;
  late final OrderApi orders;
  late final ExchangeRateApi exchangeRates;
  late final ReportApi reports;
  late final AuthSession session;

  /// 이미 로그인된 상태를 만든다.
  Future<void> signIn({
    String accessToken = 'access-token',
    String refreshToken = 'refresh-token',
  }) async {
    await tokens.startSession(
      accessToken: accessToken,
      refreshToken: refreshToken,
      generation: tokens.generation,
    );
  }

  List<RequestOptions> requestsTo(String pathSuffix) =>
      adapter.requestsTo(pathSuffix);

  int countTo(String pathSuffix) => adapter.countTo(pathSuffix);
}

/// 인증 헤더에서 토큰만 뽑는다(테스트 단언용).
String? bearerTokenOf(RequestOptions options) {
  final header = options.headers['Authorization'];
  if (header is! String || !header.startsWith('Bearer ')) return null;
  return header.substring('Bearer '.length);
}
