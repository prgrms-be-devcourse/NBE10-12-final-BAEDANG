"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useAuth } from "./AuthProvider";
import { useTheme } from "./ThemeProvider";
import { PillTabs } from "./PillTabs";

const MENU = [
  { value: "/", label: "메인" },
  { value: "/rankings", label: "랭킹" },
  { value: "/guide", label: "가이드" },
  { value: "/my", label: "마이페이지" },
];

function activeNavValue(pathname: string): string {
  if (pathname === "/") return "/";
  if (pathname.startsWith("/rankings") || pathname.startsWith("/stocks")) return "/rankings";
  if (pathname.startsWith("/guide")) return "/guide";
  if (pathname.startsWith("/my")) return "/my";
  return "/";
}

export function Nav() {
  const pathname = usePathname();
  const router = useRouter();
  const { isLoggedIn, user, logout } = useAuth();
  const { theme, setTheme } = useTheme();

  // 로그인/회원가입 화면은 페이지 그라데이션이 헤더까지 이어져야 해서 배경을 투명하게 둔다.
  const transparentHeader = pathname === "/login" || pathname === "/signup";

  return (
    <header
      className="relative z-[2] mb-6 flex w-full items-center gap-1.5 px-8 py-3"
      style={{ background: transparentHeader ? "transparent" : "var(--headerBg)" }}
    >
      <Link
        href="/"
        className="mr-1.5 whitespace-nowrap text-[16px] font-extrabold"
        style={{ color: "var(--headerLogo)" }}
      >
        모의주식
      </Link>

      <PillTabs
        options={MENU}
        value={activeNavValue(pathname)}
        onChange={(v) => router.push(v)}
        trackClassName="w-[340px] gap-0.5 p-[3px]"
        pillRadius="8px"
        buttonClassName="rounded-lg px-1 py-[7px] text-[12.5px] font-bold"
        activeTextClassName="text-white"
        inactiveTextClassName="hover:brightness-95"
        inactiveTextStyle={{ color: "var(--headerNavInactive)" }}
      />

      <div className="ml-auto flex items-center gap-2.5">
        <PillTabs
          options={[
            { value: "light", label: "라이트" },
            { value: "dark", label: "다크" },
          ]}
          value={theme}
          onChange={(v) => setTheme(v as "light" | "dark")}
          trackClassName="w-[132px] box-border gap-0.5 rounded-full border p-[3px] backdrop-blur-[4px]"
          trackStyle={{
            background: theme === "dark" ? "rgba(255,255,255,.03)" : "rgba(15,56,104,.06)",
            borderColor: theme === "dark" ? "rgba(255,255,255,.06)" : "rgba(15,56,104,.12)",
          }}
          pillColor={theme === "dark" ? "rgba(42,46,51,.5)" : "rgba(15,56,104,.68)"}
          buttonClassName="rounded-full px-0 py-1.5 text-[12px] font-bold"
          inactiveTextStyle={{ color: theme === "dark" ? "oklch(75% 0.02 258)" : "rgba(15,56,104,.75)" }}
          activeTextClassName="text-white"
        />

        {isLoggedIn && user ? (
          <>
            <span className="text-[13px]" style={{ color: "var(--mut)" }}>
              {user.nickname}님
            </span>
            <button
              onClick={logout}
              className="whitespace-nowrap text-[12.5px] underline underline-offset-2"
              style={{ color: "var(--mut2)" }}
            >
              로그아웃
            </button>
          </>
        ) : (
          <>
            <Link
              href={`/login?next=${encodeURIComponent(pathname)}`}
              className="whitespace-nowrap px-3.5 py-[7px] text-[13px] font-semibold"
              style={{ color: "var(--headerLoginText)" }}
            >
              로그인
            </Link>
            <Link
              href={`/signup?next=${encodeURIComponent(pathname)}`}
              className="whitespace-nowrap rounded-full px-4 py-[7px] text-[13px] font-bold text-white"
              style={{ background: "var(--accent)" }}
            >
              회원가입
            </Link>
          </>
        )}
      </div>
    </header>
  );
}
