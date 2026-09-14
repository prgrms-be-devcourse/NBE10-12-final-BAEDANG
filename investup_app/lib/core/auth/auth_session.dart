import 'package:flutter/foundation.dart';

import '../api/account_api.dart';
import '../api/api_client.dart';
import '../api/api_error.dart';
import '../api/auth_api.dart';
import '../models/account_summary.dart';
import '../models/auth_response.dart';
import '../models/user_profile.dart';
import 'token_manager.dart';
import 'token_storage.dart';

/// 로그인 상태.
enum AuthStatus {
  /// 아직 판단 전(스플래시).
  unknown,

  /// access token과 프로필을 확보한 상태.
  authenticated,

  /// 저장된 자격증명이 없거나 확정적으로 무효.
  unauthenticated,

  /// 저장된 refresh token은 있지만 통신/서버 문제로 확인하지 못했다.
  /// 저장 토큰을 지우지 않고 재시도를 안내한다.
  unavailable,
}

/// 화면이 구독하는 인증 상태. Riverpod에 묶지 않고 ChangeNotifier로만 노출한다.
///
/// 토큰은 밖으로 내보내지 않는다. 화면은 [status]/[profile]/[account]만 본다.
class AuthSession extends ChangeNotifier {
  AuthSession({
    required ApiClient client,
    required AuthApi authApi,
    required AccountApi accountApi,
  }) : _client = client,
       _tokens = client.tokens,
       _authApi = authApi,
       _accountApi = accountApi {
    _tokens.addListener(_onTokensChanged);
  }

  final ApiClient _client;
  final TokenManager _tokens;
  final AuthApi _authApi;
  final AccountApi _accountApi;

  AuthStatus _status = AuthStatus.unknown;
  UserProfile? _profile;
  AccountSummary? _account;
  ApiError? _restoreError;
  bool _restoring = false;

  AuthStatus get status => _status;

  UserProfile? get profile => _profile;

  AccountSummary? get account => _account;

  /// [AuthStatus.unavailable]로 끝난 복원의 원인.
  ApiError? get restoreError => _restoreError;

  bool get isAuthenticated => _status == AuthStatus.authenticated;

  @override
  void dispose() {
    _tokens.removeListener(_onTokensChanged);
    super.dispose();
  }

  /// 앱 시작 시 1회. 저장된 refresh token으로 access token을 갱신한 뒤
  /// 프로필과 계좌를 읽어 상태를 복원한다.
  Future<void> restore() async {
    if (_restoring) return;
    _restoring = true;
    final generation = _tokens.generation;
    try {
      final String? stored;
      try {
        stored = await _tokens.restoreRefreshToken();
      } on StorageException catch (error) {
        // 백업/복원 등으로 읽기가 실패한 경우. 비로그인으로 단정하지 않는다.
        _status = AuthStatus.unavailable;
        _restoreError = ApiError(
          kind: ApiErrorKind.unexpected,
          message: error.message,
        );
        notifyListeners();
        return;
      }
      if (_isStale(generation)) return;
      if (stored == null) {
        _applySignedOut();
        return;
      }
      try {
        await _client.refreshAccessToken();
      } on ApiException catch (error) {
        if (_isStale(generation)) return;
        if (error.isSessionInvalid) {
          await _invalidateSession();
        } else {
          // timeout/오프라인/5xx는 refresh token 무효의 증거가 아니다.
          _status = AuthStatus.unavailable;
          _restoreError = error.error;
          notifyListeners();
        }
        return;
      }
      if (_isStale(generation)) return;
      try {
        final profile = await _authApi.getMe();
        if (_isStale(generation)) return;
        final account = await _accountApi.getAccountSummary();
        if (_isStale(generation)) return;
        _profile = profile;
        _account = account;
        _applyAuthenticated();
      } on ApiException catch (error) {
        if (_isStale(generation)) return;
        if (error.isSessionInvalid) {
          await _invalidateSession();
          return;
        }
        _status = AuthStatus.unavailable;
        _restoreError = error.error;
        notifyListeners();
      }
    } finally {
      _restoring = false;
    }
  }

  Future<UserProfile> logIn({
    required String email,
    required String password,
  }) => _authenticate(() => _authApi.logIn(email: email, password: password));

  Future<UserProfile> signUp({
    required String email,
    required String password,
    required String nickname,
  }) => _authenticate(
    () => _authApi.signUp(email: email, password: password, nickname: nickname),
  );

  /// 프로필을 서버에서 다시 읽어 캐시를 갱신한다.
  Future<void> reloadProfile() async {
    final generation = _tokens.generation;
    final profile = await _authApi.getMe();
    if (_isStale(generation)) return;
    _profile = profile;
    notifyListeners();
  }

