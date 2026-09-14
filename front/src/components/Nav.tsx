"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
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
  // 반응형 웹 적용 — 데스크톱(md 이상)에서는 로고 옆에 메뉴·테마 토글·로그인
  // 영역이 한 줄로 다 들어가지만, 그 폭(메뉴 340px + 테마 132px + 로그인/
  // 가입 176px 등, 고정폭들의 합)이 모바일 화면(375px 기준)보다 훨씬 넓어서
  // 그대로 두면 헤더가 옆으로 잘려 나가고 가로 스크롤이 생겼다(제보). md
  // 미만에서는 로고와 햄버거 버튼만 한 줄에 남기고, 나머지(메뉴·테마·로그인)는
  // 버튼을 눌러야 열리는 아래쪽 패널로 옮긴다.
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);

  // 페이지를 이동하면(메뉴 탭 클릭·로그인/회원가입 이동 등) 모바일 메뉴를
  // 자동으로 닫는다 — 안 닫으면 다음 화면 위에 이전 메뉴가 계속 떠 있게 된다.
  // pathname은 React 바깥(라우터)에서 오는 외부 신호라 effect로 동기화하는 게
  // 맞는 자리다(AuthProvider의 localStorage 하이드레이션과 같은 이유) —
  // 이미 닫혀 있으면 다시 안 불러서 불필요한 리렌더를 피한다.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setMobileMenuOpen((prev) => (prev ? false : prev));
  }, [pathname]);

  // 로그인/회원가입/비밀번호 찾기/재설정 화면은 페이지 그라데이션이 헤더까지
  // 이어져야 해서 배경을 투명하게 둔다(PageBackground.tsx와 같은 기준이어야
  // 한다 — 비밀번호 찾기가 빠져 있어서 그 화면만 헤더 아래에서 그라데이션이
  // 끊겨 보이는 오류가 있었다. 재설정 화면도 같은 흐름이라 함께 넣는다).
  const transparentHeader =
    pathname === "/login" || pathname === "/signup" || pathname === "/forgot-password" || pathname === "/reset-password";

  return (
    // mb-6(헤더 아래 여백)을 헤더 자체가 아니라 이 바깥 wrapper로 옮겼다 —
    // 모바일 메뉴 패널이 열리면 헤더 바로 아래에 패널이 붙고, 그 패널 다음에
    // 본문과의 간격이 생겨야 자연스럽다(헤더에 mb-6를 그대로 두면 헤더와
    // 패널 사이에 불필요한 틈이 생긴다).
    <div className="relative z-[2] mb-6">
    <header
      className="flex w-full items-center gap-1.5 px-4 py-3 md:px-8"
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

      {/* md 이상(데스크톱)에서만 한 줄로 보이는 영역 — 메뉴 탭 + 오른쪽
          그룹(테마 토글·로그인/닉네임). 아래 모바일 패널과 내용은 같지만
          트랙 폭·배치가 달라서 별개 컴포넌트 인스턴스로 둔다(같은 상태
          (activeNavValue·theme 등)를 그대로 참조해 항상 서로 일치한다). */}
      <div className="hidden md:flex md:flex-1 md:items-center md:gap-1.5">
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
            // 라이트/다크 버튼 뒤에 있는 둥근 사각형(트랙)의 배경 불투명도를
            // 낮춰 반투명하게 보이게 해달라는 요청 — 기존 alpha 값(다크
            // .03·라이트 .06)을 절반으로 낮췄다. 테두리도 같은 비율로
            // 낮춰 트랙 전체가 함께 옅어지도록 했다.
            trackStyle={{
              background: theme === "dark" ? "rgba(255,255,255,.015)" : "rgba(15,56,104,.03)",
              borderColor: theme === "dark" ? "rgba(255,255,255,.03)" : "rgba(15,56,104,.06)",
            }}
            // 라이트 모드 필박스(="라이트" 버튼) 배경을 여러 파란 계열로
            // 시도해본 끝에, 메인 화면과 가장 잘 어우러지는 색을 골라달라는
            // 요청 — 사이트 전체(로그인/회원가입 필박스, 상단 메인/랭킹/가이드
            // 탭, "모의 투자금 받고 시작하기" 등 CTA 버튼)에서 이미 일관되게
            // 쓰고 있는 브랜드 색 var(--accent)(진한 남색)를 그대로 채택했다 —
            // 새 색을 만드는 대신 이미 검증된 사이트 대표색을 재사용해 통일감을
            // 유지한다. PillTabs의 기본 pillColor 값도 var(--accent)라
            // 사실상 다른 탭들과 동일한 방식으로 돌아온 것이다.
            pillColor={theme === "dark" ? "rgba(42,46,51,.5)" : "var(--accent)"}
            // "라이트/다크" 글자 크기를 키워달라는 요청 — 12px → 13.5px.
            buttonClassName="rounded-full px-0 py-1.5 text-[13.5px] font-bold"
            inactiveTextStyle={{ color: theme === "dark" ? "oklch(75% 0.02 258)" : "rgba(15,56,104,.75)" }}
            // 필박스 배경이 다시 진한 남색(var(--accent))이 되면서, 사이트의
            // 다른 필박스(메인 네비, 로그인/회원가입)와 마찬가지로 흰 글자가
            // 잘 어울려 activeTextClassName="text-white" 기본값을 그대로 둔다
            // (별도 activeTextStyle 오버라이드 없음).
          />

          {isLoggedIn && user ? (
            <>
              {/* 닉네임 글자 크기를 키워달라는 요청 — 13px → 14.5px. */}
              <span className="text-[14.5px]" style={{ color: "var(--mut)" }}>
                {user.nickname}님
              </span>
              {/* 회원가입 버튼(비로그인 상태의 PillTabs 활성 필박스)과 같은 디자인 —
                  rounded-lg, var(--accent) 배경, 흰색 굵은 글자로 맞췄다. 글자 크기를
                  키워달라는 요청으로 13px → 14.5px(닉네임과 같은 크기)로 올렸다.
                  한때 다크 모드 accent(#5fa0d6)가 가볍다는 이유로 이 버튼만
                  로컬로 #114f8c를 썼는데, 이후 dark accent 자체가 #114f8c로
                  통일되면서(globals.css) var(--accent) 하나만 써도 같은 색이라
                  로컬 오버라이드를 정리했다. */}
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
      </div>

      {/* md 미만(모바일)에서만 보이는 햄버거 버튼 — 누르면 아래 패널이 열린다.
          아이콘은 이 앱의 다른 스트로크 아이콘(검색 돋보기 등)과 같은 스타일
          (viewBox 24, strokeWidth 2, round cap)로 맞췄다. */}
      <button
        type="button"
        className="ml-auto cursor-pointer rounded-lg p-2 md:hidden"
        onClick={() => setMobileMenuOpen((v) => !v)}
        aria-label={mobileMenuOpen ? "메뉴 닫기" : "메뉴 열기"}
        aria-expanded={mobileMenuOpen}
      >
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
          {mobileMenuOpen ? (
            <>
              <path d="M6 6l12 12" stroke="var(--ink)" strokeWidth="2" strokeLinecap="round" />
              <path d="M18 6l-12 12" stroke="var(--ink)" strokeWidth="2" strokeLinecap="round" />
            </>
          ) : (
            <>
              <path d="M4 7h16" stroke="var(--ink)" strokeWidth="2" strokeLinecap="round" />
              <path d="M4 12h16" stroke="var(--ink)" strokeWidth="2" strokeLinecap="round" />
              <path d="M4 17h16" stroke="var(--ink)" strokeWidth="2" strokeLinecap="round" />
            </>
          )}
        </svg>
      </button>
    </header>

    {/* 모바일 전용 드롭다운 패널 — 데스크톱의 메뉴 탭·테마 토글·로그인 영역과
        내용은 같지만, 트랙을 w-full로 세로 스택해 좁은 화면에 맞춘다. 화면을
        덮는 오버레이 대신 헤더 바로 아래에서 문서 흐름대로 펼쳐지는 방식이라
        (position: fixed가 아니다) 뒤로가기·바깥 클릭 처리 없이도 항상 자연스럽게
        동작한다. */}
    {mobileMenuOpen && (
      <div
        className="border-t px-4 py-4 md:hidden"
        style={{ background: transparentHeader ? "var(--card)" : "var(--headerBg)", borderColor: "var(--line)" }}
      >
        <PillTabs
          options={MENU}
          value={activeNavValue(pathname)}
          onChange={(v) => router.push(v)}
          trackClassName="w-full gap-0.5 p-[3px]"
          pillRadius="8px"
          squashAnimation="liquid"
          buttonClassName="rounded-lg px-1 py-[9px] text-[14px] font-bold"
          activeTextClassName="text-white"
          inactiveTextClassName="hover:brightness-95"
          inactiveTextStyle={{ color: "var(--headerNavInactive)" }}
        />

        <div className="mt-4 flex items-center justify-between">
          <span className="text-[13px] font-bold" style={{ color: "var(--mut2)" }}>
            화면 테마
          </span>
          <PillTabs
            options={[
              { value: "light", label: "라이트" },
              { value: "dark", label: "다크" },
            ]}
            value={theme}
            onChange={(v) => setTheme(v as "light" | "dark")}
            trackClassName="w-[132px] box-border gap-0.5 rounded-full border p-[3px] backdrop-blur-[4px]"
            trackStyle={{
              background: theme === "dark" ? "rgba(255,255,255,.015)" : "rgba(15,56,104,.03)",
              borderColor: theme === "dark" ? "rgba(255,255,255,.03)" : "rgba(15,56,104,.06)",
            }}
            pillColor={theme === "dark" ? "rgba(42,46,51,.5)" : "var(--accent)"}
            buttonClassName="rounded-full px-0 py-1.5 text-[13.5px] font-bold"
            inactiveTextStyle={{ color: theme === "dark" ? "oklch(75% 0.02 258)" : "rgba(15,56,104,.75)" }}
          />
        </div>

        <div className="mt-4 border-t pt-4" style={{ borderColor: "var(--line)" }}>
          {isLoggedIn && user ? (
            <div className="flex items-center justify-between">
              <span className="text-[14.5px]" style={{ color: "var(--mut)" }}>
                {user.nickname}님
              </span>
              <button
                onClick={logout}
                className="cursor-pointer whitespace-nowrap rounded-lg px-3 py-[7px] text-[14.5px] font-bold text-white"
                style={{ background: "var(--accent)" }}
              >
                로그아웃
              </button>
            </div>
          ) : (
            <PillTabs
              options={[
                { value: "login", label: "로그인" },
                { value: "signup", label: "회원가입" },
              ]}
              value={pathname === "/login" ? "login" : "signup"}
              onChange={(v) => router.push(`/${v}?next=${encodeURIComponent(pathname)}`)}
              trackClassName="w-full gap-0.5 rounded-lg p-[3px]"
              pillRadius="8px"
              buttonClassName="rounded-lg px-1 py-[9px] text-[14.5px] font-bold"
              activeTextClassName="text-white"
              inactiveTextStyle={{ color: "var(--headerNavInactive)" }}
            />
          )}
        </div>
      </div>
    )}
    </div>
  );
}
