"use client";

import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import type { AuthUser } from "@/lib/api";

const STORAGE_KEY = "trading-auth-user";

/**
 * 실제 `/api/auth/login`·`/api/auth/signup` 응답(userId/email/nickname)을 들고 있는
 * 로그인 상태 컨텍스트입니다. 백엔드가 토큰을 발급하지 않는 1주차 방식이라, 로그인
 * 유지도 우리가 직접 localStorage에 사용자 정보를 저장해서 흉내냅니다.
 *
 * 2주차에 JWT가 붙으면 `setUser`가 받는 값에 `accessToken`이 추가되고, 이후 요청에
 * `Authorization` 헤더를 실어 보내도록 이 파일만 바꾸면 됩니다 — 화면 코드는 그대로.
 */
type AuthState = {
  user: AuthUser | null;
  isLoggedIn: boolean;
  setUser: (user: AuthUser) => void;
  logout: () => void;
};

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUserState] = useState<AuthUser | null>(null);

  // localStorage는 서버에 없으므로 마운트 후에만 읽는다 (SSR 하이드레이션 불일치 방지).
  // 외부 저장소(localStorage)를 최초 1회 동기화하는 것이라 useEffect가 맞는 자리이지만,
  // eslint-plugin-react-hooks가 "effect 안에서 setState 직접 호출"을 일괄 경고하므로 예외 처리.
  useEffect(() => {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return;
    try {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setUserState(JSON.parse(raw) as AuthUser);
    } catch {
      window.localStorage.removeItem(STORAGE_KEY);
    }
  }, []);

  function setUser(next: AuthUser) {
    setUserState(next);
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
  }

  function logout() {
    setUserState(null);
    window.localStorage.removeItem(STORAGE_KEY);
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
