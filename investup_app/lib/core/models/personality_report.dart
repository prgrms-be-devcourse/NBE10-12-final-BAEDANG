import 'json_read.dart';

/// GET /reports/me — 투자 성향 리포트(현재 활성 계좌=라운드 기준).
///
/// `locked`가 true면 unlockAt(개설 + N주)에 못 미친 상태라 자산·유형 필드가
/// 전부 null/빈 값이다. 잠금이 풀린 뒤에도 "고정 스냅샷"이 아니라 열 때마다
/// 다시 계산된다 — `asOf`가 매번 최신 계산 시각이다.
///
/// `classified`는 보유 종목 2개 이상일 때만 true — 미만이면 typeCode/typeLabel이
/// null인 "미분류/신규" 상태로, `shares`는 계산된 값이 그대로 담긴다.
class PersonalityReport {
  const PersonalityReport({
    required this.accountId,
    required this.roundNo,
    required this.locked,
    required this.unlockAt,
    required this.classified,
    required this.holdingCount,
    required this.holdingPeriodWeeks,
    required this.longHeldStocks,
    required this.asOf,
    this.initialCash,
    this.cashBalance,
    this.stockValue,
    this.totalAsset,
    this.totalPnl,
    this.returnRate,
    this.typeCode,
    this.typeLabel,
    this.shares,
  });

  factory PersonalityReport.fromJson(Map<String, Object?> json) =>
      PersonalityReport(
        accountId: json.requireInt('accountId'),
        roundNo: json.requireInt('roundNo'),
        locked: json.requireBool('locked'),
        unlockAt: json.dateTimeOrNull('unlockAt'),
        initialCash: json.stringOrNull('initialCash'),
        cashBalance: json.stringOrNull('cashBalance'),
        stockValue: json.stringOrNull('stockValue'),
        totalAsset: json.stringOrNull('totalAsset'),
        totalPnl: json.stringOrNull('totalPnl'),
        returnRate: json.stringOrNull('returnRate'),
        classified: json.boolOrNull('classified') ?? false,
        typeCode: json.stringOrNull('typeCode'),
        typeLabel: json.stringOrNull('typeLabel'),
        shares: json.objectOrNull('shares') != null
            ? PersonalityShares.fromJson(json.objectOrNull('shares')!)
            : null,
        holdingCount: json.intOrNull('holdingCount') ?? 0,
        holdingPeriodWeeks: json.intOrNull('holdingPeriodWeeks') ?? 0,
        longHeldStocks: json
            .requireObjectList('longHeldStocks')
            .map(LongHeldStock.fromJson)
            .toList(growable: false),
        asOf: json.dateTimeOrNull('asOf'),
      );

  final int accountId;
  final int roundNo;
  final bool locked;
  final DateTime? unlockAt;
  final String? initialCash;
  final String? cashBalance;
  final String? stockValue;
  final String? totalAsset;
  final String? totalPnl;

  /// 초기자본 대비 총손익 — 0~1 소수 문자열.
  final String? returnRate;
  final bool classified;

  /// 4글자 유형 코드(예: "CKSB") — 분산·시장·유형·공격성 순. 미분류/잠김이면 null.
  final String? typeCode;
  final String? typeLabel;
  final PersonalityShares? shares;
  final int holdingCount;
  final int holdingPeriodWeeks;
  final List<LongHeldStock> longHeldStocks;
  final DateTime? asOf;
}

/// 4축 각각의 "높은 쪽(집중C·국내K·개별주S·공격A)" 평가비중 — 0~1 소수 문자열.
class PersonalityShares {
  const PersonalityShares({
    required this.concentration,
    required this.domestic,
    required this.individual,
    required this.aggressive,
  });

  factory PersonalityShares.fromJson(Map<String, Object?> json) =>
      PersonalityShares(
        concentration: json.stringOrNull('concentration'),
        domestic: json.stringOrNull('domestic'),
        individual: json.stringOrNull('individual'),
        aggressive: json.stringOrNull('aggressive'),
      );

  final String? concentration;
  final String? domestic;
  final String? individual;
  final String? aggressive;
}

/// holdingPeriodWeeks 이상 들고 있는 종목 — 보유 시작일 오름차순.
class LongHeldStock {
  const LongHeldStock({
    required this.symbol,
    required this.name,
    required this.currency,
    required this.avgBuyPrice,
    required this.lastPrice,
    required this.returnRate,
    required this.heldSince,
  });

