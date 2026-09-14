"use client";

import Link from "next/link";
import { InvestupIntro } from "./InvestupIntro";

/**
 * `/intro` 페이지 본문 — 방문할 때마다(재방문 포함) 무조건 보여주는 서비스 소개
 * 화면이다(proxy.ts가 "/"를 여기로 무조건 돌려보낸다). 실제 메인 화면은 "/main"으로
 * 옮겨졌다 — 시작하기 버튼(`InvestupIntro`의 `ctaHref`)과 아래 SKIP 버튼 모두 거기로
 * 보낸다.
 *
 * <p>SKIP 버튼은 `InvestupIntro`(팀원이 전달한 디자인 패키지 — 로직·수치·마크업을
 * 임의로 바꾸지 않기로 한 컴포넌트) 내부를 건드리지 않고, 그 위에 고정 위치로
 * 얹은 오버레이다.
 */
export function IntroScreen() {
  return (
    <>
      <InvestupIntro ctaHref="/main" />
      <Link
        href="/main"
        className="iv-skip-btn"
        style={{
          position: "fixed",
          right: "clamp(16px, 4vw, 40px)",
          bottom: "clamp(16px, 4vw, 40px)",
          zIndex: 100,
          display: "inline-flex",
          alignItems: "center",
          gap: 8,
          padding: "10px 22px",
          borderRadius: 999,
          background: "rgba(255,255,255,.92)",
          color: "#0f3868",
          fontSize: 13,
          fontWeight: 800,
          letterSpacing: ".08em",
          textDecoration: "none",
          transition: "background-color 150ms ease-out",
        }}
        onMouseEnter={(e) => (e.currentTarget.style.background = "#ffffff")}
        onMouseLeave={(e) => (e.currentTarget.style.background = "rgba(255,255,255,.92)")}
        aria-label="소개 건너뛰고 메인 화면으로 이동"
      >
        SKIP
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="M9 6l6 6-6 6" />
        </svg>
      </Link>
    </>
  );
}
