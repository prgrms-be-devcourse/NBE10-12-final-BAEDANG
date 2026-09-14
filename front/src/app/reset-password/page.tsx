"use client";

import Link from "next/link";
import { Suspense, useState, type FormEvent } from "react";
import { useSearchParams } from "next/navigation";
import { ApiError, confirmPasswordReset } from "@/lib/api";

/**
 * 비밀번호 찾기 메일 속 링크(`/reset-password?token=...`)가 도착하는 화면.
 * `forgot-password`와 짝을 이루는 화면 — 토큰은 URL 쿼리로만 받고 화면에는
 * 절대 다시 보여주지 않는다(주소창·브라우저 기록에는 남지만, 그건 이메일
 * 링크 방식 자체의 한계라 이 화면에서 더 할 수 있는 게 없다).
 */
function ResetPasswordForm() {
  const searchParams = useSearchParams();
  const token = searchParams.get("token");

  const [newPassword, setNewPassword] = useState("");
  const [newPasswordConfirm, setNewPasswordConfirm] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState(false);

  const passwordMismatch = newPasswordConfirm.length > 0 && newPassword !== newPasswordConfirm;

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (!token) return; // 아래에서 링크 자체가 없다는 안내로 분기하므로 폼이 뜰 일이 없다.
    if (newPassword !== newPasswordConfirm) {
      setError("비밀번호가 서로 달라요.");
      return;
    }
    setSubmitting(true);
    try {
      await confirmPasswordReset({ token, newPassword });
      setDone(true);
    } catch (err) {
      // PASSWORD_RESET_TOKEN_INVALID/EXPIRED 모두 서버 메시지를 그대로 보여준다 —
      // 어느 쪽이든 사용자가 할 일은 같다(비밀번호 찾기를 다시 요청).
      setError(err instanceof ApiError ? err.message : "비밀번호 재설정에 실패했어요.");
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
        {!token ? (
          <>
            <h1 className="mb-1 text-[22px] font-extrabold" style={{ color: "var(--ink)" }}>재설정 링크가 올바르지 않아요</h1>
            <p className="mb-6 text-[13.5px] leading-relaxed" style={{ color: "var(--mut)" }}>
              이메일에 있는 링크를 그대로 눌러 들어와주세요. 링크가 오래됐다면 비밀번호 찾기를 다시 요청해주세요.
            </p>
            <Link
              href="/forgot-password"
              className="block w-full rounded-[14px] py-3.5 text-center text-[14px] font-bold text-white transition-[filter] duration-150"
              style={{ background: "var(--accent)" }}
              onMouseEnter={(e) => (e.currentTarget.style.filter = "brightness(.92)")}
              onMouseLeave={(e) => (e.currentTarget.style.filter = "none")}
            >
              비밀번호 찾기로 가기
            </Link>
          </>
        ) : done ? (
          <>
            <h1 className="mb-1 text-[22px] font-extrabold" style={{ color: "var(--ink)" }}>비밀번호가 바뀌었어요</h1>
            <p className="mb-6 text-[13.5px] leading-relaxed" style={{ color: "var(--mut)" }}>
              새 비밀번호로 다시 로그인해주세요.
            </p>
            <Link
              href="/login"
              className="block w-full rounded-[14px] py-3.5 text-center text-[14px] font-bold text-white transition-[filter] duration-150"
              style={{ background: "var(--accent)" }}
              onMouseEnter={(e) => (e.currentTarget.style.filter = "brightness(.92)")}
              onMouseLeave={(e) => (e.currentTarget.style.filter = "none")}
            >
              로그인하러 가기
            </Link>
          </>
        ) : (
          <>
            <h1 className="mb-1 text-center text-[22px] font-extrabold" style={{ color: "var(--ink)" }}>새 비밀번호 설정</h1>
            <p className="mb-5 text-center text-[13.5px]" style={{ color: "var(--mut)" }}>
              새로 사용할 비밀번호를 입력해주세요
            </p>

            <form onSubmit={handleSubmit} className="space-y-3">
              <div>
                <label className="mb-1 block text-[12px]" style={{ color: "var(--mut)" }}>새 비밀번호</label>
                <input
                  type="password"
                  required
                  minLength={8}
                  maxLength={64}
                  value={newPassword}
                  onChange={(e) => setNewPassword(e.target.value)}
                  className="w-full rounded-xl px-4 py-3.5 text-[13.5px] outline-none"
                  style={{ background: "var(--fill)", color: "var(--ink)" }}
                  placeholder="8자 이상 입력하세요"
                />
              </div>
              <div>
                <label className="mb-1 block text-[12px]" style={{ color: "var(--mut)" }}>새 비밀번호 확인</label>
                <input
                  type="password"
                  required
                  value={newPasswordConfirm}
                  onChange={(e) => setNewPasswordConfirm(e.target.value)}
                  className="w-full rounded-xl px-4 py-3.5 text-[13.5px] outline-none"
                  style={{ background: "var(--fill)", color: "var(--ink)" }}
                  placeholder="비밀번호를 다시 입력하세요"
                />
                {passwordMismatch && (
                  <div className="mt-1 text-[11.5px]" style={{ color: "var(--warnText)" }}>비밀번호가 서로 달라요.</div>
                )}
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
                {submitting ? "변경 중…" : "비밀번호 변경하기"}
              </button>
            </form>
          </>
        )}
      </div>
    </div>
  );
}

export default function ResetPasswordPage() {
  return (
    <Suspense>
      <ResetPasswordForm />
    </Suspense>
  );
}
