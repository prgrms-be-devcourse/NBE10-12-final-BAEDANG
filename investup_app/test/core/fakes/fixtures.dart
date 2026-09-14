/// 백엔드 DTO와 같은 모양의 테스트 fixture. 토큰은 전부 자리표시자다.
Map<String, Object?> authJson({
  int userId = 7,
  String email = 'user@example.com',
  String nickname = '사용자',
  String accessToken = 'access-token',
  String refreshToken = 'refresh-token',
  int accountId = 3,
  int roundNo = 1,
  String initialCash = '50000000',
  String cashBalance = '50000000',
}) => <String, Object?>{
  'userId': userId,
  'email': email,
  'nickname': nickname,
  'accessToken': accessToken,
  'refreshToken': refreshToken,
  'account': <String, Object?>{
    'accountId': accountId,
    'roundNo': roundNo,
    'initialCash': initialCash,
    'cashBalance': cashBalance,
  },
};

Map<String, Object?> profileJson({
  int userId = 7,
  String email = 'user@example.com',
  String nickname = '사용자',
}) => <String, Object?>{'userId': userId, 'email': email, 'nickname': nickname};

Map<String, Object?> accountSummaryJson({int accountId = 3, int roundNo = 1}) =>
    <String, Object?>{
      'accountId': accountId,
      'roundNo': roundNo,
      'initialCash': '50000000',
      'cashBalance': '49000000',
      'stockValue': '1200000',
      'totalAsset': '50200000',
      'unrealizedPnl': '200000',
      // 서버 규칙: unrealizedPnl / (stockValue - unrealizedPnl) = 200000/1000000.
      'unrealizedPnlRate': '0.2',
      'exchangeRate': '1380.5',
      'asOf': '2026-09-15T10:00:00+09:00',
    };

Map<String, Object?> accessTokenJson(String accessToken) => <String, Object?>{
  'accessToken': accessToken,
};

Map<String, Object?> rankingJson() => <String, Object?>{
  'items': <Map<String, Object?>>[
    <String, Object?>{
      'rank': 1,
      'stockId': 101,
      'symbol': '005930',
      'name': '삼성전자',
      'market': 'KOSPI',
      'category': 'INDIVIDUAL',
      'isDividend': true,
      'leverageFactor': null,
      'currency': 'KRW',
      'lastPrice': '74500',
      'prevClose': '74000',
      'changeAmount': '500',
      // 서버 규칙: (lastPrice-prevClose)/prevClose, 소수점 6자리 HALF_UP.
      'changeRate': '0.006757',
      'tradingAmount': '1234567890',
      'quoteAt': '2026-09-15T09:30:00+09:00',
      'realtime': true,
      'stockLikeId': null,
    },
  ],
  'nextCursor': 'cursor-1',
  'hasNext': true,
};

Map<String, Object?> exchangeRateJson() => <String, Object?>{
  'baseCurrency': 'USD',
  'quoteCurrency': 'KRW',
  'rate': '1398.50',
  'changeAmount': '2.30',
  'changeRate': '0.0016',
  'validFrom': '2026-09-15T15:00:00+09:00',
};

Map<String, Object?> marketStatusJson() => <String, Object?>{
  'markets': <Map<String, Object?>>[
    <String, Object?>{
      'marketCountry': 'KR',
      'open': true,
      'opensAt': '2026-09-15T09:00:00+09:00',
      'closesAt': '2026-09-15T15:30:00+09:00',
      'nextOpensAt': null,
    },
    <String, Object?>{
      'marketCountry': 'US',
      'open': false,
      'opensAt': null,
      'closesAt': null,
      'nextOpensAt': '2026-09-15T22:30:00+09:00',
    },
  ],
  'serverTime': '2026-09-15T10:00:00+09:00',
};
