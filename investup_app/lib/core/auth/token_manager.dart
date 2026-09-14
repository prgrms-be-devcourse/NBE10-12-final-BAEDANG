import 'package:flutter/foundation.dart';

import 'token_storage.dart';

/// access/refresh token의 메모리 상태와 세션 세대를 관리한다.
///
/// - access token은 메모리에만 둔다.
/// - refresh token은 보안 저장소에 두고 메모리에는 사본만 캐시한다.
/// - 세대(generation)는 로그아웃·새 로그인마다 올라가고, 늦게 도착한 응답을
///   버리는 기준이 된다.
class TokenManager extends ChangeNotifier {
  TokenManager({required TokenStorage storage}) : _storage = storage;

  final TokenStorage _storage;

  /// 저장소 조작을 직렬화한다. 로그아웃 삭제와 로그인 저장이 엇갈려
  /// 이전 세션이 토큰을 다시 남기는 일을 막는다.
  Future<void> _lock = Future<void>.value();

  String? _accessToken;
  String? _refreshToken;
  int _generation = 0;

  /// 세션 세대. 로그아웃/새 로그인마다 증가한다.
  int get generation => _generation;

  String? get accessToken => _accessToken;

  String? get refreshToken => _refreshToken;

  bool get hasSession => _refreshToken != null;

  /// 보안 저장소에서 refresh token을 읽어 메모리에 캐시한다.
  Future<String?> restoreRefreshToken() => _synchronized(() async {
    final stored = await _storage.readRefreshToken();
    _refreshToken = stored;
    return stored;
  });

  /// 로그인/가입 성공 반영. 저장에 성공해야만 세션을 공개한다.
  ///
  /// 성공하면 새 세대를 시작한다 — 이전에 시작된 로그인·refresh·인증 재시도의
  /// 늦은 응답이 새 사용자 상태를 덮거나 새 사용자 권한으로 실행되지 않는다.
  ///
  /// [generation]은 요청을 시작할 때의 세대다. 그 사이 로그아웃이나 다른
  /// 로그인이 있었으면 false를 돌려주고 아무것도 남기지 않는다.
  Future<bool> startSession({
    required String accessToken,
    required String refreshToken,
    required int generation,
  }) => _synchronized(() async {
    if (_isStale(generation)) return false;
    await _storage.writeRefreshToken(refreshToken);
    // 저장하는 동안 로그아웃/새 로그인이 있었으면 늦은 토큰을 공개하지 않는다.
    if (_isStale(generation)) return false;
    _accessToken = accessToken;
    _refreshToken = refreshToken;
    _generation++;
    notifyListeners();
    return true;
  });

  /// refresh 응답 반영. refresh token은 그대로 두고 access token만 바꾼다.
  Future<bool> applyRefreshedAccessToken(
    String accessToken, {
    required int generation,
  }) => _synchronized(() async {
    if (_isStale(generation)) return false;
    _accessToken = accessToken;
    return true;
  });

  /// 로그아웃 시작 즉시: 세대를 올리고 메모리 토큰을 비운다.
  /// 저장소 삭제는 호출부가 이어서 수행한다.
  void invalidate() {
    _generation++;
    _accessToken = null;
    _refreshToken = null;
    notifyListeners();
  }

  /// 저장된 refresh token 삭제. 실패는 [StorageException]으로 알린다.
  Future<void> clearStoredRefreshToken() =>
      _synchronized(() => _storage.deleteRefreshToken());

  /// 확정적으로 무효가 된 세션 정리(메모리 + 저장소).
  Future<void> invalidateSession() async {
    invalidate();
    await clearStoredRefreshToken();
  }

  bool _isStale(int generation) => generation != _generation;

  Future<T> _synchronized<T>(Future<T> Function() action) {
    final result = _lock.then((_) => action());
    // 직렬화 사슬은 실패해도 끊기지 않아야 한다.
    _lock = result.then((_) {}, onError: (Object _) {});
    return result;
  }
}
