/// 시장 국가. 서버는 KR/US 문자열로 내려주고 쿼리도 같은 값을 쓴다.
enum MarketCountry {
  kr('KR'),
  us('US'),

  /// 알 수 없는 값. 파싱 크래시 대신 미지원 상태로 표시한다.
  unknown('');

  const MarketCountry(this.wireValue);

  /// 서버와 주고받는 값.
  final String wireValue;

  static MarketCountry fromWire(String? raw) {
    return switch (raw) {
      'KR' => MarketCountry.kr,
      'US' => MarketCountry.us,
      _ => MarketCountry.unknown,
    };
  }
}
