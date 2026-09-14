"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";
import { ApiError, requestPasswordReset } from "@/lib/api";

/**
 * 비밀번호 찾기 화면. `POST /api/auth/password/forgot`(이메일만 받음)을 호출한다.
 *
 * <p>성공(200) 여부만으로 분기하고, 실패 사유를 세분화해서 보여주지 않는다 —
 * 가입 여부를 노출하지 않기 위해 백엔드가 항상 200을 주는 설계라서(계정 열거
 * 공격 방지), 화면도 굳이 "그런 이메일 없어요" 같은 분기를 두지 않는다. 메일
 * 속 링크는 `reset-password` 화면으로 이어진다.
 */
export default function ForgotPasswordPage() {
  const [email, setEmail] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [sent, setSent] = useState(false);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await requestPasswordReset(email);
      setSent(true);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "요청을 처리하지 못했어요. 잠시 후 다시 시도해주세요.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="flex min-h-[70vh] items-center justify-center px-6 py-10">
      <div
        className="w-full max-w-[400px] rounded-[24px] px-8 pt-9 pb-7.5 opacity-0"
        style={{ background: "var(--card)", animation: "modalPop .55s cubic-bezier(.2,.9,.3,1.05) .05s forwards" }}
      >
        {sent ? (
          <>
            <h1 className="mb-1 text-center text-[22px] font-extrabold" style={{ color: "var(--ink)" }}>메일함을 확인해주세요</h1>
            <p className="mb-6 text-center text-[13.5px] leading-relaxed" style={{ color: "var(--mut)", whiteSpace: "pre-line" }}>
              {"비밀번호 재설정 안내를 보내드렸어요.\n메일이 안 보이면 스팸함도 확인해주세요."}
            </p>
            <Link
              href="/login"
              className="block w-full rounded-[14px] py-3.5 text-center text-[14px] font-bold text-white transition-[filter] duration-150"
              style={{ background: "var(--accent)" }}
              onMouseEnter={(e) => (e.currentTarget.style.filter = "brightness(.92)")}
              onMouseLeave={(e) => (e.currentTarget.style.filter = "none")}
            >
              로그인으로 돌아가기
            </Link>
          </>
        ) : (
          <>
            <h1 className="mb-1 text-[22px] font-extrabold" style={{ color: "var(--ink)" }}>비밀번호 찾기</h1>
            <p className="mb-5 text-[13.5px]" style={{ color: "var(--mut)" }}>
              가입하신 이메일로 비밀번호 재설정 안내를 보내드려요
            </p>

            <form onSubmit={handleSubmit} className="space-y-3">
              <div>
                <label className="mb-1 block text-[12px]" style={{ color: "var(--mut)" }}>이메일</label>
                <input
                  type="email"
                  required
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  className="w-full rounded-xl px-4 py-3.5 text-[13.5px] outline-none"
                  style={{ background: "var(--fill)", color: "var(--ink)" }}
                  placeholder="you@example.com"
                />
              </div>

              {error && (
                <div className="rounded-xl px-3.5 py-2.5 text-[12.5px]" style={{ background: "var(--warnBg)", color: "var(--warnText)" }}>
                  {error}
                </div>
              )}

              <button
                type="submit"
                disabled={submitting}
                className="w-full cursor-pointer rounded-[14px] py-3.5 text-[14px] font-bold text-white transition-[filter] duration-150 disabled:cursor-not-allowed disabled:opacity-50"
                style={{ background: "var(--accent)" }}
                onMouseEnter={(e) => !submitting && (e.currentTarget.style.filter = "brightness(.92)")}
                onMouseLeave={(e) => (e.currentTarget.style.filter = "none")}
              >
                {submitting ? "보내는 중…" : "재설정 메일 보내기"}
              </button>
            </form>

            <div className="mt-5 text-center text-[12.5px]" style={{ color: "var(--mut)" }}>
              <Link href="/login" className="font-bold" style={{ color: "var(--accentText)" }}>
                로그인으로 돌아가기
              </Link>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
