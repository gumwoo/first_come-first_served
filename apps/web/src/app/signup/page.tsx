"use client";

import Link from "next/link";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { ApiError } from "@/lib/apiClient";
import { useRequestPhoneCode, useVerifyPhoneCode, useSignup } from "@/features/auth/hooks/useAuth";

type Form = {
  name: string;
  email: string;
  password: string;
  passwordConfirm: string;
  phone: string;
  code: string;
  termsAccepted: boolean;
};

export default function SignupPage() {
  const { register, handleSubmit, getValues, watch } = useForm<Form>();
  const [phoneVerified, setPhoneVerified] = useState(false);
  const requestCode = useRequestPhoneCode();
  const verifyCode = useVerifyPhoneCode();
  const signup = useSignup();

  return (
    <main className="mx-auto max-w-md p-8">
      <h1 className="text-2xl font-bold">회원가입</h1>
      <p className="mt-1 text-sm text-muted-foreground">
        FlowTicket 계정을 만들고 빠르게 예매를 시작하세요.
      </p>

      <form
        className="mt-6 space-y-4"
        onSubmit={handleSubmit((v) =>
          signup.mutate({
            email: v.email,
            password: v.password,
            name: v.name,
            phone: v.phone,
            termsAccepted: v.termsAccepted,
          })
        )}
      >
        <input className="w-full rounded border border-border px-3 py-2" placeholder="이름"
          {...register("name", { required: true })} />
        <input className="w-full rounded border border-border px-3 py-2" placeholder="이메일" type="email"
          {...register("email", { required: true })} />
        <input className="w-full rounded border border-border px-3 py-2" placeholder="비밀번호(8자 이상)" type="password"
          {...register("password", { required: true })} />
        <input className="w-full rounded border border-border px-3 py-2" placeholder="비밀번호 확인" type="password"
          {...register("passwordConfirm", { required: true })} />

        <div className="flex gap-2">
          <input className="flex-1 rounded border border-border px-3 py-2" placeholder="휴대폰 번호(숫자만)"
            {...register("phone", { required: true })} />
          <button type="button" className="rounded border border-border px-3 text-sm"
            onClick={() => requestCode.mutate(getValues("phone"))}>
            인증요청
          </button>
        </div>

        <div className="flex gap-2">
          <input className="flex-1 rounded border border-border px-3 py-2" placeholder="인증번호 6자리"
            {...register("code")} />
          <button type="button" className="rounded border border-border px-3 text-sm"
            onClick={() =>
              verifyCode.mutate(
                { phone: getValues("phone"), code: getValues("code") },
                { onSuccess: () => setPhoneVerified(true) }
              )
            }>
            인증확인
          </button>
        </div>
        {phoneVerified && <p className="text-sm text-green-600">휴대폰 인증 완료</p>}
        {verifyCode.isError && (
          <p className="text-sm text-red-600">{(verifyCode.error as ApiError).message}</p>
        )}

        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" {...register("termsAccepted", { required: true })} />
          (필수) 서비스 이용약관 및 개인정보 처리방침에 동의합니다.
        </label>

        {signup.isError && (
          <p className="text-sm text-red-600">{(signup.error as ApiError).message}</p>
        )}

        <button type="submit" disabled={!phoneVerified || signup.isPending}
          className="w-full rounded bg-primary py-2 text-white disabled:opacity-50">
          {signup.isPending ? "가입 중…" : "회원가입"}
        </button>
        {!phoneVerified && watch("phone") && (
          <p className="text-xs text-muted-foreground">휴대폰 인증을 완료해야 가입할 수 있습니다.</p>
        )}
      </form>

      <p className="mt-6 text-center text-sm text-muted-foreground">
        이미 계정이 있으신가요? <Link href="/login" className="text-primary">로그인</Link>
      </p>
    </main>
  );
}
