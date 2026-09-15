import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_error.dart';
import '../../core/api/market_api.dart';
import '../../core/auth/auth_session.dart';
import '../../core/models/market_country.dart';
import '../../core/models/market_status.dart';
import '../../widgets/app_widgets.dart';

/// 홈 탭. 서비스 소개와 현재 국내/해외 장 상태를 보여준다.
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key, required this.session, required this.market});

  final AuthSession session;
  final MarketApi market;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  Future<MarketStatus>? _future;

  @override
  void initState() {
    super.initState();
    _future = widget.market.getMarketStatus();
  }

  Future<void> _reload() async {
    final future = widget.market.getMarketStatus();
    setState(() {
      _future = future;
    });
    try {
      await future;
    } on ApiException {
      // FutureBuilder가 오류 상태를 그린다.
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return PageList(
      onRefresh: _reload,
      children: [
        AppCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text('실전처럼 경험하고', style: theme.textTheme.headlineMedium),
              Text('나만의 투자 감각을 키워요', style: theme.textTheme.headlineMedium),
              const SizedBox(height: 12),
              const Text('모의 투자금 5,000만원으로 국내·해외 주식을 연습해요.'),
              const SizedBox(height: 16),
              ListenableBuilder(
                listenable: widget.session,
                builder: (context, _) {
                  final loggedIn = widget.session.isAuthenticated;
                  return FilledButton(
                    onPressed: () =>
                        context.go(loggedIn ? '/rankings' : '/login?from=/'),
                    child: Text(
                      loggedIn ? '종목 둘러보기' : '모의 투자금 받고 시작하기',
                    ),
                  );
                },
              ),
            ],
          ),
        ),
        const SizedBox(height: 16),
        FutureBuilder<MarketStatus>(
          future: _future,
          builder: (context, snapshot) {
            if (snapshot.hasError) {
              return Notice(
                message: snapshot.error is ApiException
                    ? (snapshot.error! as ApiException).message
                    : '시장 상태를 불러오지 못했어요',
                onRetry: _reload,
              );
            }
            final status = snapshot.data;
            if (status == null) {
              return const AppCard(
                child: Center(child: CircularProgressIndicator()),
              );
            }
            return AppCard(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('지금 시장', style: theme.textTheme.titleLarge),
                  const SizedBox(height: 12),
                  for (final country in MarketCountry.values)
                    if (country != MarketCountry.unknown)
                      _MarketRow(session: status.sessionOf(country)),
                ],
              ),
            );
          },
        ),
        const SizedBox(height: 16),
        AppCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text('처음이세요?', style: theme.textTheme.titleLarge),
              const SizedBox(height: 8),
              const Text('이용 가이드에서 주식 투자의 기본을 차근차근 알아보세요.'),
              const SizedBox(height: 12),
              FilledButton.tonal(
                onPressed: () => context.go('/guide'),
                child: const Text('가이드 보기'),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _MarketRow extends StatelessWidget {
  const _MarketRow({required this.session});

  final MarketSession? session;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final label = switch (session?.marketCountry) {
      MarketCountry.kr => '국내장',
      MarketCountry.us => '해외장',
      _ => '기타',
    };
    final open = session?.open;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        children: [
          Expanded(child: Text(label)),
          if (open == null)
            Text('확인 중', style: TextStyle(color: scheme.onSurfaceVariant))
          else
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
              decoration: BoxDecoration(
                color: open
                    ? const Color(0xFFDCFCE7)
                    : scheme.surfaceContainerHighest,
                borderRadius: BorderRadius.circular(6),
              ),
              child: Text(
                open ? '개장 중' : '마감',
                style: TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w700,
                  color: open
                      ? const Color(0xFF166534)
                      : scheme.onSurfaceVariant,
                ),
              ),
            ),
        ],
      ),
    );
  }
}
