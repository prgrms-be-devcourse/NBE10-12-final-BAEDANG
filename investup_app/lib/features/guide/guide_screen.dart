import 'package:flutter/material.dart';
import '../../widgets/app_widgets.dart';

class GuideScreen extends StatelessWidget {
  const GuideScreen({super.key});

  static const steps = [
    ('계좌를 만들어요', '회원가입하면 모의 투자금으로 연습을 시작할 수 있어요. 실제 돈을 입금하거나 출금하는 서비스는 아니에요.', Icons.account_balance_wallet_outlined),
    ('종목을 살펴봐요', '국내·해외 랭킹에서 종목의 가격과 등락을 확인해보세요. 표시된 가격의 기준 시각도 함께 살펴보세요.', Icons.travel_explore),
    ('가격과 주문을 이해해요', '시세는 계속 변할 수 있어요. 주문 전 견적과 실제 체결 결과는 다를 수 있다는 점을 기억하세요.', Icons.menu_book_outlined),
    ('나의 투자 과정을 돌아봐요', '수익률만 보기보다 어떤 이유로 종목을 선택했는지 기록해보세요. 작은 연습을 쌓아 투자 감각을 길러요.', Icons.insights_outlined),
  ];

  @override
  Widget build(BuildContext context) => PageList(
    children: [
      Text('처음이어도 괜찮아요', style: Theme.of(context).textTheme.headlineMedium),
      const SizedBox(height: 8),
      const Text('실수는 가볍게, 투자 감각은 제대로.'),
      const SizedBox(height: 24),
      for (var i = 0; i < steps.length; i++) ...[
        AppCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(steps[i].$3, color: Theme.of(context).colorScheme.primary, size: 32),
              const SizedBox(height: 16),
              Text('${i + 1}. ${steps[i].$1}', style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(height: 8),
              Text(steps[i].$2, style: Theme.of(context).textTheme.bodyLarge),
            ],
          ),
        ),
        const SizedBox(height: 16),
      ],
    ],
  );
}
