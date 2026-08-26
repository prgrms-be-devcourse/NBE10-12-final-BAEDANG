"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useAuth } from "./AuthProvider";

const MENU = [
  { href: "/", label: "메인" },
  { href: "/rankings", label: "주식 종목 랭킹" },
  { href: "/guide", label: "이용 가이드" },
  { href: "/my", label: "마이페이지" },
];

export function Nav() {
  const pathname = usePathname();
  const { isLoggedIn, user, logout } = useAuth();

  const isActive = (href: string) =>
    href === "/" ? pathname === "/" : pathname.startsWith(href);

  return (
    <header className="flex items-center gap-6 border-b border-gray-200 px-6 py-3.5">
      <Link href="/" className="text-[15px] font-bold text-gray-900">
        모의주식 트레이딩
      </Link>
      <nav className="flex gap-5 text-[13.5px]">
        {MENU.map((item) => (
          <Link
            key={item.href}
            href={item.href}
            className={
              isActive(item.href)
                ? "font-semibold text-gray-900"
                : "text-gray-400 hover:text-gray-900"
            }
          >
            {item.label}
          </Link>
        ))}
      </nav>
      <div className="ml-auto flex items-center gap-3">
        {isLoggedIn && user ? (
          <>
            <span className="text-[13px] text-gray-500">{user.nickname}님</span>
            <button
              onClick={logout}
              className="text-[12.5px] text-gray-400 underline underline-offset-2 hover:text-gray-900"
            >
              로그아웃
            </button>
          </>
        ) : (
          <>
            <Link
              href={`/login?next=${encodeURIComponent(pathname)}`}
              className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-[13px] text-gray-900 hover:bg-gray-50"
            >
              로그인
            </Link>
            <Link
              href={`/signup?next=${encodeURIComponent(pathname)}`}
              className="rounded-md bg-gray-900 px-3 py-1.5 text-[13px] text-white hover:bg-black"
            >
              회원가입
            </Link>
          </>
        )}
      </div>
    </header>
  );
}
