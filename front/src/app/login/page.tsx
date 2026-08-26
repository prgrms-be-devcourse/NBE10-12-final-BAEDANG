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
    <div className="flex min-h-[70vh] items-center justify-center p-6">
      <div className="w-full max-w-[380px] rounded-lg border border-gray-200 p-7">
        <h1 className="mb-1 text-[19px] font-bold text-gray-900">로그인</h1>
        <p className="mb-5 text-[13px] text-gray-500">이메일과 비밀번호를 입력해주세요</p>

        <form onSubmit={handleSubmit} className="space-y-3">
          <div>
            <label className="mb-1 block text-[12px] text-gray-500">이메일</label>
            <input
              type="email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-[13px] outline-none focus:border-gray-500"
              placeholder="you@example.com"
            />
          </div>
          <div>
            <label className="mb-1 block text-[12px] text-gray-500">비밀번호</label>
            <input
              type="password"
              required
              minLength={8}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-[13px] outline-none focus:border-gray-500"
              placeholder="8자 이상"
            />
          </div>

          {error && (
            <div className="rounded-md border border-gray-300 bg-gray-50 px-3 py-2 text-[12.5px] text-gray-700">
              {error}
            </div>
          )}

          <button
            type="submit"
            disabled={submitting}
            className="w-full rounded-md bg-gray-900 py-2.5 text-[13px] font-medium text-white hover:bg-black disabled:opacity-50"
          >
            {submitting ? "로그인 중…" : "로그인"}
          </button>
        </form>

        <div className="mt-4 text-center text-[12.5px] text-gray-500">
          아직 계정이 없으신가요?{" "}
          <Link
            href={next ? `/signup?next=${encodeURIComponent(next)}` : "/signup"}
            className="font-medium text-gray-900 underline underline-offset-2"
          >
            회원가입
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
