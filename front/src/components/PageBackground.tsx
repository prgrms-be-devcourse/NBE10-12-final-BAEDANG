"use client";

import { usePathname } from "next/navigation";
import type { ReactNode } from "react";

/**
 * 로그인/회원가입 화면은 세로 그라데이션(`--loginBg`)이 헤더까지 이어져야 한다
 * (design_handoff README: "헤더 배경도 transparent로 두어 경계 없이 이어짐").
 * `<main>`만 배경을 칠하면 투명 헤더 뒤로는 body의 `--bg`가 비쳐 경계가 생기므로,
 * 헤더+본문을 함께 감싸는 이 컨테이너가 라우트에 따라 배경을 통째로 바꾼다.
 */
export function PageBackground({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const gradient = pathname === "/login" || pathname === "/signup";

  return (
    <div
      className="min-h-screen transition-[background] duration-200"
      style={{ background: gradient ? "var(--loginBg)" : "var(--bg)" }}
    >
      {children}
    </div>
  );
}
