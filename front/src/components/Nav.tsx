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
      {/* "InvestUP" 텍스트를 로고 이미지로 바꿔달라는 요청 — 첨부받은 이미지는
          짙은 남색(라이트 모드 텍스트 색 #0f3868과 같은 톤) 한 가지 색으로만
          되어 있어서, 다크 모드에서 쓰던 색 전환(--headerLogo: #ffffff)을 이미지
          하나로는 그대로 재현할 수 없다. 대신 다크 모드일 때만
          filter: brightness(0) invert(1)을 걸어 같은 PNG를 흰색 실루엣으로
          렌더링한다 — 별도의 다크 모드 로고 파일 없이도 텍스트였을 때와 동일하게
          라이트=남색/다크=흰색으로 보인다. */}
      <Link href="/" className="mr-1.5 inline-flex items-center whitespace-nowrap" aria-label="InvestUP">
        {/* eslint-disable-next-line @next/next/no-img-element -- 헤더 로고 이미지 하나뿐이라 next/image 최적화 이점이 없다 */}
        <img
          src="/investup-logo-symbol-light.png"
          alt="InvestUP"
          style={{
            display: "block",
            // 로고 이미지 크기를 키워달라는 요청 — 22 → 30.
            height: 30,
            width: "auto",
            filter: theme === "dark" ? "brightness(0) invert(1)" : "none",
          }}
        />
      </Link>

      <PillTabs
        options={MENU}
        value={activeNavValue(pathname)}
        onChange={(v) => router.push(v)}
        trackClassName="w-[340px] gap-0.5 p-[3px]"
        pillRadius="8px"
        squashAnimation="liquid"
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
            {/* 회원가입 버튼(비로그인 상태의 PillTabs 활성 필박스)과 같은 디자인 —
                rounded-lg, var(--accent) 배경, 흰색 굵은 13px 글자로 맞췄다. */}
            <button
              onClick={logout}
              className="cursor-pointer whitespace-nowrap rounded-lg px-3 py-[7px] text-[13px] font-bold text-white"
              style={{ background: "var(--accent)" }}
            >
              로그아웃
            </button>
          </>
        ) : (
          // 상단 네비(메인/랭킹/가이드/마이페이지)와 같은 슬라이딩 필박스 탭으로 통일했다.
          // 로그인 화면이 아니면 기본으로 회원가입 쪽에 필박스를 둔다 — 평소엔 회원가입이
          // 강조돼 있다가, 로그인 화면으로 이동하면 필박스가 로그인 쪽으로 슬라이딩한다.
          <PillTabs
            options={[
              { value: "login", label: "로그인" },
              { value: "signup", label: "회원가입" },
            ]}
            value={pathname === "/login" ? "login" : "signup"}
            onChange={(v) => router.push(`/${v}?next=${encodeURIComponent(pathname)}`)}
            trackClassName="w-[176px] gap-0.5 rounded-lg p-[3px]"
            pillRadius="8px"
            buttonClassName="rounded-lg px-1 py-[7px] text-[13px] font-bold"
            activeTextClassName="text-white"
            inactiveTextStyle={{ color: "var(--headerNavInactive)" }}
          />
        )}
      </div>
    </header>
  );
}
