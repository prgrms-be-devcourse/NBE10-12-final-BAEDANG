"use client";

import { createContext, useContext, useState, type ReactNode } from "react";

/**
 * 로그인 상태를 흉내내는 임시 컨텍스트입니다. 실제 인증 API가 없는 상태라
 * "회원가입 유도 모달 → 가입하기 클릭 → 로그인 상태로 전환" 같은 화면 흐름을
 * 실제로 눌러서 확인할 수 있도록 클라이언트 상태로만 관리합니다.
 *
 * 2주차에 실제 로그인 API가 붙으면 이 파일만 교체하면 됩니다 — 나머지 화면은
 * useAuth() 훅만 보고 있어서 손댈 필요가 없습니다.
 */
type AuthState = {
  isLoggedIn: boolean;
  nickname: string;
  login: () => void;
  logout: () => void;
};

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [isLoggedIn, setIsLoggedIn] = useState(false);

  return (
    <AuthContext.Provider
      value={{
        isLoggedIn,
        nickname: "홍길동",
        login: () => setIsLoggedIn(true),
        logout: () => setIsLoggedIn(false),
      }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth는 AuthProvider 안에서만 쓸 수 있습니다.");
  return ctx;
}
