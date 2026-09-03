"use client";

import Link from "next/link";
import { Suspense, useState, type FormEvent } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useAuth } from "@/components/AuthProvider";
import { TermsModal } from "@/components/TermsModal";
import { ApiError, signUp } from "@/lib/api";

function SignupForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { setUser } = useAuth();
  const next = searchParams.get("next");

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [passwordConfirm, setPasswordConfirm] = useState("");
  const [nickname, setNickname] = useState("");
  const [agreed, setAgreed] = useState(false);
  // 체크박스를 눌러도 곧장 동의 처리되지 않고, 이용약관/개인정보 처리방침 팝업이
  // 뜬다(제보) — 실제 동의(agreed)는 팝업 하단의 동의 체크박스를 눌러야만 바뀐다.
  const [termsOpen, setTermsOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);

  const passwordMismatch = passwordConfirm.length > 0 && password !== passwordConfirm;

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setFieldErrors({});
    if (password !== passwordConfirm) {
      setError("비밀번호가 서로 달라요.");
      return;
    }
    if (!agreed) {
      setError("이용약관과 개인정보 처리방침에 동의해주세요.");
      return;
    }
    setSubmitting(true);
    try {
      const user = await signUp({ email, password, nickname });
      setUser(user);
      router.push(next ?? "/");
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message);
        if (err.fieldErrors) setFieldErrors(err.fieldErrors);
      } else {
        setError("회원가입에 실패했어요.");
      }
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
        <h1 className="mb-1 text-[22px] font-extrabold" style={{ color: "var(--ink)" }}>회원가입</h1>
        <p className="mb-5 text-[13.5px]" style={{ color: "var(--mut)" }}>
          가입하면 <b style={{ color: "var(--ink)" }}>모의 투자금 5,000만원</b>을 바로 드려요
        </p>

        <form onSubmit={handleSubmit} className="space-y-3">
          <Field
            label="이메일"
            error={fieldErrors.email}
            input={
              <input
                type="email"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                className="w-full rounded-xl px-4 py-3.5 text-[13.5px] outline-none"
                style={{ background: "var(--fill)", color: "var(--ink)" }}
                placeholder="you@example.com"
              />
            }
          />
          <Field
            label="닉네임"
            error={fieldErrors.nickname}
            input={
              <input
                type="text"
                required
                minLength={2}
                maxLength={20}
                value={nickname}
                onChange={(e) => setNickname(e.target.value)}
                className="w-full rounded-xl px-4 py-3.5 text-[13.5px] outline-none"
                style={{ background: "var(--fill)", color: "var(--ink)" }}
                placeholder="2~20자"
              />
            }
          />
          <Field
            label="비밀번호"
            error={fieldErrors.password}
            input={
              <input
                type="password"
                required
                minLength={8}
                maxLength={64}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className="w-full rounded-xl px-4 py-3.5 text-[13.5px] outline-none"
                style={{ background: "var(--fill)", color: "var(--ink)" }}
                placeholder="8자 이상 입력하세요"
              />
            }
          />
          <Field
            label="비밀번호 확인"
            error={passwordMismatch ? "비밀번호가 서로 달라요." : undefined}
            input={
              <input
                type="password"
                required
                value={passwordConfirm}
                onChange={(e) => setPasswordConfirm(e.target.value)}
                className="w-full rounded-xl px-4 py-3.5 text-[13.5px] outline-none"
                style={{ background: "var(--fill)", color: "var(--ink)" }}
                placeholder="비밀번호를 다시 입력하세요"
              />
            }
          />

          <label className="flex items-start gap-2.5 pt-1 text-[12.5px] leading-[1.55]" style={{ color: "var(--mut)" }}>
            <input
              type="checkbox"
              checked={agreed}
              onChange={() => setTermsOpen(true)}
              className="mt-0.5 h-4 w-4 cursor-pointer"
              style={{ accentColor: "var(--accent)" }}
            />
            <span>
              이용약관과 개인정보 처리방침에 동의해요.
              <br />
              모의 투자 서비스이며 실제 매매는 이루어지지 않아요.
            </span>
          </label>

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
            {submitting ? "가입 중…" : "모의 투자금 받고 시작하기"}
          </button>
        </form>

        {/* form 안에 두면 팝업 속 동의 체크박스까지 회원가입 form에 DOM으로 딸려
            들어가서, form 밖(형제)으로 뺐다 — 모달 자체는 position:fixed라
            어차피 화면 전체를 덮으니 위치상 차이는 없다. */}
        {termsOpen && (
          <TermsModal initialAgreed={agreed} onConfirm={setAgreed} onClose={() => setTermsOpen(false)} />
        )}

        <div className="my-4 flex items-center gap-3">
          <div className="h-px flex-1" style={{ background: "var(--line)" }} />
          <span className="text-[12px]" style={{ color: "var(--mut2)" }}>또는</span>
          <div className="h-px flex-1" style={{ background: "var(--line)" }} />
        </div>

        <div className="text-center text-[12.5px]" style={{ color: "var(--mut)" }}>
          이미 계정이 있으신가요?{" "}
          <Link
            href={next ? `/login?next=${encodeURIComponent(next)}` : "/login"}
            className="font-bold"
            style={{ color: "var(--accentText)" }}
          >
            로그인하기
          </Link>
        </div>
      </div>
    </div>
  );
}

function Field({ label, input, error }: { label: string; input: React.ReactNode; error?: string }) {
  return (
    <div>
      <label className="mb-1 block text-[12px] font-bold" style={{ color: "var(--mut)" }}>{label}</label>
      {input}
      {error && <div className="mt-1 text-[11.5px]" style={{ color: "var(--warnText)" }}>{error}</div>}
    </div>
  );
}

export default function SignupPage() {
  return (
    <Suspense>
      <SignupForm />
    </Suspense>
  );
}
