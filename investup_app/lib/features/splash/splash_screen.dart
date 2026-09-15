import 'package:flutter/material.dart';

import '../../core/auth/auth_session.dart';
import '../../widgets/app_widgets.dart';

/// 앱 시작 화면. 저장된 세션을 복원하는 동안 보여준다.
///
/// 복원 결과에 따른 화면 전환은 라우터 redirect가 처리한다.
class SplashScreen extends StatefulWidget {
  const SplashScreen({super.key, required this.session});

  final AuthSession session;

  @override
  State<SplashScreen> createState() => _SplashScreenState();
}

class _SplashScreenState extends State<SplashScreen> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      widget.session.restore();
    });
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Scaffold(
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: ListenableBuilder(
            listenable: widget.session,
            builder: (context, _) {
              final failed =
                  widget.session.status == AuthStatus.unavailable;
              return Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(Icons.trending_up, size: 72, color: scheme.primary),
                  const SizedBox(height: 16),
                  Text('InvestUP',
                      style: Theme.of(context).textTheme.headlineMedium),
                  const SizedBox(height: 8),
                  Text(
                    '실수는 가볍게, 투자 감각은 제대로',
                    style: TextStyle(color: scheme.onSurfaceVariant),
                  ),
                  const SizedBox(height: 32),
                  if (failed) ...[
                    Notice(
                      message: widget.session.restoreError?.message ??
                          '로그인 정보를 확인하지 못했어요',
                      onRetry: widget.session.restore,
                    ),
                  ] else
                    const CircularProgressIndicator(),
                ],
              );
            },
          ),
        ),
      ),
    );
  }
}
