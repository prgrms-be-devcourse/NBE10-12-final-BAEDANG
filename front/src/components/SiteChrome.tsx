"use client";

import { usePathname } from "next/navigation";
import type { ReactNode } from "react";
import { Nav } from "./Nav";

/**
 * 공통 헤더(`Nav`)와 본문 폭 제한을 모든 화면에 씌우는 래퍼 — 기존에 `layout.tsx`에
 * 그대로 박혀 있던 마크업을 그대로 옮긴 것뿐이라, 기존 화면(메인·랭킹·가이드·
 * 마이페이지·로그인·회원가입 등)은 렌더링 결과가 완전히 동일하다.
 *
 * 유일한 예외는 홈페이지 첫 방문 시 등장하는 서비스 소개 화면("/intro")이다 —
 * 그 화면은 사이트 헤더도 없이 전체 화면을 그대로 써야 하는 디자인이라, 이 경로에서만
 * `Nav`와 폭 제한 없이 children을 그대로 내보낸다.
 */
export function SiteChrome({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  if (pathname === "/intro") {
    return <>{children}</>;
  }
  return (
    <>
      <Nav />
      <main className="mx-auto max-w-[1180px] px-6 pb-16">{children}</main>
    </>
  );
}
