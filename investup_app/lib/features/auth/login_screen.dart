import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_error.dart';
import '../../core/auth/auth_session.dart';
import '../../core/auth/token_storage.dart';
import '../../theme/app_theme.dart';

/// 이메일/비밀번호 로그인. 성공 시 화면 전환은 라우터 redirect가 담당한다.
class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key, required this.session});

  final AuthSession session;

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final _email = TextEditingController();
  final _password = TextEditingController();
  bool _submitting = false;
  String? _error;

  @override
  void dispose() {
    _email.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    // 요청 중에는 버튼을 잠가 중복 제출을 막는다.
    if (_submitting) return;
    setState(() {
      _submitting = true;
      _error = null;
    });
    try {
      await widget.session.logIn(
        email: _email.text.trim(),
        password: _password.text,
      );
    } on ApiException catch (error) {
      setState(() => _error = error.message);
    } on StorageException catch (error) {
      setState(() => _error = error.message);
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: AppTheme.pagePadding,
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: AppTheme.formWidth),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text(
                    '로그인',
                    style: Theme.of(context).textTheme.headlineMedium,
                  ),
                  const SizedBox(height: 8),
                  const Text('모의 투자금으로 연습을 이어가세요.'),
                  const SizedBox(height: 24),
                  TextField(
                    controller: _email,
                    keyboardType: TextInputType.emailAddress,
                    autofillHints: const [AutofillHints.email],
                    decoration: const InputDecoration(
                      labelText: '이메일',
                      prefixIcon: Icon(Icons.mail_outline),
                    ),
                  ),
                  const SizedBox(height: 12),
                  TextField(
                    controller: _password,
                    obscureText: true,
                    autofillHints: const [AutofillHints.password],
                    onSubmitted: (_) => _submit(),
                    decoration: const InputDecoration(
                      labelText: '비밀번호',
                      prefixIcon: Icon(Icons.lock_outline),
                    ),
                  ),
                  if (_error != null) ...[
                    const SizedBox(height: 12),
                    Text(
                      _error!,
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.error,
                      ),
                    ),
                  ],
                  const SizedBox(height: 24),
                  FilledButton(
                    onPressed: _submitting ? null : _submit,
                    child: _submitting
                        ? const SizedBox(
                            width: 20,
                            height: 20,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Text('로그인'),
                  ),
                  const SizedBox(height: 12),
                  OutlinedButton(
                    onPressed: _submitting
                        ? null
                        : () => context.go('/signup'),
                    child: const Text('회원가입'),
                  ),
                  const SizedBox(height: 8),
                  TextButton(
                    onPressed: () => context.push('/forgot-password'),
                    child: const Text('비밀번호를 잊으셨나요?'),
                  ),
                  TextButton(
                    onPressed: () => context.go('/'),
                    child: const Text('로그인 없이 둘러보기'),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
