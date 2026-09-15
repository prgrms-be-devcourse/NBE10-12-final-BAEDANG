# Stateful 인증과 Refresh Token Rotation (#203)

[English](authentication.md) | 한국어

## 배포와 전송

기존 Vercel·EC2·PostgreSQL을 유지합니다. Next.js는 `POST /api/auth/{signup,login,refresh,logout}`만
백엔드로 중계하고, 나머지 API는 기존 `NEXT_PUBLIC_API_BASE_URL`에 Bearer Access로 요청합니다.
Redis·SMTP·비밀번호 재설정·인프라 프로비저닝은 범위에 포함하지 않습니다.

Vercel 서버 환경변수 `AUTH_BACKEND_URL`에는 NPMplus의 HTTPS 백엔드 Origin을,
`AUTH_PUBLIC_ORIGIN`에는 정확한 프론트 Origin을 지정합니다. 직접 호출하는
`NEXT_PUBLIC_API_BASE_URL`도 HTTPS 프론트에서 접근할 수 있는 HTTPS 주소여야 합니다.
EC2에는 `openssl rand -base64 32`로 생성한 별도 `AUTH_SESSION_ENCRYPTION_KEY`를 설정합니다.
JWT 서명 키와 분리하고 두 키 모두 재시작 시 유지합니다. 암호화 키를 바꿀 때는 기존 세션을 먼저 폐기합니다.

**외부 HTTPS 백엔드 주소는 아직 없습니다.** 로컬 구현·검증은 가능하지만 현재 HTTP IP로 운영 인증을
활성화할 수 없습니다. NPMplus HTTPS와 환경변수를 설정한 뒤 프론트·백엔드를 함께 배포해야 합니다.
설정 누락·외부 HTTP는 `AUTH_UNAVAILABLE`로 차단합니다. Vercel이 아닌 실행에서 양쪽 호스트가 모두
localhost/127.0.0.1일 때만 HTTP를 허용합니다. 예시는 `front/.env.example`을 참고합니다.
Vercel 미리보기 배포는 각각 정확한 프론트 Origin 설정이 필요합니다.

## 브라우저와 백엔드 계약

Access는 메모리에만 보관합니다. `baedang_refresh`는 HttpOnly, 운영 Secure, SameSite=Lax,
Path=/api/auth, host-only 쿠키이며 절대 세션 만료에 맞춰 만료됩니다. 중계 응답 JSON에서 Refresh를
제거합니다. 토큰을 localStorage·sessionStorage에 저장하지 않고 기존 `trading-auth-user`도 제거합니다.
localStorage에는 계정 변경·로그아웃 재시도 조정용 무작위 표식만 저장합니다.

네 중계 경로 모두 정확한 Origin, `X-Auth-Request: 1`, JSON Content-Type을 요구합니다.
Refresh/logout은 브라우저 본문의 토큰을 무시하고 쿠키에서만 읽습니다. 고정 경로, 10초 제한,
리다이렉트 금지, 요청·응답 no-store를 적용합니다. 백엔드는 중계 서버·API 클라이언트용 JSON 계약을
유지하고 인증 쿠키를 읽지 않습니다. Spring Security의 STATELESS는 HttpSession을 만들지 않는다는
뜻이며, PostgreSQL 로그인 세션의 활성 검증과는 별개입니다.

| 작업 | 백엔드 계약 | 브라우저 중계 계약 |
| --- | --- | --- |
| 가입/로그인 | 자격증명 → 사용자/계좌, accessToken, refreshToken, expiresAt | Refresh는 쿠키로만 전달 |
| 갱신 | `{refreshToken}` → accessToken, refreshToken, expiresAt | `{}` 요청, 쿠키 읽기·교체 |
| 로그아웃 | `{refreshToken}` → 200 빈 응답 | `{}` 요청, 쿠키 삭제, 204 |
| 복원 | 갱신 후 Bearer로 `GET /api/users/me` | 사용자 정보를 메모리에 복원 |

로그아웃은 같은 세션의 서명이 유효하고 만료 전인 Refresh면 회전 여부와 무관하게 허용하며 멱등적입니다.
중계 로그아웃은 이미 무효·만료된 401도 완료로 처리해 쿠키를 삭제합니다. 그 외 오류는 쿠키를 유지합니다.
갱신 오류는 새 로그인 쿠키를 지우지 않도록 쿠키를 변경하지 않습니다.

## 세션·잠금과 회전

Access 기본 15분, 세션 절대 수명 7일입니다. 회전으로 세션 만료를 연장하지 않고 Access도 이를 넘지
않습니다. JWT는 issuer·subject·token_type·sid·generation·무작위 jti를 포함합니다. sid 없는 기존 토큰은
재로그인이 필요합니다. 로그인별 독립 세션을 만들고 로그아웃은 현재 세션만, 비밀번호 변경·탈퇴는
사용자의 모든 세션을 같은 트랜잭션에서 폐기합니다.

