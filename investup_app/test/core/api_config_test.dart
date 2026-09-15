import 'package:flutter_test/flutter_test.dart';
import 'package:investup_app/core/api/api_config.dart';

void main() {
  test('baseUrl은 \'/api\'를 건드리지 않고 뒤쪽 슬래시만 정리한다', () {
    expect(
      ApiConfig(baseUrl: 'http://10.0.2.2:8080/api').baseUrl,
      'http://10.0.2.2:8080/api/',
    );
    expect(
      ApiConfig(baseUrl: 'http://10.0.2.2:8080/api/').baseUrl,
      'http://10.0.2.2:8080/api/',
    );
    expect(
      ApiConfig(baseUrl: '  https://staging.example.com/api  ').baseUrl,
      'https://staging.example.com/api/',
    );
  });

  test('빈 값, 상대 경로, http(s)가 아닌 주소는 거절한다', () {
    expect(() => ApiConfig(baseUrl: '   '), throwsArgumentError);
    expect(() => ApiConfig(baseUrl: 'localhost:8080/api'), throwsArgumentError);
    expect(() => ApiConfig(baseUrl: '/api'), throwsArgumentError);
    expect(
      () => ApiConfig(baseUrl: 'ftp://example.com/api'),
      throwsArgumentError,
    );
  });
}
