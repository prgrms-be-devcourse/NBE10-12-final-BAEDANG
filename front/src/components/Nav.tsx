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
          라이트=남색/다크=흰색으로 보인다.
          로고를 클릭하면 서비스 소개 화면("/intro")으로 이동하게 해달라는
          요청 — 홈("/")은 첫 방문자만 자동으로 리다이렉트되므로(proxy.ts),
          로고 클릭은 그 소개 화면을 언제든 다시 보고 싶을 때 쓰는 통로다. */}
      <Link href="/intro" className="mr-1.5 inline-flex items-center whitespace-nowrap" aria-label="InvestUP">
        {/* eslint-disable-next-line @next/next/no-img-element -- 헤더 로고 이미지 하나뿐이라 next/image 최적화 이점이 없다 */}
        <img
          src="/investup-logo-symbol-light.png"
          alt="InvestUP"
          style={{
            display: "block",
            // 로고 이미지 크기를 키워달라는 요청을 두 차례 거쳐
            // 22 → 30 → 40으로 키웠다.
            height: 40,
            width: "auto",
            filter: theme === "dark" ? "brightness(0) invert(1)" : "none",
            // 로고 위치를 좀 더 아래로 옮겨달라는 요청(5px)에 이어,
            // "메인" 탭 문구와 나란히 놓이도록 다시 살짝만 위로 올려달라는
            // 요청으로 5px → 2px로 줄였다. 헤더의 다른 요소들(PillTabs
            // 등)과의 정렬(items-center)에는 영향을 주지 않도록 레이아웃에
            // 관여하는 margin 대신 순수 시각적 이동인 transform을 쓴다.
            transform: "translateY(2px)",
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
        // "메인/랭킹/가이드/마이페이지" 글자 크기를 키워달라는 요청 —
        // 12.5px → 14px.
        buttonClassName="rounded-lg px-1 py-[7px] text-[14px] font-bold"
        activeTextClassName="text-white"
        inactiveTextClassName="hover:brightness-95"
        inactiveTextStyle={{ color: "var(--headerNavInactive)" }}
      />

      {/* 라이트/다크 모드 버튼과 닉네임 문구, 닉네임과 로그아웃 버튼 사이의
          간격을 조금씩 띄워달라는 요청 — 이 셋이 같은 flex 컨테이너의
          gap 하나를 공유해서, gap 값을 2.5(10px) → 4(16px)로 올리면
          두 간격 모두 똑같이 넓어진다. */}
      <div className="ml-auto flex items-center gap-4">
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
          // "라이트/다크" 글자 크기를 키워달라는 요청 — 12px → 13.5px.
          buttonClassName="rounded-full px-0 py-1.5 text-[13.5px] font-bold"
          inactiveTextStyle={{ color: theme === "dark" ? "oklch(75% 0.02 258)" : "rgba(15,56,104,.75)" }}
          activeTextClassName="text-white"
        />

        {isLoggedIn && user ? (
          <>
            {/* 닉네임 글자 크기를 키워달라는 요청 — 13px → 14.5px. */}
            <span className="text-[14.5px]" style={{ color: "var(--mut)" }}>
              {user.nickname}님
            </span>
            {/* 회원가입 버튼(비로그인 상태의 PillTabs 활성 필박스)과 같은 디자인 —
                rounded-lg, var(--accent) 배경, 흰색 굵은 글자로 맞췄다. 글자 크기를
                키워달라는 요청으로 13px → 14.5px(닉네임과 같은 크기)로 올렸다. */}
            <button
              onClick={logout}
              className="cursor-pointer whitespace-nowrap rounded-lg px-3 py-[7px] text-[14.5px] font-bold text-white"
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
            // "로그인" 글자 크기를 키워달라는 요청 — 같은 필박스를 쓰는
            // "회원가입"도 함께 13px → 14.5px로 커진다.
            buttonClassName="rounded-lg px-1 py-[7px] text-[14.5px] font-bold"
            activeTextClassName="text-white"
            inactiveTextStyle={{ color: "var(--headerNavInactive)" }}
          />
        )}
      </div>
    </header>
  );
}
