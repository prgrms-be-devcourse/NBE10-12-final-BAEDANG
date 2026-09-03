"use client";

import Link from "next/link";
import { Suspense, useState, type FormEvent } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useAuth } from "@/components/AuthProvider";
import { ApiError, login } from "@/lib/api";

function LoginForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { setUser } = useAuth();
  const next = searchParams.get("next");

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      const user = await login({ email, password });
      setUser(user);
      router.push(next ?? "/");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "로그인에 실패했어요.");
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
        <h1 className="mb-1 text-[22px] font-extrabold" style={{ color: "var(--ink)" }}>로그인</h1>
        <p className="mb-5 text-[13.5px]" style={{ color: "var(--mut)" }}>모의 투자금으로 다시 시작해볼까요?</p>

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
          <div>
            <label className="mb-1 block text-[12px]" style={{ color: "var(--mut)" }}>비밀번호</label>
            <input
              type="password"
              required
              minLength={8}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="w-full rounded-xl px-4 py-3.5 text-[13.5px] outline-none"
              style={{ background: "var(--fill)", color: "var(--ink)" }}
              placeholder="비밀번호를 입력하세요"
            />
          </div>

          <div className="text-right">
            <span className="cursor-default text-[12.5px] underline underline-offset-2" style={{ color: "var(--mut)" }}>
              비밀번호를 잊으셨나요?
            </span>
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
            {submitting ? "로그인 중…" : "로그인"}
          </button>
        </form>

        <div className="my-4 flex items-center gap-3">
          <div className="h-px flex-1" style={{ background: "var(--line)" }} />
          <span className="text-[12px]" style={{ color: "var(--mut2)" }}>또는</span>
          <div className="h-px flex-1" style={{ background: "var(--line)" }} />
        </div>

        <div className="text-center text-[12.5px]" style={{ color: "var(--mut)" }}>
          아직 계정이 없으신가요?{" "}
          <Link
            href={next ? `/signup?next=${encodeURIComponent(next)}` : "/signup"}
            className="font-bold"
            style={{ color: "var(--accentText)" }}
          >
            회원가입하고 5,000만원 받기
          </Link>
        </div>
      </div>
    </div>
  );
}

export default function LoginPage() {
  return (
    <Suspense>
      <LoginForm />
    </Suspense>
  );
}
