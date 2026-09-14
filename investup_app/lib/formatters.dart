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

/// 거래 불가 사유 코드(ErrorCode 이름)를 사용자 문구로 바꾼다.
/// 웹의 TRADABLE_REASON_LABEL과 같은 표를 쓴다.
String tradableReasonLabel(String? code) => switch (code) {
  'MARKET_CLOSED' => '장 마감 · 거래 시간이 아니에요',
  'NOT_IN_UNIVERSE' => '이 종목은 아직 거래를 지원하지 않아요',
  'SUSPENDED' => '거래정지 종목이에요',
  'LIQUIDATION' => '정리매매 종목이에요',
  'QUOTE_NOT_FOUND' => '시세 정보가 아직 없어요',
  'PRICE_LIMIT_UNAVAILABLE' => '당일 상하한가를 확인 중이에요. 잠시 후 다시 시도해주세요',
  'PRICE_OUT_OF_RANGE' => '주문 가격은 당일 하한가와 상한가 사이여야 해요',
  'INVALID_TICK_SIZE' => '주문 가격이 호가 단위에 맞지 않아요',
  'QUOTE_OUT_OF_PRICE_LIMIT' => '현재가를 다시 확인 중이에요. 잠시 후 다시 시도해주세요',
  _ => '지금은 거래할 수 없어요',
};

/// 등락률 문자열의 부호에 따른 표시 색. 국내 관례: 상승 빨강, 하락 파랑.
Color changeColor(String? raw, ColorScheme scheme) {
  final value = raw == null ? null : double.tryParse(raw);
  if (value == null || value == 0) return scheme.onSurfaceVariant;
  return value > 0 ? const Color(0xFFEF4444) : const Color(0xFF3B82F6);
}
