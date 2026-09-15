import 'package:dio/dio.dart';

import '../models/personality_report.dart';
import 'api_client.dart';
import 'auth_requirement.dart';

/// 투자 성향 리포트·리더보드 API. 전부 로그인이 필요하다.
class ReportApi {
  ReportApi(this._client);

  static const String _me = 'reports/me';
  static const String _leaderboard = 'reports/leaderboard';
  static const String _leaderboardTypes = 'reports/leaderboard/types';

  final ApiClient _client;

  /// 내 투자 성향 리포트 — 잠김/미분류/공개 세 상태를 그린다.
  Future<PersonalityReport> getMyReport({CancelToken? cancelToken}) async {
    final json = await _client.getObject(
      _me,
      auth: AuthRequirement.required,
      cancelToken: cancelToken,
    );
    return _client.decode(() => PersonalityReport.fromJson(json));
  }

  /// 수익률 리더보드 — 아침 배치 스냅샷 기준.
  Future<Leaderboard> getLeaderboard({CancelToken? cancelToken}) async {
    final json = await _client.getObject(
      _leaderboard,
      auth: AuthRequirement.required,
      cancelToken: cancelToken,
    );
    return _client.decode(() => Leaderboard.fromJson(json));
  }

  /// 유형별 평균 수익률 비교 — 미분류 계좌는 집계에서 빠진다.
  Future<LeaderboardTypes> getLeaderboardTypes({
    CancelToken? cancelToken,
  }) async {
    final json = await _client.getObject(
      _leaderboardTypes,
      auth: AuthRequirement.required,
      cancelToken: cancelToken,
    );
    return _client.decode(() => LeaderboardTypes.fromJson(json));
  }
}
