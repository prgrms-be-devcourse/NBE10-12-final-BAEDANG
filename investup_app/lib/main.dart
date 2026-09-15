import 'package:flutter/material.dart';

import 'app.dart';
import 'core/api/api_config.dart';
import 'core/auth/token_storage.dart';
import 'core/core_services.dart';
import 'core/theme/theme_controller.dart';
import 'environment.dart';
import 'features/guide/wiki_terms_source.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final theme = await ThemeController.load();
  final core = CoreServices(
    config: ApiConfig(baseUrl: apiBaseUrl()),
    storage: SecureTokenStorage(),
  );
  runApp(
    InvestUpApp(
      session: core.createAuthSession(),
      auth: core.auth,
      market: core.market,
      stocks: core.stocks,
      account: core.account,
      orders: core.orders,
      exchangeRates: core.exchangeRates,
      reports: core.reports,
      theme: theme,
      // terms.md 원문을 우선 가져오고, 실패하면 생성된 스냅샷으로 폴백한다.
      wiki: FallbackWikiTermsSource(
        RemoteWikiTermsSource(),
        const BundledWikiTermsSource(),
      ),
    ),
  );
}
