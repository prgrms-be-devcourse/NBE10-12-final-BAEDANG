import 'package:flutter/material.dart';

import '../../core/api/api_error.dart';
import '../../core/auth/auth_session.dart';
import '../../widgets/app_widgets.dart';

/// 계정 설정 — 닉네임 변경·비밀번호 변경·회원 탈퇴.
/// 마이페이지 로그인 상태에서만 렌더링된다.
class AccountSettings extends StatefulWidget {
  const AccountSettings({super.key, required this.session});

  final AuthSession session;

  @override
  State<AccountSettings> createState() => _AccountSettingsState();
}

class _AccountSettingsState extends State<AccountSettings> {
  late final TextEditingController _nicknameController;
  final _currentPwController = TextEditingController();
  final _newPwController = TextEditingController();
  final _newPwConfirmController = TextEditingController();
  final _withdrawPwController = TextEditingController();

  bool _nicknameSaving = false;
  String? _nicknameError;
  bool _nicknameSaved = false;

  bool _passwordSaving = false;
  String? _passwordError;
  bool _passwordSaved = false;

  @override
  void initState() {
    super.initState();
    _nicknameController = TextEditingController(
      text: widget.session.profile?.nickname ?? '',
    );
  }

  @override
  void dispose() {
    _nicknameController.dispose();
    _currentPwController.dispose();
    _newPwController.dispose();
    _newPwConfirmController.dispose();
    _withdrawPwController.dispose();
    super.dispose();
  }

  Future<void> _saveNickname() async {
    final next = _nicknameController.text.trim();
    if (_nicknameSaving || next == widget.session.profile?.nickname) return;
    setState(() {
      _nicknameSaving = true;
      _nicknameError = null;
      _nicknameSaved = false;
    });
    try {
      await widget.session.updateNickname(next);
      if (!mounted) return;
      setState(() => _nicknameSaved = true);
    } on ApiException catch (e) {
      if (mounted) setState(() => _nicknameError = e.message);
    } finally {
      if (mounted) setState(() => _nicknameSaving = false);
    }
  }

  Future<void> _savePassword() async {
    if (_passwordSaving) return;
    final current = _currentPwController.text;
    final next = _newPwController.text;
    setState(() {
      _passwordError = null;
      _passwordSaved = false;
    });
    if (next.length < 8 || next.length > 64) {
      setState(() => _passwordError = '새 비밀번호는 8~64자로 입력해 주세요');
      return;
    }
    if (next != _newPwConfirmController.text) {
      setState(() => _passwordError = '새 비밀번호가 서로 달라요');
      return;
    }
    setState(() => _passwordSaving = true);
    try {
      await widget.session.changePassword(
        currentPassword: current,
        newPassword: next,
      );
      if (!mounted) return;
      _currentPwController.clear();
      _newPwController.clear();
      _newPwConfirmController.clear();
      setState(() => _passwordSaved = true);
    } on ApiException catch (e) {
      if (mounted) setState(() => _passwordError = e.message);
    } finally {
      if (mounted) setState(() => _passwordSaving = false);
    }
  }