인증 요청마다 JWT와 DB의 사용자·세션 활성 상태를 확인하며 캐시는 두지 않습니다. 폐기 커밋 뒤 시작한
검증은 기존 Access를 거절합니다. 이미 인증된 실행 중 요청을 소급 취소하지는 않습니다. DB 장애는
503으로 거절합니다. 로그인은 사용자 잠금 후 비밀번호를 검증합니다. 갱신·로그아웃·비밀번호 변경·탈퇴도
사용자 → 세션 순서로 잠급니다. 가입은 사용자·계좌·초기 원장·세션을 함께 커밋합니다.

현재 Refresh의 SHA-256 해시·세대가 일치하면 한 번 회전합니다. 회전 시점부터 **5초 동안** 직전 토큰은
**동일한 후속 Refresh**를 반환하며 회전하거나 유예를 연장하지 않습니다. 후속 토큰은 별도 키의
AES-256-GCM 암호문으로 보관하고 무작위 nonce와 세션 ID AAD를 사용합니다. 5초 경계 이후 또는
두 세대 전 정상 서명 토큰 재사용은 해당 세션만 폐기합니다. 폐기를 먼저 커밋한 뒤 오류를 반환합니다.
변조 토큰으로 다른 세션을 폐기할 수 없습니다. 현재 후속 토큰은 정상 회전합니다.

유예 중에는 직전 토큰을 가진 공격자와 정상 재시도를 구별할 수 없습니다. 유예는 무제한 응답 유실·지연
복구를 보장하지 않습니다. 매시간 정리 작업이 만료된 유예 암호문·해시를 비우고 절대 만료 후 7일 지난
세션을 삭제합니다. 물리적 정리 전에도 요청마다 유예 종료를 검사합니다.

## 프론트 조정과 전환

보이는 탭의 10분 선제 갱신, Access 만료 복구, 새로고침 복원이 탭 내 단일 진행 중 요청을 공유합니다.
Web Locks로 탭 간 쿠키 변경을 직렬화하고 BroadcastChannel과 공유 표식으로 로그인·로그아웃을
전파합니다. 두 API를 지원하는 현대 브라우저에서 탭 간 보장을 제공합니다. Web Locks 미지원 시
탭 내 공유만 적용하며, 5초 유예로 임의의 탭 간 동시 요청까지 보장하지 않습니다.

갱신 후 원래 요청은 본문·clientOrderId를 유지해 최대 한 번 재시도합니다. 이전 계정 요청을 새 계정으로
재시도하지 않습니다. 토큰 무효는 메모리를 지우고, 네트워크·5xx는 다음 visible/online/폴링 복구를 위해
유지합니다. 로그아웃은 메모리를 즉시 지우고 늦은 응답을 무효화합니다. 서버 로그아웃 실패 표식이 있으면
다음 복원·로그인 전에 로그아웃부터 재시도합니다. 비밀번호 변경은 안내와 함께 재로그인으로 이동합니다.

닉네임 변경은 요청 당시 세션 epoch·표식을 캡처해 로그인·로그아웃·다른 탭의 계정 변경 후 도착한
성공 응답도 무시합니다. 프로필 전용 이벤트에는 userId·email·nickname만 담고 토큰을 넣지 않습니다.
AuthProvider는 현재 사용자와 일치할 때만 반영하며 최신 Access를 유지합니다.

오류는 `TOKEN_EXPIRED`, `INVALID_TOKEN`, `SESSION_REVOKED`, `REFRESH_TOKEN_REUSED`,
`AUTH_UNAVAILABLE`로 구분합니다. 상세 정의와 검증 범위는 영문 문서의 대응 절과 같습니다.
V16은 auth_session만 추가하며 기존 회원·계좌·거래 데이터를 보존합니다. 배포 전 토큰은 재로그인이
필요하고 프론트만 되돌리는 롤백은 RTR 계약과 호환되지 않습니다. 실제 HTTPS 주소가 설정되면 운영
쿠키 전달을 별도로 확인해야 합니다. E2E는 브라우저와 검증용 API의 세션을 분리하고 단일 worker를 유지합니다.

브라우저 인증 요청은 헤더·본문 수신을 포함해 별도 15초 제한을 적용합니다. 시간 초과 시 fetch를 중단하고 Web Lock과 진행 중 갱신을 해제합니다. REQUEST_TIMEOUT은 일시 오류로 처리해 인증을 지우거나 토큰 회전을 자동 재시도하지 않으며, 실패한 로그아웃 표식은 유지합니다.
