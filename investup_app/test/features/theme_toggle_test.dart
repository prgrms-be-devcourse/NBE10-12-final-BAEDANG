import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:investup_app/core/theme/theme_controller.dart';
import 'package:investup_app/features/my/my_screen.dart';

import '../core/fakes/fake_http_adapter.dart';
import '../core/fakes/test_harness.dart';

void main() {
  testWidgets('비로그인 마이 화면의 화면 테마 토글이 다크 모드로 바꾼다', (tester) async {
    final harness = TestHarness(
      adapter: FakeHttpAdapter((_) async => FakeResponse.ok(const {})),
    );
    final theme = ThemeController();
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: MyScreen(
            session: harness.session,
            stocks: harness.stocks,
            account: harness.account,
            orders: harness.orders,
            exchangeRates: harness.exchangeRates,
            reports: harness.reports,
            theme: theme,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('화면 테마'), findsOneWidget);
    expect(find.text('라이트'), findsOneWidget);
    expect(find.text('다크'), findsOneWidget);

    await tester.tap(find.text('다크'));
    await tester.pumpAndSettle();
    expect(theme.mode, ThemeMode.dark);
  });
}
