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
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/45 px-4"
      onClick={onClose}
    >
      <div
        className="w-full max-w-[400px] rounded-xl bg-white p-7 pb-6 shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <h3 className="mb-1.5 text-[17px] font-bold text-gray-900">
          거래하려면 회원가입이 필요해요
        </h3>
        <p className="mb-4.5 text-[13px] leading-relaxed text-gray-500">
          가입하면 <b className="text-gray-900">모의 투자금 5,000만원</b>을 바로 드립니다.
          <br />
          실제 돈이 오가지 않으니 부담 없이 시작하세요.
        </p>
        <button
          className="mb-2 w-full rounded-md bg-gray-900 px-4 py-2.5 text-[13px] font-medium text-white hover:bg-black"
          onClick={() => router.push(`/signup?next=${next}`)}
        >
          회원가입하고 5,000만원 받기
        </button>
        <button
          className="mb-3.5 w-full rounded-md border border-gray-300 bg-white px-4 py-2.5 text-[13px] font-medium text-gray-900 hover:bg-gray-50"
          onClick={() => router.push(`/login?next=${next}`)}
        >
          이미 계정이 있어요 · 로그인
        </button>
        <div className="text-center">
          <button
            className="text-[13px] text-gray-500 underline underline-offset-2"
            onClick={onClose}
          >
            둘러보기만 할게요
          </button>
        </div>
      </div>
    </div>
  );
}
