import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/auth/auth_session.dart';
import '../../core/auth/token_storage.dart';
import '../../formatters.dart';
import '../../widgets/app_widgets.dart';

/// 마이 탭. 프로필·계좌 요약·로그아웃을 보여준다.
/// 보유 종목·주문 내역·성향 리포트는 다음 단계에서 이어서 붙인다.
class MyScreen extends StatefulWidget {
  const MyScreen({super.key, required this.session});

  final AuthSession session;

  @override
  State<MyScreen> createState() => _MyScreenState();
}

class _MyScreenState extends State<MyScreen> {
  bool _loggingOut = false;

  Future<void> _reloadAccount() => widget.session.reloadAccount();

  Future<void> _logOut() async {
    if (_loggingOut) return;
    setState(() => _loggingOut = true);
    try {
      await widget.session.logOut();
      // 화면 전환은 라우터 redirect가 담당한다.
    } on StorageException catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(error.message)));
      }
    } finally {
      if (mounted) setState(() => _loggingOut = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return ListenableBuilder(
      listenable: widget.session,
      builder: (context, _) {
        final profile = widget.session.profile;
        final account = widget.session.account;
        if (!widget.session.isAuthenticated) {
          return PageList(
            children: [
              Text('내 정보', style: theme.textTheme.headlineMedium),
              const SizedBox(height: 16),
              AppCard(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Icon(
                      Icons.lock_outline,
                      size: 40,
                      color: theme.colorScheme.primary,
                    ),
                    const SizedBox(height: 12),
                    Text('로그인이 필요해요', style: theme.textTheme.titleLarge),
                    const SizedBox(height: 8),
                    const Text('내 계좌와 투자 기록을 보려면 로그인하세요.'),
                    const SizedBox(height: 16),
                    FilledButton(
                      onPressed: () => context.go('/login?from=/my'),
                      child: const Text('로그인'),
                    ),
                  ],
                ),
              ),
            ],
          );
        }
        return PageList(
          onRefresh: _reloadAccount,
          children: [
            Text('내 정보', style: theme.textTheme.headlineMedium),
            const SizedBox(height: 16),
            AppCard(
              child: Row(
                children: [
                  Icon(
                    Icons.account_circle_outlined,
                    size: 40,
                    color: theme.colorScheme.primary,
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          profile?.nickname ?? '-',
                          style: theme.textTheme.titleLarge,
                        ),
                        Text(
                          profile?.email ?? '',
                          style: TextStyle(
                            fontSize: 13,
                            color: theme.colorScheme.onSurfaceVariant,
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),
            if (account == null)
              Notice(
                message: '계좌 정보를 불러오지 못했어요',
                onRetry: _reloadAccount,
              )
            else
              AppCard(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('계좌 ${account.roundNo}회차', style: theme.textTheme.titleLarge),
                    const SizedBox(height: 16),
                    _row(context, '총 자산', formatMoney(account.totalAsset, 'KRW')),
                    _row(context, '예수금', formatMoney(account.cashBalance, 'KRW')),
                    _row(context, '주식 평가금액', formatMoney(account.stockValue, 'KRW')),
                    _row(
                      context,
                      '평가손익',
                      formatMoney(account.unrealizedPnl, 'KRW'),
                      valueColor: changeColor(account.unrealizedPnl, theme.colorScheme),
                    ),
                    _row(
                      context,
                      '수익률',
                      formatRate(account.unrealizedPnlRate),
                      valueColor: changeColor(
                        account.unrealizedPnlRate,
                        theme.colorScheme,
                      ),
                    ),
                  ],
                ),
              ),
            const SizedBox(height: 24),
            OutlinedButton.icon(
              onPressed: _loggingOut ? null : _logOut,
              icon: const Icon(Icons.logout),
              label: Text(_loggingOut ? '로그아웃 중…' : '로그아웃'),
            ),
          ],
        );
      },
    );
  }

  Widget _row(BuildContext context, String label, String value,
      {Color? valueColor}) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label, style: TextStyle(color: theme.colorScheme.onSurfaceVariant)),
          Text(
            value,
            style: theme.textTheme.bodyLarge?.copyWith(color: valueColor),
          ),
        ],
      ),
    );
  }
}
