"use client";

import Link from "next/link";
import { useForm } from "react-hook-form";
import { ApiError } from "@/lib/apiClient";
import { useLogin } from "@/features/auth/hooks/useAuth";

type Form = { email: string; password: string };

export default function LoginPage() {
  const { register, handleSubmit } = useForm<Form>();
  const login = useLogin();

  return (
    <main className="mx-auto max-w-md p-8">
      <h1 className="text-2xl font-bold">로그인</h1>
      <p className="mt-1 text-sm text-muted-foreground">
        예매를 위해 FlowTicket 계정으로 로그인하세요.
      </p>

      <form
        className="mt-6 space-y-4"
        onSubmit={handleSubmit((v) => login.mutate(v))}
      >
        <div>
          <label className="block text-sm">이메일</label>
          <input
            type="email"
            className="mt-1 w-full rounded border border-border px-3 py-2"
            {...register("email", { required: true })}
          />
        </div>
        <div>
          <label className="block text-sm">비밀번호</label>
          <input
            type="password"
            className="mt-1 w-full rounded border border-border px-3 py-2"
            {...register("password", { required: true })}
          />
        </div>

        {login.isError && (
          <p className="text-sm text-red-600">
            {(login.error as ApiError).message ?? "로그인에 실패했습니다."}
          </p>
        )}

        <button
          type="submit"
          disabled={login.isPending}
          className="w-full rounded bg-primary py-2 text-white disabled:opacity-50"
        >
          {login.isPending ? "로그인 중…" : "로그인"}
        </button>
      </form>

      <div className="mt-4 space-y-2">
        <a href="/oauth2/authorization/kakao" className="block rounded border border-border py-2 text-center">
          카카오로 계속하기
        </a>
        <a href="/oauth2/authorization/naver" className="block rounded border border-border py-2 text-center">
          네이버로 계속하기
        </a>
      </div>

      <p className="mt-6 text-center text-sm text-muted-foreground">
        아직 회원이 아니신가요? <Link href="/signup" className="text-primary">회원가입</Link>
      </p>
    </main>
  );
}
