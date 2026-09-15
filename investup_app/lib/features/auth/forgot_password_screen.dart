import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_error.dart';
import '../../core/api/auth_api.dart';
import '../../theme/app_theme.dart';

/// 비밀번호 찾기·재설정.
/// 1단계: 이메일로 재설정 메일 요청 — 계정 존재 여부와 무관하게 같은 안내를 보여준다.
/// 2단계: 메일 속 토큰과 새 비밀번호로 재설정.
class ForgotPasswordScreen extends StatefulWidget {
  const ForgotPasswordScreen({super.key, required this.auth});

  final AuthApi auth;

  @override
  State<ForgotPasswordScreen> createState() => _ForgotPasswordScreenState();
}

class _ForgotPasswordScreenState extends State<ForgotPasswordScreen> {
  final _email = TextEditingController();
  final _token = TextEditingController();
  final _newPassword = TextEditingController();
  final _newPasswordConfirm = TextEditingController();
  bool _submitting = false;
  bool _emailSent = false;
  bool _done = false;
  String? _error;

  @override
  void dispose() {
    _email.dispose();
    _token.dispose();
    _newPassword.dispose();
    _newPasswordConfirm.dispose();
    super.dispose();
  }

  Future<void> _requestMail() async {
    if (_submitting) return;
    setState(() {
      _submitting = true;
      _error = null;
    });
    try {
      await widget.auth.forgotPassword(email: _email.text.trim());
      setState(() => _emailSent = true);
    } on ApiException catch (e) {
      setState(() => _error = e.message);
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  Future<void> _reset() async {
    if (_submitting) return;
    final password = _newPassword.text;
    if (password.length < 8 || password.length > 64) {
      setState(() => _error = '비밀번호는 8~64자로 입력해 주세요');
      return;
    }
    if (password != _newPasswordConfirm.text) {
      setState(() => _error = '비밀번호가 서로 달라요');
      return;
    }
    setState(() {
      _submitting = true;
      _error = null;
    });
    try {
      await widget.auth.resetPassword(
        token: _token.text.trim(),
        newPassword: password,
      );
      setState(() => _done = true);
    } on ApiException catch (e) {
      setState(() => _error = e.message);
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(title: const Text('비밀번호 찾기')),
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: AppTheme.pagePadding,
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: AppTheme.formWidth),
              child: _done ? _buildDone(theme) : _buildForm(theme),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildDone(ThemeData theme) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      Icon(
        Icons.check_circle_outline,
        size: 56,
        color: theme.colorScheme.primary,
      ),
      const SizedBox(height: 16),
      Text(
        '비밀번호가 변경됐어요',
        style: theme.textTheme.titleLarge,
        textAlign: TextAlign.center,
      ),
      const SizedBox(height: 24),
      FilledButton(
        onPressed: () => context.go('/login'),
        child: const Text('로그인으로 이동'),
      ),
    ],
  );

  Widget _buildForm(ThemeData theme) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      Text('비밀번호를 재설정해요', style: theme.textTheme.headlineMedium),
      const SizedBox(height: 8),
      const Text('가입한 이메일로 재설정 링크를 보내드려요.'),
      const SizedBox(height: 24),
      TextField(
        controller: _email,
        keyboardType: TextInputType.emailAddress,
        autofillHints: const [AutofillHints.email],
        enabled: !_emailSent,
        decoration: const InputDecoration(
          labelText: '이메일',
          prefixIcon: Icon(Icons.mail_outline),
        ),
      ),
      const SizedBox(height: 12),
      if (_emailSent)
        Text(
          '재설정 메일을 보냈어요. 메일 속 링크의 토큰과 새 비밀번호를 입력해 주세요.',
          style: TextStyle(color: theme.colorScheme.primary),
        )
      else
        FilledButton(
          onPressed: _submitting ? null : _requestMail,
          child: _submitting
              ? const SizedBox(
                  width: 20,
                  height: 20,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : const Text('재설정 메일 보내기'),
        ),
      if (_emailSent) ...[
        const SizedBox(height: 20),
        TextField(
          controller: _token,
          decoration: const InputDecoration(
            labelText: '재설정 토큰',
            prefixIcon: Icon(Icons.key_outlined),
          ),
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _newPassword,
          obscureText: true,
          autofillHints: const [AutofillHints.newPassword],
          decoration: const InputDecoration(
            labelText: '새 비밀번호 (8~64자)',
            prefixIcon: Icon(Icons.lock_outline),
          ),
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _newPasswordConfirm,
          obscureText: true,
          onSubmitted: (_) => _reset(),
          decoration: const InputDecoration(
            labelText: '새 비밀번호 확인',
            prefixIcon: Icon(Icons.lock_outline),
          ),
        ),
        const SizedBox(height: 16),
        FilledButton(
          onPressed: _submitting ? null : _reset,
          child: _submitting
              ? const SizedBox(
                  width: 20,
                  height: 20,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : const Text('비밀번호 재설정'),
        ),
      ],
      if (_error != null) ...[
        const SizedBox(height: 12),
        Text(_error!, style: TextStyle(color: theme.colorScheme.error)),
      ],
    ],
  );
}
