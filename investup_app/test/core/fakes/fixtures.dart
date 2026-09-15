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

Map<String, Object?> holdingsJson() => <String, Object?>{
  'items': <Map<String, Object?>>[
    <String, Object?>{
      'symbol': '005930',
      'name': '삼성전자',
      'currency': 'KRW',
      'quantity': '10',
      'avgBuyPrice': '74000',
      'avgExchangeRate': '1',
      'lastPrice': '74500',
      'evaluationAmount': '745000',
      'unrealizedPnl': '5000',
      'unrealizedPnlRate': '0.006757',
      'realtime': true,
    },
  ],
  'asOf': '2026-09-15T10:00:00+09:00',
};

Map<String, Object?> ledgerJson() => <String, Object?>{
  'items': <Map<String, Object?>>[
    <String, Object?>{
      'entryId': 1,
      'entryType': 'INITIAL_DEPOSIT',
      'amount': '50000000',
      'balanceAfter': '50000000',
      'exchangeRate': '1',
      'memo': '모의 투자금 지급',
      'orderId': null,
      'symbol': null,
      'name': null,
      'occurredAt': '2026-09-15T09:00:00+09:00',
    },
    <String, Object?>{
      'entryId': 2,
      'entryType': 'BUY',
      'amount': '-148148',
      'balanceAfter': '49851852',
      'exchangeRate': '1',
      'memo': '삼성전자 매수',
      'orderId': 77,
      'symbol': '005930',
      'name': '삼성전자',
      'occurredAt': '2026-09-15T10:01:00+09:00',
    },
  ],
  'nextCursor': null,
  'hasNext': false,
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

Map<String, Object?> personalityReportJson({bool locked = false}) =>
    locked
        ? <String, Object?>{
            'accountId': 3,
            'roundNo': 1,
            'locked': true,
            'unlockAt': '2026-10-10T00:00:00+09:00',
            'initialCash': null,
            'cashBalance': null,
            'stockValue': null,
            'totalAsset': null,
            'totalPnl': null,
            'returnRate': null,
            'classified': false,
            'typeCode': null,
            'typeLabel': null,
            'shares': null,
            'holdingCount': 1,
            'holdingPeriodWeeks': 4,
            'longHeldStocks': <Object?>[],
            'asOf': '2026-09-15T09:00:00+09:00',
          }
        : <String, Object?>{
            'accountId': 3,
            'roundNo': 1,
            'locked': false,
            'unlockAt': '2026-09-12T00:00:00+09:00',
            'initialCash': '50000000',
            'cashBalance': '30000000',
            'stockValue': '22000000',
            'totalAsset': '52000000',
            'totalPnl': '2000000',
            'returnRate': '0.04',
            'classified': true,
            'typeCode': 'DKSB',
            'typeLabel': '분산·국내·개별주·안정',
            'shares': <String, Object?>{
              'concentration': '0.32',
              'domestic': '0.85',
              'individual': '0.90',
              'aggressive': '0.05',
            },
            'holdingCount': 3,
            'holdingPeriodWeeks': 4,
            'longHeldStocks': <Object?>[
              <String, Object?>{
                'symbol': '005930',
                'name': '삼성전자',
                'currency': 'KRW',
                'avgBuyPrice': '74000',
                'lastPrice': '74500',
                'returnRate': '0.006757',
                'heldSince': '2026-08-15T00:00:00+09:00',
              },
            ],
            'asOf': '2026-09-15T09:00:00+09:00',
          };

Map<String, Object?> leaderboardJson() => <String, Object?>{
  'asOf': '2026-09-15T06:00:00+09:00',
  'participants': 128,
  'top': <Object?>[
    <String, Object?>{
      'rank': 1,
      'nickname': '투*왕',
      'returnRate': '0.253',
    },
    <String, Object?>{
      'rank': 2,
      'nickname': '주*신',
      'returnRate': '0.182',
    },
    <String, Object?>{
      'rank': 3,
      'nickname': '분*투',
      'returnRate': '0.120',
    },
  ],
  'me': <String, Object?>{
    'rank': 12,
    'returnRate': '0.012',
    'topPercent': 10,
    'neighbors': <Object?>[
      <String, Object?>{
        'rank': 11,
        'nickname': '이*어',
        'returnRate': '0.015',
      },
      <String, Object?>{
        'rank': 13,
        'nickname': '초*자',
        'returnRate': '0.008',
      },
    ],
    'typeCode': 'DKSB',
    'typeLabel': '분산·국내·개별주·안정',
    'typeRank': 3,
    'typeParticipants': 9,
    'typePercent': 34,
  },
};

Map<String, Object?> leaderboardTypesJson() => <String, Object?>{
  'asOf': '2026-09-15T06:00:00+09:00',
  'types': <Object?>[
    <String, Object?>{
      'typeCode': 'DKSB',
      'typeLabel': '분산·국내·개별주·안정',
      'count': 9,
      'avgReturnRate': '0.021',
    },
    <String, Object?>{
      'typeCode': 'CGSA',
      'typeLabel': '집중·해외·개별주·공격',
      'count': 14,
      'avgReturnRate': '0.112',
    },
  ],
};
