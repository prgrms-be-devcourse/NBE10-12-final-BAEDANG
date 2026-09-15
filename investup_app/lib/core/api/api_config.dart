/// API 클라이언트가 사용할 서버 주소와 타임아웃.
///
/// [baseUrl]은 `/api`까지 포함한 값이어야 하고, 항상 '/'로 끝나도록 정규화한다.
/// 릴리즈 기본값으로 localhost를 넣지 않는다 — 주소는 항상 호출부가 넘긴다.
class ApiConfig {
  ApiConfig({
    required String baseUrl,
    this.connectTimeout = const Duration(seconds: 10),
    this.receiveTimeout = const Duration(seconds: 15),
    this.sendTimeout = const Duration(seconds: 15),
  }) : baseUrl = normalizeBaseUrl(baseUrl);

  /// 예: `http://10.0.2.2:8080/api/`
  final String baseUrl;

  final Duration connectTimeout;
  final Duration receiveTimeout;
  final Duration sendTimeout;

  /// 뒤쪽 슬래시만 정리한다. 경로의 `/api`를 더하거나 지우지 않아
  /// `/api/api`나 `/auth/login` 같은 조합이 생기지 않게 한다.
  static String normalizeBaseUrl(String raw) {
    final trimmed = raw.trim();
    if (trimmed.isEmpty) {
      throw ArgumentError.value(raw, 'baseUrl', 'API_BASE_URL이 비어 있어요');
    }
    final Uri uri;
    try {
      uri = Uri.parse(trimmed);
    } on FormatException {
      throw ArgumentError.value(raw, 'baseUrl', '주소 형식이 올바르지 않아요');
    }
    if (!uri.isAbsolute || uri.host.isEmpty) {
      throw ArgumentError.value(raw, 'baseUrl', 'http(s) 절대 주소여야 해요');
    }
    if (uri.scheme != 'http' && uri.scheme != 'https') {
      throw ArgumentError.value(raw, 'baseUrl', 'http(s) 주소만 사용할 수 있어요');
    }
    return trimmed.endsWith('/') ? trimmed : '$trimmed/';
  }
}