  factory LongHeldStock.fromJson(Map<String, Object?> json) => LongHeldStock(
    symbol: json.requireString('symbol'),
    name: json.requireString('name'),
    currency: json.stringOrNull('currency'),
    avgBuyPrice: json.stringOrNull('avgBuyPrice'),
    lastPrice: json.stringOrNull('lastPrice'),
    returnRate: json.stringOrNull('returnRate'),
    heldSince: json.dateTimeOrNull('heldSince'),
  );

  final String symbol;
  final String name;
  final String? currency;
  final String? avgBuyPrice;
  final String? lastPrice;

  /// 시세가 없으면 null.
  final String? returnRate;
  final DateTime? heldSince;
}

/// GET /reports/leaderboard — 아침 배치 스냅샷 기준 수익률 리더보드.
/// 스냅샷이 없거나 참가자 0명이면 asOf: null · top: [] · me: null.
class Leaderboard {
  const Leaderboard({
    required this.asOf,
    required this.participants,
    required this.top,
    required this.me,
  });

  factory Leaderboard.fromJson(Map<String, Object?> json) => Leaderboard(
    asOf: json.dateTimeOrNull('asOf'),
    participants: json.intOrNull('participants') ?? 0,
    top: json
        .requireObjectList('top')
        .map(LeaderboardEntry.fromJson)
        .toList(growable: false),
    me: json.objectOrNull('me') != null
        ? LeaderboardMe.fromJson(json.objectOrNull('me')!)
        : null,
  );

  final DateTime? asOf;
  final int participants;
  final List<LeaderboardEntry> top;
  final LeaderboardMe? me;
}

/// 닉네임은 백엔드가 이미 가운데 글자를 마스킹해서 내려준다.
class LeaderboardEntry {
  const LeaderboardEntry({
    required this.rank,
    required this.nickname,
    required this.returnRate,
  });

  factory LeaderboardEntry.fromJson(Map<String, Object?> json) =>
      LeaderboardEntry(
        rank: json.requireInt('rank'),
        nickname: json.requireString('nickname'),
        returnRate: json.stringOrNull('returnRate'),
      );

  final int rank;
  final String nickname;
  final String? returnRate;
}

class LeaderboardMe {
  const LeaderboardMe({
    required this.rank,
    required this.returnRate,
    required this.topPercent,
    required this.neighbors,
    this.typeCode,
    this.typeLabel,
    this.typeRank,
    this.typeParticipants,
    this.typePercent,
  });

  factory LeaderboardMe.fromJson(Map<String, Object?> json) => LeaderboardMe(
    rank: json.requireInt('rank'),
    returnRate: json.stringOrNull('returnRate'),
    topPercent: json.intOrNull('topPercent'),
    neighbors: json
        .requireObjectList('neighbors')
        .map(LeaderboardEntry.fromJson)
        .toList(growable: false),
    typeCode: json.stringOrNull('typeCode'),
    typeLabel: json.stringOrNull('typeLabel'),
    typeRank: json.intOrNull('typeRank'),
    typeParticipants: json.intOrNull('typeParticipants'),
    typePercent: json.intOrNull('typePercent'),
  );

  final int rank;
  final String? returnRate;

  /// 1/5/10/25/50/75 중 하나, 하위권이면 null.
  final int? topPercent;
  final List<LeaderboardEntry> neighbors;

  /// 유형 코호트 안에서의 순위 — 미분류면 다섯 필드 전부 null.
  final String? typeCode;
  final String? typeLabel;
  final int? typeRank;
  final int? typeParticipants;
  final int? typePercent;
}

/// GET /reports/leaderboard/types — 같은 라운드 코호트의 유형별 평균 수익률.
class LeaderboardTypes {
  const LeaderboardTypes({required this.asOf, required this.types});

  factory LeaderboardTypes.fromJson(Map<String, Object?> json) =>
      LeaderboardTypes(
        asOf: json.dateTimeOrNull('asOf'),
        types: json
            .requireObjectList('types')
            .map(LeaderboardTypeEntry.fromJson)
            .toList(growable: false),
      );

  final DateTime? asOf;
  final List<LeaderboardTypeEntry> types;
}

class LeaderboardTypeEntry {
  const LeaderboardTypeEntry({
    required this.typeCode,
    required this.typeLabel,
    required this.count,
    required this.avgReturnRate,
  });

  factory LeaderboardTypeEntry.fromJson(Map<String, Object?> json) =>
      LeaderboardTypeEntry(
        typeCode: json.requireString('typeCode'),
        typeLabel: json.stringOrNull('typeLabel'),
        count: json.intOrNull('count') ?? 0,
        avgReturnRate: json.stringOrNull('avgReturnRate'),
      );

  final String typeCode;
  final String? typeLabel;
  final int count;

  /// 0~1 소수 문자열.
  final String? avgReturnRate;
}
