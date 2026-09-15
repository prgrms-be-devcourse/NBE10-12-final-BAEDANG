import 'package:investup_app/core/auth/token_storage.dart';

/// 메모리만 쓰는 테스트용 저장소. 실패를 주입해 예외 경로를 검증한다.
class FakeTokenStorage implements TokenStorage {
  FakeTokenStorage({
    String? initialRefreshToken,
    this.failWrites = false,
    this.failDeletes = false,
    this.failReads = false,
  }) : _refreshToken = initialRefreshToken;

  String? _refreshToken;

  bool failWrites;
  bool failDeletes;
  bool failReads;

  int writeCount = 0;
  int deleteCount = 0;

  String? get stored => _refreshToken;

  @override
  Future<String?> readRefreshToken() async {
    if (failReads) throw const StorageException('읽기 실패');
    return _refreshToken;
  }

  @override
  Future<void> writeRefreshToken(String refreshToken) async {
    writeCount++;
    if (failWrites) throw const StorageException('쓰기 실패');
    _refreshToken = refreshToken;
  }

  @override
  Future<void> deleteRefreshToken() async {
    deleteCount++;
    if (failDeletes) throw const StorageException('삭제 실패');
    _refreshToken = null;
  }
}
