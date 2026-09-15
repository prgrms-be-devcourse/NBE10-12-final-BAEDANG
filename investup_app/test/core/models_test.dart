import 'package:flutter_test/flutter_test.dart';
import 'package:investup_app/core/models/account_summary.dart';
import 'package:investup_app/core/models/auth_response.dart';
import 'package:investup_app/core/models/market_country.dart';
import 'package:investup_app/core/models/market_status.dart';
import 'package:investup_app/core/models/ranking.dart';

import 'fakes/fixtures.dart';

void main() {
  test('가입/로그인 응답은 평탄한 프로필과 중첩 account를 파싱한다', () {
    final response = AuthResponse.fromJson(authJson());

    expect(response.profile.userId, 7);
    expect(response.profile.email, 'user@example.com');
    expect(response.profile.nickname, '사용자');
    expect(response.accessToken, 'access-token');
    expect(response.refreshToken, 'refresh-token');
    expect(response.account.accountId, 3);
    expect(response.account.roundNo, 1);
    expect(response.account.initialCash, '50000000');
    expect(response.account.cashBalance, '50000000');
  });

  test('가입/로그인 응답에 account가 없으면 파싱을 거절한다', () {
    final json = authJson()..remove('account');
    expect(() => AuthResponse.fromJson(json), throwsFormatException);
  });

  test('userId가 문자열이면 숫자로 넘겨짚지 않는다', () {
    final json = authJson();
    json['userId'] = '7';
    expect(() => AuthResponse.fromJson(json), throwsFormatException);
  });

  test('계좌 요약은 생략된 선택 필드를 null로 둔다', () {
    final full = AccountSummary.fromJson(accountSummaryJson());
    expect(full.accountId, 3);
    expect(full.roundNo, 1);
    expect(full.cashBalance, '49000000');
    expect(full.unrealizedPnlRate, '0.2');
    expect(full.exchangeRate, '1380.5');
    expect(full.asOf, DateTime.parse('2026-09-15T10:00:00+09:00'));

    final minimal = AccountSummary.fromJson(<String, Object?>{
      'accountId': 3,
      'roundNo': 2,
    });
    expect(minimal.roundNo, 2);
    expect(minimal.cashBalance, isNull);
    expect(minimal.totalAsset, isNull);
    expect(minimal.asOf, isNull);
  });

  test('시장 상태는 시장별 세션과 KST 시각을 파싱한다', () {
    final status = MarketStatus.fromJson(marketStatusJson());

    expect(status.markets, hasLength(2));
    expect(status.serverTime, DateTime.parse('2026-09-15T10:00:00+09:00'));

    final kr = status.sessionOf(MarketCountry.kr)!;
    expect(kr.open, isTrue);
    expect(kr.opensAt, DateTime.parse('2026-09-15T09:00:00+09:00'));
    expect(kr.nextOpensAt, isNull);

    final us = status.sessionOf(MarketCountry.us)!;
    expect(us.open, isFalse);
    expect(us.opensAt, isNull);
    expect(us.nextOpensAt, DateTime.parse('2026-09-15T22:30:00+09:00'));
  });

  test('랭킹은 평탄한 항목과 커서를 파싱한다', () {
    final page = RankingPage.fromJson(rankingJson());

    expect(page.items, hasLength(1));
    expect(page.nextCursor, 'cursor-1');
    expect(page.hasNext, isTrue);

    final item = page.items.single;
    expect(item.rank, 1);
    expect(item.stockId, 101);
    expect(item.symbol, '005930');
    expect(item.market, 'KOSPI');
    expect(item.category, StockCategory.individual);
    expect(item.currency, 'KRW');
    expect(item.lastPrice, '74500');
    expect(item.changeRate, '0.006757');
    expect(item.realtime, isTrue);
    expect(item.quoteAt, DateTime.parse('2026-09-15T09:30:00+09:00'));
    expect(item.stockLikeId, isNull);
  });

  test('모르는 분류·국가는 크래시 대신 unknown으로 둔다', () {
    expect(StockCategory.fromWire('FUND'), StockCategory.unknown);
    expect(StockCategory.fromWire(null), StockCategory.unknown);
    expect(MarketCountry.fromWire('JP'), MarketCountry.unknown);
    expect(MarketCountry.fromWire('kr'), MarketCountry.unknown);

    final json = rankingJson();
    json['items'] = <Map<String, Object?>>[
      <String, Object?>{
        'rank': 1,
        'stockId': 5,
        'symbol': 'AAPL',
        'name': 'Apple',
        'market': 'NASDAQ',
        'category': 'FUND',
        'realtime': false,
        'stockLikeId': 42,
      },
    ];
    final item = RankingPage.fromJson(json).items.single;
    expect(item.category, StockCategory.unknown);
    expect(item.lastPrice, isNull);
    expect(item.stockLikeId, 42);
  });

  test('필수 boolean이 빠지거나 오염되면 파싱을 거절한다', () {
    final missingOpen = marketStatusJson();
    ((missingOpen['markets']! as List)[0] as Map<String, Object?>)
        .remove('open');
    expect(() => MarketStatus.fromJson(missingOpen), throwsFormatException);

    final wrongOpen = marketStatusJson();
    ((wrongOpen['markets']! as List)[0] as Map<String, Object?>)['open'] =
        'true';
    expect(() => MarketStatus.fromJson(wrongOpen), throwsFormatException);

    final missingRealtime = rankingJson();
    ((missingRealtime['items']! as List)[0] as Map<String, Object?>)
        .remove('realtime');
    expect(() => RankingPage.fromJson(missingRealtime), throwsFormatException);

    final missingHasNext = rankingJson()..remove('hasNext');
    expect(() => RankingPage.fromJson(missingHasNext), throwsFormatException);
  });

  test('필수 목록이 빠지거나 원소가 깨지면 파싱을 거절한다', () {
    final missingMarkets = marketStatusJson()..remove('markets');
    expect(() => MarketStatus.fromJson(missingMarkets), throwsFormatException);

    final wrongMarkets = marketStatusJson()..['markets'] = 'not-a-list';
    expect(() => MarketStatus.fromJson(wrongMarkets), throwsFormatException);

    final brokenItem = rankingJson()..['items'] = <Object?>['broken'];
    expect(() => RankingPage.fromJson(brokenItem), throwsFormatException);
  });
}
