import 'package:flutter/material.dart';

import 'app.dart';
import 'core/api/api_config.dart';
import 'core/auth/token_storage.dart';
import 'core/core_services.dart';
import 'environment.dart';

void main() {
  final core = CoreServices(
    config: ApiConfig(baseUrl: apiBaseUrl()),
    storage: SecureTokenStorage(),
  );
  runApp(
    InvestUpApp(
      session: core.createAuthSession(),
      market: core.market,
      stocks: core.stocks,
      account: core.account,
    ),
  );
}