  /// 계좌 요약을 다시 읽는다.
  Future<void> reloadAccount() async {
    final generation = _tokens.generation;
    final account = await _accountApi.getAccountSummary();
    if (_isStale(generation)) return;
    _account = account;
    notifyListeners();
  }

  /// 닉네임 변경 — 성공하면 Nav·마이페이지가 참조하는 프로필을 같이 갱신한다.
  Future<UserProfile> updateNickname(String nickname) async {
    final profile = await _authApi.updateNickname(nickname: nickname);
    _profile = profile;
    notifyListeners();
    return profile;
  }

  /// 비밀번호 변경 — 반환된 프로필로 캐시를 맞춘다.
  Future<UserProfile> changePassword({
    required String currentPassword,
    required String newPassword,
  }) async {
    final profile = await _authApi.changePassword(
      currentPassword: currentPassword,
      newPassword: newPassword,
    );
    _profile = profile;
    notifyListeners();
    return profile;
  }

  /// 회원 탈퇴. 서버가 토큰을 무효화하지 않으므로(stateless JWT) 성공 후
  /// 로컬 세션을 반드시 지운다 — 안 지우면 없는 계정으로 요청을 계속 보낸다.
  Future<void> withdraw(String currentPassword) async {
    await _authApi.withdraw(currentPassword: currentPassword);
    await _invalidateSession();
  }

  /// 로그아웃. 화면 상태를 먼저 비우고, 서버 호출 실패는 무시한다.
  /// 저장된 토큰 삭제 실패는 [StorageException]으로 알린다(로컬 로그아웃은 유지).
  Future<void> logOut() async {
    final accessToken = _tokens.accessToken;
    _tokens.invalidate();
    _applySignedOut();
    // 로컬 삭제를 서버 응답보다 먼저 끝낸다. 늦은 로그아웃 응답이 이후
    // 로그인이 저장한 토큰을 지우는 일이 없어야 한다.
    await _tokens.clearStoredRefreshToken();
    try {
      await _authApi.logOut(accessToken: accessToken);
    } on ApiException {
      // 서버 로그아웃은 stateless다. 통신 실패가 로컬 로그아웃을 막지 않는다.
    }
  }

  Future<UserProfile> _authenticate(
    Future<AuthResponse> Function() request,
  ) async {
    final generation = _tokens.generation;
    final response = await request();
    if (_isStale(generation)) {
      throw const ApiException(ApiError.sessionSuperseded);
    }
    final applied = await _tokens.startSession(
      accessToken: response.accessToken,
      refreshToken: response.refreshToken,
      generation: generation,
    );
    if (!applied) {
      // 그 사이 로그아웃/다른 로그인이 있었다.
      throw const ApiException(ApiError.sessionSuperseded);
    }
    // startSession이 새 세대를 열었다. 이후 대기는 새 세대 기준으로 판단한다.
    final sessionGeneration = _tokens.generation;
    _profile = response.profile;
    _account = null;
    _applyAuthenticated();
    await _loadAccountQuietly(sessionGeneration);
    if (_isStale(sessionGeneration)) {
      // 계좌 조회를 기다리는 사이 로그아웃/세션 무효화가 끼어들었다.
      // 늦은 로그인 성공을 돌려주지 않는다.
      throw const ApiException(ApiError.sessionSuperseded);
    }
    return response.profile;
  }

  /// 계좌 요약은 인증 성공 뒤 따라오는 정보라, 실패해도 로그인 상태를 되돌리지 않는다.
  Future<void> _loadAccountQuietly(int generation) async {
    try {
      final account = await _accountApi.getAccountSummary();
      if (_isStale(generation)) return;
      _account = account;
      notifyListeners();
    } on ApiException catch (error) {
      if (error.isSessionInvalid) await _invalidateSession();
    }
  }

  Future<void> _invalidateSession() async {
    try {
      await _tokens.invalidateSession();
    } on StorageException {
      // 세션은 이미 끝났다. 저장소 정리는 다음 시작의 refresh 실패 경로에서 다시 시도된다.
    }
    _applySignedOut();
  }

  /// 인터셉터가 INVALID_TOKEN을 확정해 세션을 정리한 경우를 화면 상태에 반영한다.
  void _onTokensChanged() {
    if (!_tokens.hasSession && _status != AuthStatus.unauthenticated) {
      _applySignedOut();
    }
  }

  void _applyAuthenticated() {
    _status = AuthStatus.authenticated;
    _restoreError = null;
    notifyListeners();
  }

  void _applySignedOut() {
    _status = AuthStatus.unauthenticated;
    _profile = null;
    _account = null;
    _restoreError = null;
    notifyListeners();
  }

  bool _isStale(int generation) => generation != _tokens.generation;
}