  Future<void> _withdraw() async {
    _withdrawPwController.clear();
    String? error;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => StatefulBuilder(
        builder: (context, setDialogState) => AlertDialog(
          title: const Text('정말 탈퇴할까요?'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('계정과 보유 종목·체결 내역이 모두 사라져요. 되돌릴 수 없어요.'),
              const SizedBox(height: 12),
              TextField(
                controller: _withdrawPwController,
                obscureText: true,
                decoration: const InputDecoration(labelText: '현재 비밀번호'),
              ),
              if (error != null)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(
                    error!,
                    style: TextStyle(
                      fontSize: 12.5,
                      color: Theme.of(context).colorScheme.error,
                    ),
                  ),
                ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(dialogContext, false),
              child: const Text('취소'),
            ),
            FilledButton(
              onPressed: () async {
                final password = _withdrawPwController.text;
                if (password.isEmpty) return;
                try {
                  await widget.session.withdraw(password);
                } on ApiException catch (e) {
                  setDialogState(() => error = e.message);
                  return;
                }
                if (dialogContext.mounted) {
                  Navigator.pop(dialogContext, true);
                }
              },
              child: const Text('탈퇴할게요'),
            ),
          ],
        ),
      ),
    );
    // 탈퇴 성공 시 세션이 끝나고 라우터가 화면을 정리한다.
    if (confirmed == true && mounted) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('탈퇴 처리됐어요')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('계정 설정', style: theme.textTheme.titleLarge),
        const SizedBox(height: 8),
        AppCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '닉네임',
                style: TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.w700,
                  color: scheme.onSurfaceVariant,
                ),
              ),
              const SizedBox(height: 8),
              Row(
                children: [
                  Expanded(
                    child: TextField(
                      controller: _nicknameController,
                      maxLength: 20,
                      decoration: const InputDecoration(
                        hintText: '새 닉네임',
                        counterText: '',
                        isDense: true,
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  FilledButton(
                    onPressed: _nicknameSaving ? null : _saveNickname,
                    child: Text(_nicknameSaving ? '변경 중…' : '변경'),
                  ),
                ],
              ),
              if (_nicknameError != null)
                Padding(
                  padding: const EdgeInsets.only(top: 4),
                  child: Text(
                    _nicknameError!,
                    style: TextStyle(fontSize: 12, color: scheme.error),
                  ),
                ),
              if (_nicknameSaved)
                const Padding(
                  padding: EdgeInsets.only(top: 4),
                  child: Text(
                    '닉네임을 변경했어요.',
                    style: TextStyle(fontSize: 12, color: Color(0xFF16A34A)),
                  ),
                ),
              const Divider(height: 32),
              Text(
                '비밀번호 변경',
                style: TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.w700,
                  color: scheme.onSurfaceVariant,
                ),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: _currentPwController,
                obscureText: true,
                decoration: const InputDecoration(
                  labelText: '현재 비밀번호',
                  isDense: true,
                ),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: _newPwController,
                obscureText: true,
                decoration: const InputDecoration(
                  labelText: '새 비밀번호 (8자 이상)',
                  isDense: true,
                ),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: _newPwConfirmController,
                obscureText: true,
                decoration: const InputDecoration(
                  labelText: '새 비밀번호 확인',
                  isDense: true,
                ),
              ),
              const SizedBox(height: 12),
              FilledButton.tonal(
                onPressed: _passwordSaving ? null : _savePassword,
                child: Text(_passwordSaving ? '변경 중…' : '비밀번호 변경'),
              ),
              if (_passwordError != null)
                Padding(
                  padding: const EdgeInsets.only(top: 6),
                  child: Text(
                    _passwordError!,
                    style: TextStyle(fontSize: 12, color: scheme.error),
                  ),
                ),
              if (_passwordSaved)
                const Padding(
                  padding: EdgeInsets.only(top: 6),
                  child: Text(
                    '비밀번호를 변경했어요.',
                    style: TextStyle(fontSize: 12, color: Color(0xFF16A34A)),
                  ),
                ),
            ],
          ),
        ),
        const SizedBox(height: 16),
        Container(
          width: double.infinity,
          padding: const EdgeInsets.all(20),
          decoration: BoxDecoration(
            color: scheme.errorContainer.withValues(alpha: 0.35),
            borderRadius: BorderRadius.circular(16),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                '회원 탈퇴',
                style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
              ),
              const SizedBox(height: 6),
              Text(
                '계정과 보유 종목·체결 내역이 모두 사라져요. 되돌릴 수 없어요.',
                style: TextStyle(
                  fontSize: 13,
                  color: scheme.onErrorContainer,
                ),
              ),
              const SizedBox(height: 12),
              OutlinedButton(
                onPressed: _withdraw,
                child: const Text('회원 탈퇴'),
              ),
            ],
          ),
        ),
      ],
    );
  }
}
