import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';

/// 서버 대신 미리 정한 응답을 돌려주는 테스트용 어댑터.
///
/// 실제 Dio 파이프라인(인터셉터, 변환기)을 그대로 지나가므로 인증 재시도와
/// 단일 갱신을 실제 코드 경로로 검증할 수 있다.
class FakeHttpAdapter implements HttpClientAdapter {
  FakeHttpAdapter(this.handler, {this.delay = Duration.zero});

  /// 경로별 응답을 만든다. 연결 실패를 흉내 내려면 DioException을 던진다.
  final Future<FakeResponse> Function(RequestOptions options) handler;

  /// 모든 응답에 주는 지연. 동시 요청을 겹치게 만들 때 쓴다.
  final Duration delay;

  final List<RequestOptions> requests = <RequestOptions>[];

  List<RequestOptions> requestsTo(String pathSuffix) => requests
      .where((request) => request.uri.path.endsWith(pathSuffix))
      .toList(growable: false);

  int countTo(String pathSuffix) => requestsTo(pathSuffix).length;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    requests.add(options);
    if (delay > Duration.zero) {
      await Future<void>.delayed(delay);
    }
    final response = await handler(options);
    return ResponseBody.fromString(
      response.raw ?? jsonEncode(response.body),
      response.statusCode,
      headers: <String, List<String>>{
        Headers.contentTypeHeader: <String>[Headers.jsonContentType],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

/// 어댑터가 돌려줄 응답.
class FakeResponse {
  const FakeResponse(this.statusCode, [this.body]) : raw = null;

  /// JSON으로 인코딩하지 않고 그대로 보내는 본문(프록시 HTML 등).
  const FakeResponse.rawBody(this.statusCode, this.raw) : body = null;

  final int statusCode;
  final Map<String, Object?>? body;
  final String? raw;

  factory FakeResponse.ok(Map<String, Object?> body) => FakeResponse(200, body);

  factory FakeResponse.created(Map<String, Object?> body) =>
      FakeResponse(201, body);

  /// 빈 성공 본문(로그아웃 등).
  factory FakeResponse.empty() => const FakeResponse(200);

  factory FakeResponse.error(
    int statusCode, {
    required String code,
    required String message,
    Map<String, Object?>? data,
  }) => FakeResponse(statusCode, <String, Object?>{
    'code': code,
    'message': message,
    'timestamp': '2026-09-15T10:00:00+09:00',
    'data': ?data,
  });
}
