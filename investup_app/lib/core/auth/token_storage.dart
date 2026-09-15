import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// refresh token만 다루는 최소 저장소. 테스트에서는 가짜 구현을 쓴다.
///
/// access token은 여기에 넣지 않는다(메모리에만 둔다).
abstract interface class TokenStorage {
  Future<String?> readRefreshToken();

  Future<void> writeRefreshToken(String refreshToken);

  Future<void> deleteRefreshToken();
}

/// 보안 저장소 접근 실패. 로그인 완료로 넘기지 않고 호출부에 알린다.
class StorageException implements Exception {
  const StorageException(this.message, {this.cause});

  final String message;
  final Object? cause;

  @override
  String toString() => 'StorageException($message)';
}

/// flutter_secure_storage 기반 구현. 비밀번호·재설정 자격증명은 저장하지 않는다.
class SecureTokenStorage implements TokenStorage {
  SecureTokenStorage({FlutterSecureStorage? storage, this.key = defaultKey})
    : _storage = storage ?? const FlutterSecureStorage();

  static const String defaultKey = 'investup.refresh_token';

  final FlutterSecureStorage _storage;
  final String key;

  @override
  Future<String?> readRefreshToken() async {
    try {
      final stored = await _storage.read(key: key);
      if (stored == null || stored.isEmpty) return null;
      return stored;
    } on Exception catch (error) {
      throw StorageException('저장된 로그인 정보를 읽지 못했어요', cause: error);
    }
  }

  @override
  Future<void> writeRefreshToken(String refreshToken) async {
    try {
      await _storage.write(key: key, value: refreshToken);
    } on Exception catch (error) {
      throw StorageException('로그인 정보를 안전하게 저장하지 못했어요', cause: error);
    }
  }

  @override
  Future<void> deleteRefreshToken() async {
    try {
      await _storage.delete(key: key);
    } on Exception catch (error) {
      throw StorageException('저장된 로그인 정보를 지우지 못했어요', cause: error);
    }
  }
}
