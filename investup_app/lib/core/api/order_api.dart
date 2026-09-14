import 'dart:math';

import 'package:dio/dio.dart';

import '../models/market_country.dart';
import '../models/order_detail.dart';
import '../models/order_quote.dart';
import 'api_client.dart';
import 'auth_requirement.dart';

/// 주문 API. 모의 거래 범위 안에서만 호출한다.
///
/// [newClientOrderId]는 주문 의도 하나를 식별한다. 같은 의도의 재시도는
/// 같은 값을 쓰고, 새 주문 의도에는 새 값을 만든다. 서버가 UUID로 파싱하므로
/// 반드시 UUID 형식이어야 한다.
class OrderApi {
  OrderApi(this._client);

  final ApiClient _client;

  /// 시장가 주문 견적. 주문 전 잔고·수수료를 미리 보여준다.
  Future<MarketOrderQuote> getMarketQuote({
    required String symbol,
    required MarketCountry marketCountry,
    required String side,
    required String quantity,
    CancelToken? cancelToken,
  }) async {
    final json = await _client.getObject(
      'orders/quote/market',
      auth: AuthRequirement.required,
      query: {
        'symbol': symbol,
        'marketCountry': marketCountry.wireValue,
        'side': side,
        'quantity': quantity,
      },
      cancelToken: cancelToken,
    );
    return _client.decode(() => MarketOrderQuote.fromJson(json));
  }

  /// 시장가 주문 제출. [clientOrderId]는 이 주문 의도에 고정된 UUID다.
  Future<MarketOrderResult> placeMarketOrder({
    required int accountId,
    required String clientOrderId,
    required String symbol,
    required MarketCountry marketCountry,
    required String side,
    required String quantity,
    CancelToken? cancelToken,
  }) async {
    final json = await _client.postObject(
      'orders/market',
      auth: AuthRequirement.required,
      body: {
        'accountId': accountId,
        'clientOrderId': clientOrderId,
        'symbol': symbol,
        'marketCountry': marketCountry.wireValue,
        'side': side,
        'quantity': quantity,
      },
      cancelToken: cancelToken,
    );
    return _client.decode(() => MarketOrderResult.fromJson(json));
  }

  /// 지정가 주문 견적. limitPrice는 종목 통화(KRW/USD)로 입력한다.
  Future<LimitOrderQuote> getLimitQuote({
    required String symbol,
    required MarketCountry marketCountry,
    required String side,
    required String quantity,
    required String limitPrice,
    required String limitCurrency,
    CancelToken? cancelToken,
  }) async {
    final json = await _client.getObject(
      'orders/quote/limit',
      auth: AuthRequirement.required,
      query: {
        'symbol': symbol,
        'marketCountry': marketCountry.wireValue,
        'side': side,
        'quantity': quantity,
        'limitPrice': limitPrice,
        'limitCurrency': limitCurrency,
      },
      cancelToken: cancelToken,
    );
    return _client.decode(() => LimitOrderQuote.fromJson(json));
  }

  /// 지정가 주문 접수. [clientOrderId]는 이 주문 의도에 고정된 UUID다.
  Future<OrderDetail> placeLimitOrder({
    required int accountId,
    required String clientOrderId,
    required String symbol,
    required MarketCountry marketCountry,
    required String side,
    required String quantity,
    required String limitPrice,
    required String limitCurrency,
    CancelToken? cancelToken,
  }) async {
    final json = await _client.postObject(
      'orders/limit',
      auth: AuthRequirement.required,
      body: {
        'accountId': accountId,
        'clientOrderId': clientOrderId,
        'symbol': symbol,
        'marketCountry': marketCountry.wireValue,
        'side': side,
        'quantity': quantity,
        'limitPrice': limitPrice,
        'limitCurrency': limitCurrency,
      },
      cancelToken: cancelToken,
    );
    return _client.decode(() => OrderDetail.fromJson(json));
  }

  /// 미체결 지정가 주문 취소. 서버는 {status: "CANCELED"}만 받는다.
  Future<OrderDetail> cancelOrder({
    required int orderId,
    CancelToken? cancelToken,
  }) async {
    final json = await _client.patchObject(
      'orders/$orderId',
      auth: AuthRequirement.required,
      body: const {'status': 'CANCELED'},
      cancelToken: cancelToken,
    );
    return _client.decode(() => OrderDetail.fromJson(json));
  }
}

/// UUID v4 생성. 의존성 없이 Random.secure()로 만든다.
String newClientOrderId() {
  final random = Random.secure();
  final bytes = List<int>.generate(16, (_) => random.nextInt(256));
  bytes[6] = (bytes[6] & 0x0f) | 0x40; // version 4
  bytes[8] = (bytes[8] & 0x3f) | 0x80; // variant 1
  String hex(int b) => b.toRadixString(16).padLeft(2, '0');
  final s = bytes.map(hex).join();
  return '${s.substring(0, 8)}-${s.substring(8, 12)}-'
      '${s.substring(12, 16)}-${s.substring(16, 20)}-${s.substring(20)}';
}
