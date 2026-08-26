"use client";

import Link from "next/link";
import { Suspense, useState, type FormEvent } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useAuth } from "@/components/AuthProvider";
import { ApiError, signUp } from "@/lib/api";

function SignupForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { setUser } = useAuth();
  const next = searchParams.get("next");

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [nickname, setNickname] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setFieldErrors({});
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
    <div className="flex min-h-[70vh] items-center justify-center p-6">
      <div className="w-full max-w-[380px] rounded-lg border border-gray-200 p-7">
        <h1 className="mb-1 text-[19px] font-bold text-gray-900">회원가입</h1>
        <p className="mb-5 text-[13px] text-gray-500">
          가입하면 <b className="text-gray-900">모의 투자금 5,000만원</b>을 바로 드립니다
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
                className="w-full rounded-md border border-gray-300 px-3 py-2 text-[13px] outline-none focus:border-gray-500"
                placeholder="you@example.com"
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
                className="w-full rounded-md border border-gray-300 px-3 py-2 text-[13px] outline-none focus:border-gray-500"
                placeholder="8자 이상"
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
                className="w-full rounded-md border border-gray-300 px-3 py-2 text-[13px] outline-none focus:border-gray-500"
                placeholder="2~20자"
              />
            }
          />

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
            {submitting ? "가입 중…" : "회원가입하고 5,000만원 받기"}
          </button>
        </form>

        <div className="mt-4 text-center text-[12.5px] text-gray-500">
          이미 계정이 있으신가요?{" "}
          <Link
            href={next ? `/login?next=${encodeURIComponent(next)}` : "/login"}
            className="font-medium text-gray-900 underline underline-offset-2"
          >
            로그인
          </Link>
        </div>
      </div>
    </div>
  );
}

function Field({ label, input, error }: { label: string; input: React.ReactNode; error?: string }) {
  return (
    <div>
      <label className="mb-1 block text-[12px] text-gray-500">{label}</label>
      {input}
      {error && <div className="mt-1 text-[11.5px] text-gray-500">{error}</div>}
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
