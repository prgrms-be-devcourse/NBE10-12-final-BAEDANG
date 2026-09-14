import 'package:dio/dio.dart';

import '../models/account_summary.dart';
import '../models/holding.dart';
import '../models/ledger.dart';
import '../models/order_detail.dart';
import 'api_client.dart';
import 'auth_requirement.dart';

/// 계좌 API. 보유·원장·주문 목록은 화면이 필요해질 때 이어서 추가한다.
class AccountApi {
  AccountApi(this._client);

  static const String _me = 'accounts/me';

  final ApiClient _client;

  Future<AccountSummary> getAccountSummary({CancelToken? cancelToken}) async {
    final json = await _client.getObject(
      _me,
      auth: AuthRequirement.required,
      cancelToken: cancelToken,
    );
    return _client.decode(() => AccountSummary.fromJson(json));
  }

  /// 체결/원장 내역. entryId 기준 커서 페이징(기본 20건).
  Future<LedgerPage> getLedger({
    String? cursor,
    int size = 20,
    String? entryType,
    CancelToken? cancelToken,
  }) async {
    final json = await _client.getObject(
      'accounts/me/ledger',
      auth: AuthRequirement.required,
      query: <String, dynamic>{
        'size': size,
        'cursor': ?cursor,
        'entryType': ?entryType,
      },
      cancelToken: cancelToken,
    );
    return _client.decode(() => LedgerPage.fromJson(json));
  }

  /// 포트폴리오 초기화 — 새 회차 계좌 개설. 되돌릴 수 없다.
  Future<AccountReset> resetAccount({
    required int accountId,
    CancelToken? cancelToken,
  }) async {
    final json = await _client.postObject(
      'accounts/me/reset',
      auth: AuthRequirement.required,
      body: {'accountId': accountId},
      cancelToken: cancelToken,
    );
    return _client.decode(() => AccountReset.fromJson(json));
  }

  /// 보유 종목 목록. 평가금액·평가손익은 서버가 원화 환산까지 끝내서 내려준다.
  Future<Holdings> getHoldings({CancelToken? cancelToken}) async {
    final json = await _client.getObject(
      'accounts/me/holdings',
      auth: AuthRequirement.required,
      cancelToken: cancelToken,
    );
    return _client.decode(() => Holdings.fromJson(json));
  }

  /// 현재 라운드 주문 내역. 최신순 커서 페이징.
  Future<OrderPage> getOrders({
    String? cursor,
    int size = 20,
    CancelToken? cancelToken,
  }) async {
    final json = await _client.getObject(
      'accounts/me/orders',
      auth: AuthRequirement.required,
      query: <String, dynamic>{'size': size, 'cursor': ?cursor},
      cancelToken: cancelToken,
    );
    return _client.decode(() => OrderPage.fromJson(json));
  }
}
