"use client";

import Link from "next/link";
import { InvestupIntro } from "./InvestupIntro";
import { SwapText } from "./SwapText";

/**
 * `/intro` 페이지 본문 — 방문할 때마다(재방문 포함) 무조건 보여주는 서비스 소개
 * 화면이다(proxy.ts가 "/"를 여기로 무조건 돌려보낸다). 실제 메인 화면은 "/main"으로
 * 옮겨졌다 — 시작하기 버튼(`InvestupIntro`의 `ctaHref`)과 아래 SKIP 버튼 모두 거기로
 * 보낸다.
 *
 * <p>SKIP 버튼은 `InvestupIntro`(팀원이 전달한 디자인 패키지 — 로직·수치·마크업을
 * 임의로 바꾸지 않기로 한 컴포넌트) 내부를 건드리지 않고, 그 위에 고정 위치로
 * 얹은 오버레이다. 스타일·호버 애니메이션은 하단 "시작하기" CTA 버튼과 똑같이
 * 맞춰달라는 요청 — 그 버튼이 쓰는 iv-cta-btn(반투명 흰 배경 알약, 호버 시 더
 * 밝아짐)과 iv-hover-swap + SwapText(호버 시 글자가 아래→위로 스치듯 바뀜)를
 * 그대로 재사용했다. 둘 다 investup-intro.css에 "특정 버튼에 종속되지 않는"
 * 범용 클래스로 이미 있어 그대로 가져다 쓸 수 있었다(메인 화면 CTA 버튼도 같은
 * 방식으로 재사용 중). "시작하기" 버튼에만 있는 스크롤 등장 애니메이션
 * (opacity/blur/translateY, ctaBtnRevealed)은 옮기지 않았다 — SKIP은 스크롤과
 * 무관하게 화면에 고정돼 처음부터 계속 보여야 하는 버튼이라 적용 대상이 아니다.
 * 크기(minHeight/padding/fontSize)만은 그대로 가져오지 않았다 — "시작하기"는
 * 화면의 주 CTA라 크게, SKIP은 보조 동작이라 작게 요청받아 셋 다 낮췄다.
 */
export function IntroScreen() {
  return (
    <>
      <InvestupIntro ctaHref="/main" />
      <Link
        href="/main"
        className="iv-cta-btn iv-hover-swap"
        style={{
          position: "fixed",
          right: "clamp(16px, 4vw, 40px)",
          bottom: "clamp(16px, 4vw, 40px)",
          zIndex: 100,
          display: "inline-flex",
          alignItems: "center",
          minHeight: 36,
          padding: "0 20px",
          borderRadius: 999,
          background: "rgba(255,255,255,.06)",
          color: "#ffffff",
          fontSize: 13,
          fontWeight: 700,
          letterSpacing: "-.01em",
          textDecoration: "none",
          boxShadow: "0 2px 8px rgba(15,23,32,.08)",
        }}
        aria-label="소개 건너뛰고 메인 화면으로 이동"
      >
        <SwapText>SKIP</SwapText>
      </Link>
    </>
  );
}
