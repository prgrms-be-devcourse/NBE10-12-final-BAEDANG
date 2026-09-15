import 'json_read.dart';

/// GET /stocks/{symbol}/financials — 국내 종목 재무제표.
///
/// US 종목·ETF/ETN은 FINANCIALS_NOT_SUPPORTED(422)라 호출부가 그 에러를
/// "이 종목엔 섹션을 보여주지 않음"으로 다룬다.
class StockFinancials {
  const StockFinancials({
    required this.symbol,
    required this.dataStatus,
    required this.industryName,
    required this.calculatedPer,
    required this.annual,
    required this.quarterly,
  });

  factory StockFinancials.fromJson(Map<String, Object?> json) =>
      StockFinancials(
        symbol: json.requireString('symbol'),
        dataStatus: json.stringOrNull('dataStatus'),
        industryName: json
            .objectOrNull('industry')
            ?.objectOrNull('small')
            ?.stringOrNull('name'),
        calculatedPer: json
            .objectOrNull('valuation')
            ?.stringOrNull('calculatedPer'),
        annual: json
            .requireObjectList('annual')
            .map(FinancialPeriod.fromJson)
            .toList(growable: false),
        quarterly: json
            .requireObjectList('quarterly')
            .map(FinancialPeriod.fromJson)
            .toList(growable: false),
      );

  final String symbol;

  /// FRESH 또는 STALE — STALE이면 "일부 정보가 최신이 아닐 수 있어요"를 붙인다.
  final String? dataStatus;
  final String? industryName;

  /// 현재가 ÷ 최근 연간 EPS. 서버가 계산해 내려준다.
  final String? calculatedPer;
  final List<FinancialPeriod> annual;
  final List<FinancialPeriod> quarterly;

  bool get isStale => dataStatus == 'STALE';
}

/// 연간/분기 한 기간의 재무 수치. 금액·비율 모두 문자열로 내려오고 비율은 이미 %다.
class FinancialPeriod {
  const FinancialPeriod({
    required this.statementYearMonth,
    this.sales,
    this.operatingProfit,
    this.netIncome,
    this.totalAssets,
    this.totalLiabilities,
    this.totalEquity,
    this.eps,
    this.roe,
    this.operatingProfitMargin,
    this.netProfitMargin,
    this.debtRatio,
  });

  factory FinancialPeriod.fromJson(Map<String, Object?> json) {
    final income = json.objectOrNull('incomeStatement');
    final balance = json.objectOrNull('balanceSheet');
    final ratios = json.objectOrNull('ratios');
    return FinancialPeriod(
      statementYearMonth: json.requireString('statementYearMonth'),
      sales: income?.stringOrNull('sales'),
      operatingProfit: income?.stringOrNull('operatingProfit'),
      netIncome: income?.stringOrNull('netIncome'),
      totalAssets: balance?.stringOrNull('totalAssets'),
      totalLiabilities: balance?.stringOrNull('totalLiabilities'),
      totalEquity: balance?.stringOrNull('totalEquity'),
      eps: ratios?.stringOrNull('eps'),
      roe: ratios?.stringOrNull('roe'),
      operatingProfitMargin: ratios?.stringOrNull('operatingProfitMargin'),
      netProfitMargin: ratios?.stringOrNull('netProfitMargin'),
      debtRatio: ratios?.stringOrNull('debtRatio'),
    );
  }

  /// 'YYYYMM' 형식.
  final String statementYearMonth;
  final String? sales;
  final String? operatingProfit;
  final String? netIncome;
  final String? totalAssets;
  final String? totalLiabilities;
  final String? totalEquity;
  final String? eps;
  final String? roe;
  final String? operatingProfitMargin;
  final String? netProfitMargin;
  final String? debtRatio;

  /// '202412' → '2024.12'
  String get label {
    final ym = statementYearMonth;
    if (ym.length != 6) return ym;
    return '${ym.substring(0, 4)}.${ym.substring(4, 6)}';
  }
}
