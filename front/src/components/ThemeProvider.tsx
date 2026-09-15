"use client";

import { createContext, useContext, useEffect, useState, type ReactNode } from "react";

const STORAGE_KEY = "trading-theme";

type Theme = "light" | "dark";

type ThemeState = {
  theme: Theme;
  setTheme: (theme: Theme) => void;
};

const ThemeContext = createContext<ThemeState | null>(null);

/**
 * 라이트/다크 테마 컨텍스트. `<html>`의 `data-theme` 속성을 갈아끼워
 * `globals.css`의 CSS 변수(`:root[data-theme="dark"]`)를 전환한다.
 *
 * 서버에는 localStorage가 없어 SSR 시점엔 테마를 알 수 없으므로, 마운트 전까지는
 * `globals.css`의 `prefers-color-scheme` 미디어쿼리가 대신 임시로 맞는 테마를 보여주고,
 * 마운트 후 저장된 값(없으면 시스템 설정)을 읽어 명시적으로 고정한다.
 */
export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setThemeState] = useState<Theme>("light");

  useEffect(() => {
    const stored = window.localStorage.getItem(STORAGE_KEY) as Theme | null;
    const initial: Theme =
      stored ?? (window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light");
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setThemeState(initial);
    document.documentElement.dataset.theme = initial;
  }, []);

  function setTheme(next: Theme) {
    setThemeState(next);
    document.documentElement.dataset.theme = next;
    window.localStorage.setItem(STORAGE_KEY, next);
  }

  return <ThemeContext.Provider value={{ theme, setTheme }}>{children}</ThemeContext.Provider>;
}

export function useTheme(): ThemeState {
  const ctx = useContext(ThemeContext);
  if (!ctx) throw new Error("useTheme은 ThemeProvider 안에서만 쓸 수 있습니다.");
  return ctx;
}
