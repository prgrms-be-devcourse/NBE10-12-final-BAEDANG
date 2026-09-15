/// DTO 파싱에서 쓰는 최소 헬퍼.
///
/// 계약을 넘겨짚지 않는다. 예를 들어 id는 JSON 숫자만 받고 문자열은 거절한다
/// (서버는 숫자로 내려준다). 잘못된 timestamp는 예외 대신 null로 둔다.
extension JsonRead on Map<String, Object?> {
  String? stringOrNull(String key) {
    final raw = this[key];
    return raw is String ? raw : null;
  }

  String requireString(String key) {
    final value = stringOrNull(key);
    if (value == null) throw FormatException('필수 문자열 필드가 없어요: $key');
    return value;
  }

  int? intOrNull(String key) {
    final raw = this[key];
    if (raw is int) return raw;
    if (raw is num) return raw.toInt();
    return null;
  }

  int requireInt(String key) {
    final value = intOrNull(key);
    if (value == null) throw FormatException('필수 숫자 필드가 없어요: $key');
    return value;
  }

  bool? boolOrNull(String key) {
    final raw = this[key];
    return raw is bool ? raw : null;
  }

  /// 필수 boolean. 누락이나 오염을 false로 대체하지 않고 거절한다.
  bool requireBool(String key) {
    final value = boolOrNull(key);
    if (value == null) throw FormatException('필수 불리언 필드가 없어요: $key');
    return value;
  }

  DateTime? dateTimeOrNull(String key) {
    final raw = this[key];
    if (raw is! String || raw.isEmpty) return null;
    return DateTime.tryParse(raw);
  }

  Map<String, Object?>? objectOrNull(String key) {
    final raw = this[key];
    if (raw is! Map) return null;
    return raw.map((k, v) => MapEntry(k.toString(), v));
  }

  /// 필수 목록. 누락·비-List 값·비-Map 원소를 빈/부분 목록으로 축소하지 않는다.
  List<Map<String, Object?>> requireObjectList(String key) {
    final raw = this[key];
    if (raw is! List) throw FormatException('필수 목록 필드가 없어요: $key');
    return raw.map((item) {
      if (item is! Map) {
        throw FormatException('목록 원소가 객체가 아니에요: $key');
      }
      return item.map((k, v) => MapEntry(k.toString(), v));
    }).toList(growable: false);
  }
}
