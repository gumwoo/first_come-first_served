"use client";

import Link from "next/link";
import { useState } from "react";
import { ShieldCheck } from "lucide-react";
import { useForm } from "react-hook-form";
import { ApiError } from "@/lib/apiClient";
import { useRequestPhoneCode, useVerifyPhoneCode, useSignup } from "@/features/auth/hooks/useAuth";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Card, CardContent } from "@/components/ui/card";

type Form = {
  name: string;
  email: string;
  password: string;
  passwordConfirm: string;
  phone: string;
  code: string;
};

export default function SignupPage() {
  const { register, handleSubmit, getValues } = useForm<Form>();
  const [phoneVerified, setPhoneVerified] = useState(false);
  const [terms, setTerms] = useState({ service: false, privacy: false });
  const requestCode = useRequestPhoneCode();
  const verifyCode = useVerifyPhoneCode();
  const signup = useSignup();

  const allAgreed = terms.service && terms.privacy;
  const setAll = (v: boolean) => setTerms({ service: v, privacy: v });

  return (
    <main className="mx-auto max-w-4xl px-4 py-12">
      <div className="mb-8 text-center">
        <h1 className="text-2xl font-bold">회원가입</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          FlowTicket 계정을 만들고 빠르게 예매를 시작하세요.
        </p>
      </div>

      <div className="grid gap-6 md:grid-cols-[1fr_300px]">
        <Card>
          <CardContent className="pt-6">
            <form
              className="space-y-4"
              onSubmit={handleSubmit((v) =>
                signup.mutate({
                  email: v.email,
                  password: v.password,
                  name: v.name,
                  phone: v.phone,
                  termsAccepted: allAgreed,
                })
              )}
            >
              <div className="space-y-1.5">
                <Label>이름</Label>
                <Input placeholder="이름을 입력하세요" {...register("name", { required: true })} />
              </div>
              <div className="space-y-1.5">
                <Label>이메일</Label>
                <Input type="email" placeholder="이메일 주소" {...register("email", { required: true })} />
              </div>
              <div className="space-y-1.5">
                <Label>비밀번호</Label>
                <Input type="password" placeholder="8자 이상" {...register("password", { required: true })} />
              </div>
              <div className="space-y-1.5">
                <Label>비밀번호 확인</Label>
                <Input type="password" placeholder="비밀번호 재입력" {...register("passwordConfirm", { required: true })} />
              </div>

              <div className="space-y-1.5">
                <Label>휴대폰 번호</Label>
                <div className="flex gap-2">
                  <Input placeholder="숫자만 입력 (01012345678)" {...register("phone", { required: true })} />
                  <Button type="button" variant="outline" className="shrink-0"
                    onClick={() => requestCode.mutate(getValues("phone"))}>
                    인증요청
                  </Button>
                </div>
              </div>

              <div className="space-y-1.5">
                <Label>인증번호</Label>
                <div className="flex gap-2">
                  <Input placeholder="인증번호 6자리" {...register("code")} />
                  <Button type="button" variant="outline" className="shrink-0"
                    onClick={() =>
                      verifyCode.mutate(
                        { phone: getValues("phone"), code: getValues("code") },
                        { onSuccess: () => setPhoneVerified(true) }
                      )
                    }>
                    인증확인
                  </Button>
                </div>
                {phoneVerified && <p className="text-sm text-success">휴대폰 인증이 완료되었습니다.</p>}
                {verifyCode.isError && (
                  <p className="text-sm text-destructive">{(verifyCode.error as ApiError).message}</p>
                )}
              </div>

              {/* 약관 */}
              <div className="space-y-2 rounded-md border border-border p-3 text-sm">
                <label className="flex items-center gap-2 font-medium">
                  <input type="checkbox" checked={allAgreed} onChange={(e) => setAll(e.target.checked)} />
                  전체 동의
                </label>
                <label className="flex items-center gap-2 text-muted-foreground">
                  <input type="checkbox" checked={terms.service}
                    onChange={(e) => setTerms((t) => ({ ...t, service: e.target.checked }))} />
                  (필수) 서비스 이용약관 동의
                </label>
                <label className="flex items-center gap-2 text-muted-foreground">
                  <input type="checkbox" checked={terms.privacy}
                    onChange={(e) => setTerms((t) => ({ ...t, privacy: e.target.checked }))} />
                  (필수) 개인정보 수집·이용 동의
                </label>
              </div>

              {signup.isError && (
                <p className="text-sm text-destructive">{(signup.error as ApiError).message}</p>
              )}

              <Button type="submit" disabled={!phoneVerified || !allAgreed || signup.isPending}
                className="w-full">
                {signup.isPending ? "가입 중…" : "회원가입"}
              </Button>
              {!phoneVerified && (
                <p className="text-center text-xs text-muted-foreground">
                  휴대폰 인증을 완료해야 가입할 수 있습니다.
                </p>
              )}

              <p className="pt-1 text-center text-sm text-muted-foreground">
                이미 계정이 있으신가요? <Link href="/login" className="text-primary">로그인</Link>
              </p>
            </form>
          </CardContent>
        </Card>

        <Card className="h-fit bg-muted/30">
          <CardContent className="space-y-3 pt-6 text-sm">
            <div className="flex items-center gap-2 font-medium">
              <ShieldCheck className="h-4 w-4 text-primary" /> 가입 안내
            </div>
            <ul className="space-y-2 text-muted-foreground">
              <li>· 1인 1계정 정책으로 운영됩니다.</li>
              <li>· 휴대폰 인증은 본인 확인 및 어뷰징 방지를 위해 필요합니다.</li>
              <li>· 데모 환경에서는 인증번호 123456으로 인증됩니다.</li>
            </ul>
          </CardContent>
        </Card>
      </div>
    </main>
  );
}
