"use client";

import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { logoutUser, refreshAccessToken, restoreAuth, setAuthEventListeners, publishAuthUser, type AuthUser } from "@/lib/api";
import { useVisiblePolling } from "@/lib/useVisiblePolling";

type AuthState = { user: AuthUser | null; isLoggedIn: boolean; setUser: (user: AuthUser) => void; logout: () => void };
const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUserState] = useState<AuthUser | null>(null);
  useEffect(() => {
    try { localStorage.removeItem('trading-auth-user'); } catch { /* 기존 토큰은 복원하지 않습니다. */ }
    setAuthEventListeners({
      onAccessTokenRefreshed: accessToken => setUserState(previous => previous ? { ...previous, accessToken } : null),
      onAuthExpired: () => setUserState(null),
      onUserChanged: setUserState,
    });
    let disposed = false;
    const restore = () => { void restoreAuth().then(restored => {
      if (!disposed && restored) setUserState(restored);
    }).catch(() => { /* 일시 장애는 다음 online/visible 이벤트에 재시도합니다. */ }); };
    const visible = () => { if (document.visibilityState === 'visible') restore(); };
    restore();
    window.addEventListener('online', restore);
    document.addEventListener('visibilitychange', visible);
    return () => {
      disposed = true;
      setAuthEventListeners({});
      window.removeEventListener('online', restore);
      document.removeEventListener('visibilitychange', visible);
    };
  }, []);
  useVisiblePolling(() => {
    if (user) void refreshAccessToken().catch(() => {});
  }, 10 * 60 * 1000, !!user);

  function setUser(next: AuthUser) { publishAuthUser(next); setUserState(next); }
  function logout() {
    void logoutUser().catch(() => {
      // 서버 폐기 실패는 성공으로 안내하지 않습니다. 다음 접속에서 인증 복원보다 먼저 재시도합니다.
      window.alert('서버 로그아웃을 완료하지 못했어요. 연결을 확인한 뒤 다시 시도해주세요.');
    });
  }
  return <AuthContext.Provider value={{ user, isLoggedIn: user !== null, setUser, logout }}>{children}</AuthContext.Provider>;
}
export function useAuth(): AuthState {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth는 AuthProvider 안에서만 쓸 수 있습니다.');
  return context;
}
