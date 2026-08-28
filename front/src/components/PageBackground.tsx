"use client";

import { usePathname } from "next/navigation";
import type { ReactNode } from "react";

/**
 * 로그인/회원가입 화면은 세로 그라데이션이 헤더까지 이어져야 한다
 * (design_handoff README: "헤더 배경도 transparent로 두어 경계 없이 이어짐").
 * `<main>`만 배경을 칠하면 투명 헤더 뒤로는 body의 `--bg`가 비쳐 경계가 생기므로,
 * 헤더+본문을 함께 감싸는 이 컨테이너가 라우트에 따라 배경을 통째로 바꾼다.
 *
 * 로그인(`--loginBg`)과 회원가입(`--registerBg`)은 서로 다른 그라데이션을 쓴다 —
 * 예전에는 registerBg가 loginBg를 그대로 가리키는 별칭이라 두 화면이 항상
 * 같은 배경이었는데, 회원가입 화면만 따로 톤을 바꿔달라는 요청이 있어 분리했다.
 */
export function PageBackground({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const background =
    pathname === "/login" ? "var(--loginBg)" : pathname === "/signup" ? "var(--registerBg)" : "var(--bg)";

  return (
    <div className="min-h-screen transition-[background] duration-200" style={{ background }}>
      {children}
    </div>
  );
}
