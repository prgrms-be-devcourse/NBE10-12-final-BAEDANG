import 'package:dio/dio.dart';

import '../models/account_summary.dart';
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
}
