"use client";

import { useRouter, usePathname } from "next/navigation";

type Props = {
  open: boolean;
  onClose: () => void;
};

/**
 * 비로그인 상태에서 거래를 시도했을 때 뜨는 회원가입 유도 모달.
 * 버튼을 누르면 실제 `/signup`, `/login` 페이지로 이동합니다 — 로그인/가입 성공 후
 * `next` 쿼리로 원래 있던 화면(예: 종목 상세)으로 돌아옵니다.
 */
export function SignupModal({ open, onClose }: Props) {
  const router = useRouter();
  const pathname = usePathname();

  if (!open) return null;

  const next = encodeURIComponent(pathname);

  return (
    <div
      className="fixed inset-0 z-[150] flex items-center justify-center px-4"
      style={{ background: "var(--modalOverlay)", animation: "modalFade .28s" }}
      onClick={onClose}
    >
      <div
        className="w-full max-w-[420px] rounded-[24px] px-7.5 pt-8 pb-6.5 text-center"
        style={{ background: "var(--card)", animation: "modalPop .4s cubic-bezier(.2,.9,.3,1.1)" }}
        onClick={(e) => e.stopPropagation()}
      >
        <h3 className="mb-1.5 text-[18px] font-bold" style={{ color: "var(--ink)" }}>
          거래하려면 회원가입이 필요해요
        </h3>
        <p className="mb-4.5 text-[13.5px] leading-relaxed" style={{ color: "var(--mut)" }}>
          가입하면 <b style={{ color: "var(--ink)" }}>모의 투자금 5,000만원</b>을 바로 드려요.
          <br />
          실제 돈이 오가지 않으니 부담 없이 시작하세요.
        </p>
        <button
          className="mb-2 w-full rounded-xl px-4 py-3 text-[13.5px] font-bold text-white transition-[background] duration-150"
          style={{ background: "var(--accent)" }}
          onMouseEnter={(e) => (e.currentTarget.style.background = "var(--buyHover)")}
          onMouseLeave={(e) => (e.currentTarget.style.background = "var(--accent)")}
          onClick={() => router.push(`/signup?next=${next}`)}
        >
          회원가입하고 5,000만원 받기
        </button>
        <button
          className="mb-3.5 w-full rounded-xl px-4 py-3 text-[13.5px] font-bold transition-[background] duration-150"
          style={{ background: "var(--fill)", color: "var(--ink)" }}
          onMouseEnter={(e) => (e.currentTarget.style.background = "var(--line)")}
          onMouseLeave={(e) => (e.currentTarget.style.background = "var(--fill)")}
          onClick={() => router.push(`/login?next=${next}`)}
        >
          이미 계정이 있어요 · 로그인
        </button>
        <button
          className="text-[13px] underline underline-offset-2"
          style={{ color: "var(--mut)" }}
          onClick={onClose}
        >
          둘러보기만 할게요
        </button>
      </div>
    </div>
  );
}
