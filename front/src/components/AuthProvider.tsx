"use client";

import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { refreshAccessToken, setAuthEventListeners, syncAuthTokens, type AuthUser } from "@/lib/api";
import { useVisiblePolling } from "@/lib/useVisiblePolling";

const STORAGE_KEY = "trading-auth-user";

// accessToken 수명은 15분(`JWT_ACCESS_TTL`, back/.env)이다 — 그보다 여유 있게(10분)
// 미리 재발급해서, 정상적으로 탭을 켜두고 쓰는 동안은 만료로 인한 401을 거의 안
// 겪게 한다. 탭이 오래 백그라운드에 있다 돌아온 직후처럼 이 주기를 놓치는 경우의
// 안전망은 `lib/api.ts`의 request()가 요청 실패 시점에 한 번 더 재시도해준다.
const ACCESS_TOKEN_PROACTIVE_REFRESH_MS = 10 * 60 * 1000;

/**
 * 실제 `/api/auth/login`·`/api/auth/signup` 응답(userId/email/nickname/accessToken/
 * refreshToken)을 들고 있는 로그인 상태 컨텍스트입니다. 로그인 유지는 이 값을
 * localStorage에 저장해서 흉내냅니다(진짜 세션 쿠키가 아니라, 새로고침해도
 * 로그인 상태가 남아있게 하는 용도).
 *
 * <p>백엔드는 stateless JWT를 씁니다 — 보호된 API(`/api/accounts/**` 등)는
 * `Authorization: Bearer <accessToken>` 헤더가 있어야 통과합니다. `user` 상태를
 * 바꾸는 지점마다({@link applyUser}) `@/lib/api`의 토큰 저장소도 같이 맞춰서,
 * 화면 코드는 `getAccountSummary()`처럼 토큰을 직접 몰라도 되게 합니다.
 */
type AuthState = {
  user: AuthUser | null;
  isLoggedIn: boolean;
  setUser: (user: AuthUser) => void;
  logout: () => void;
};

const AuthContext = createContext<AuthState | null>(null);

/**
 * `user` 상태를 실제로 바꾸는 모든 지점(하이드레이션·로그인·로그아웃·토큰 재발급)에서
 * 반드시 이 함수를 통해서만 바꾼다 — localStorage 반영과 `@/lib/api`의 토큰 저장소
 * 동기화를 "state를 바꾸는 것과 같은 동기 실행 흐름 안"에서 끝내기 위해서다.
 *
 * <p>처음엔 토큰 동기화를 별도 `useEffect(() => syncAuthTokens(...), [user])`로
 * 뒀었는데, 실제로 붙여보니 레이스 컨디션이 있었다 — React는 같은 커밋 안에서
 * 자식 컴포넌트의 effect를 부모(이 Provider)의 effect보다 먼저 실행한다. `user`가
 * null → 실제 값으로 바뀌는 리렌더가 일어나면, `/my` 같은 화면의 "로그인됐으니
 * 계좌 조회" effect가 이 Provider의 "토큰 저장소 동기화" effect보다 먼저 실행되어,
 * 토큰이 아직 반영되기 전에 `getAccountSummary()`가 나가 401(UNAUTHENTICATED)로
 * 실패했다. state 변경과 토큰 동기화를 같은 함수 안에서 동시에 처리하면, 다음
 * 리렌더가 시작되기 전에 토큰 저장소가 이미 맞춰져 있어서 이 문제가 없다.
 */
function applyUser(setUserState: (u: AuthUser | null) => void, next: AuthUser | null) {
  syncAuthTokens(next ? { accessToken: next.accessToken, refreshToken: next.refreshToken } : null);
  try {
    if (next) window.localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
    else window.localStorage.removeItem(STORAGE_KEY);
  } catch {
    // localStorage를 못 쓰는 환경(프라이빗 모드 등)이어도 로그인 자체는 되게 둔다.
  }
  setUserState(next);
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUserState] = useState<AuthUser | null>(null);

  // localStorage는 서버에 없으므로 마운트 후에만 읽는다 (SSR 하이드레이션 불일치 방지).
  // 외부 저장소(localStorage)를 최초 1회 동기화하는 것이라 useEffect가 맞는 자리이지만,
  // eslint-plugin-react-hooks가 "effect 안에서 setState 직접 호출"을 일괄 경고하므로 예외 처리.
  useEffect(() => {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return;
    try {
      const parsed = JSON.parse(raw) as AuthUser;
      syncAuthTokens({ accessToken: parsed.accessToken, refreshToken: parsed.refreshToken });
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setUserState(parsed);
    } catch {
      window.localStorage.removeItem(STORAGE_KEY);
    }
  }, []);

  // request()가 만료된 accessToken을 조용히 재발급했을 때/refreshToken마저 무효라
  // 재발급 자체가 실패했을 때 알림받는다. setUserState를 함수형으로 갱신해서
  // 등록 시점의 오래된 user를 참조하는 문제(stale closure) 없이 항상 최신 상태에
  // 반영한다. 여기서의 토큰 저장소 동기화는 request() 자신이 이미 즉시 처리하므로
  // (api.ts 참고) applyUser를 또 부를 필요는 없고, localStorage/화면 상태만 맞춘다.
  useEffect(() => {
    setAuthEventListeners({
      onAccessTokenRefreshed: (accessToken) => {
        setUserState((prev) => {
          if (!prev) return prev;
          const next = { ...prev, accessToken };
          try {
            window.localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
          } catch {
            // 위와 같은 이유로 무시한다.
          }
          return next;
        });
      },
      onAuthExpired: () => applyUser(setUserState, null),
    });
    return () => setAuthEventListeners({});
  }, []);

  // accessToken이 실제로 만료되기 전에 미리 재발급해서 세션을 유지한다(위 상수 설명 참고).
  useVisiblePolling(
    () => {
      if (!user) return;
      refreshAccessToken(user.refreshToken)
        .then(({ accessToken }) => applyUser(setUserState, { ...user, accessToken }))
        .catch(() => {
          // refreshToken도 만료·무효면 재로그인이 필요하다 — 조용히 로그아웃 상태로 되돌린다.
          applyUser(setUserState, null);
        });
    },
    ACCESS_TOKEN_PROACTIVE_REFRESH_MS,
    !!user
  );

  function setUser(next: AuthUser) {
    applyUser(setUserState, next);
  }

  function logout() {
    applyUser(setUserState, null);
  }

  return (
    <AuthContext.Provider value={{ user, isLoggedIn: user !== null, setUser, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth는 AuthProvider 안에서만 쓸 수 있습니다.");
  return ctx;
}
