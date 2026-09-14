import 'package:flutter_test/flutter_test.dart';
import 'package:investup_app/app.dart';

import 'core/fakes/fake_http_adapter.dart';
import 'core/fakes/fixtures.dart';
import 'core/fakes/test_harness.dart';

void main() {
  testWidgets('저장된 로그인이 없으면 복원 뒤 로그인 없이 홈으로 간다', (tester) async {
    final harness = TestHarness(
      adapter: FakeHttpAdapter(
        (_) async => FakeResponse.ok(marketStatusJson()),
      ),
    );

    await tester.pumpWidget(
      InvestUpApp(
        session: harness.session,
        market: harness.market,
        stocks: harness.stocks,
        account: harness.account,
      ),
    );
    await tester.pumpAndSettle();

    // 둘러보기는 로그인 없이 열린다 — 홈 히어로와 하단 탭이 보인다.
    expect(find.text('실전처럼 경험하고'), findsOneWidget);
    expect(find.text('랭킹'), findsWidgets);
    expect(find.text('가이드'), findsWidgets);
  });
}

