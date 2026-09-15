"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { InvestupIntro } from "./InvestupIntro";

/**
 * `/intro` 페이지 본문 — 방문할 때마다(재방문 포함) 무조건 보여주는 서비스 소개
 * 화면이다(proxy.ts가 "/"를 여기로 무조건 돌려보낸다). 실제 메인 화면은 "/main"으로
 * 옮겨졌다 — 시작하기 버튼(`InvestupIntro`의 `ctaHref`)이 거기로 보낸다.
 *
 * <p>우측 하단 SKIP 버튼은 화면 몰입을 방해한다는 피드백으로 없앴다. 대신 Enter
 * 키로 건너뛸 수 있게 하고, 그 안내 문구("Enter 키를 누르면 SKIP이
 * 가능합니다.")는 InvestupIntro의 화살표 위에 함께 넣었다(화살표 자체의 클릭
 * 이동 기능도 이번에 제거했다 — InvestupIntro 쪽 변경 참고). 실제 키 입력
 * 처리는 여기(페이지 레벨)에서 한다 — 어느 스크롤 위치에 있든 Enter로 건너뛸
 * 수 있어야 안내 문구와 실제 동작이 어긋나지 않는다.
 */
export function IntroScreen() {
  const router = useRouter();

  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === "Enter") {
        router.push("/main");
      }
    }
    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [router]);

  return <InvestupIntro ctaHref="/main" />;
}
