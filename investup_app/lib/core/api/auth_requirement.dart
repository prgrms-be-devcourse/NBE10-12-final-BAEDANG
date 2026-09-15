/// 요청별 인증 의도. 경로 문자열로 공개 여부를 추정하지 않는다.
enum AuthRequirement {
  /// Authorization을 보내지 않는다. 로그인·가입·refresh.
  public,

  /// 로그인 상태면 보내고 아니면 보내지 않는다. 랭킹의 찜 상태 등.
  optional,

  /// 반드시 보낸다. 토큰이 없으면 서버가 401로 거절한다.
  required,
}

/// Dio `Options.extra`에 실어 인터셉터와 주고받는 키.
abstract final class RequestMeta {
  static const String authRequirement = 'investup.authRequirement';
  static const String retriedAfterRefresh = 'investup.retriedAfterRefresh';
  static const String sentAccessToken = 'investup.sentAccessToken';
  static const String sessionGeneration = 'investup.sessionGeneration';
}
