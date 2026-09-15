import 'package:flutter/foundation.dart';

String apiBaseUrl({String value = const String.fromEnvironment('API_BASE_URL')}) {
  final configured = value.trim();
  if (configured.isEmpty) {
    if (kReleaseMode) throw const FormatException('API_BASE_URL is required for release');
    return defaultTargetPlatform == TargetPlatform.android
        ? 'http://10.0.2.2:8080/api'
        : 'http://localhost:8080/api';
  }
  final uri = Uri.tryParse(configured);
  if (uri == null || !uri.hasAuthority || uri.userInfo.isNotEmpty ||
      uri.hasQuery || uri.hasFragment || !{'http', 'https'}.contains(uri.scheme) ||
      !RegExp(r'/api/?$').hasMatch(uri.path)) {
    throw const FormatException('API_BASE_URL must be an HTTP(S) URL ending in /api');
  }
  if (kReleaseMode && uri.scheme != 'https') {
    throw const FormatException('Release API_BASE_URL requires HTTPS');
  }
  return configured;
}
