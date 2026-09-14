import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

final _grouping = NumberFormat('#,###');

/// 금액 문자열을 통화에 맞춰 표시한다. 서버는 금액·수량을 문자열로 내려준다.
String formatMoney(String? raw, String? currency) {
  if (raw == null) return '-';
  final value = num.tryParse(raw);
  if (value == null) return raw;
  return switch (currency) {
    'USD' => '\$${_grouping.format(value)}',
    _ => '${_grouping.format(value)}원',
  };
}

/// 소수 비율 문자열("0.006757")을 부호 있는 퍼센트("+0.68%")로 표시한다.
String formatRate(String? raw) {
  if (raw == null) return '-';
  final value = double.tryParse(raw);
  if (value == null) return raw;
  final percent = value * 100;
  final sign = percent > 0 ? '+' : '';
  return '$sign${percent.toStringAsFixed(2)}%';
}

/// 등락률 문자열의 부호에 따른 표시 색. 국내 관례: 상승 빨강, 하락 파랑.
Color changeColor(String? raw, ColorScheme scheme) {
  final value = raw == null ? null : double.tryParse(raw);
  if (value == null || value == 0) return scheme.onSurfaceVariant;
  return value > 0 ? const Color(0xFFEF4444) : const Color(0xFF3B82F6);
}
