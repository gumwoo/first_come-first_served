"use client";

import Link from "next/link";
import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { ShieldCheck } from "lucide-react";
import { useForm } from "react-hook-form";
import { ApiError } from "@/lib/apiClient";
import { useLogin } from "@/features/auth/hooks/useAuth";
import { useAuthStore } from "@/features/auth/store/authStore";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Card, CardContent } from "@/components/ui/card";

type Form = { email: string; password: string; remember: boolean };

export default function LoginPage() {
  const { register, handleSubmit } = useForm<Form>();
  const login = useLogin();
  const router = useRouter();
  const user = useAuthStore((s) => s.user);

  // 이미 로그인한 사용자는 로그인 페이지에 머물 이유가 없음 → 홈으로
  useEffect(() => {
    if (user) router.replace("/");
  }, [user, router]);

  return (
    <main className="mx-auto max-w-4xl px-4 py-12">
      <div className="mb-8 text-center">
        <h1 className="text-2xl font-bold">로그인</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          예매를 위해 FlowTicket 계정으로 로그인하세요.
        </p>
      </div>

      <div className="grid gap-6 md:grid-cols-[1fr_300px]">
        {/* 로그인 폼 */}
        <Card>
          <CardContent className="pt-6">
            <form className="space-y-4" onSubmit={handleSubmit((v) => login.mutate(v))}>
              <div className="space-y-1.5">
                <Label htmlFor="email">이메일</Label>
                <Input id="email" type="email" placeholder="이메일 주소를 입력하세요"
                  {...register("email", { required: true })} />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="password">비밀번호</Label>
                <Input id="password" type="password" placeholder="비밀번호를 입력하세요"
                  {...register("password", { required: true })} />
              </div>

              <div className="flex items-center justify-between text-sm">
                <label className="flex items-center gap-2 text-muted-foreground">
                  <input type="checkbox" {...register("remember")} />
                  로그인 상태 유지
                </label>
                <Link href="/login" className="text-muted-foreground hover:text-foreground">
                  비밀번호를 잊으셨나요?
                </Link>
              </div>

              {login.isError && (
                <p className="text-sm text-destructive">
                  {(login.error as ApiError).message ?? "로그인에 실패했습니다."}
                </p>
              )}

              <Button type="submit" disabled={login.isPending} className="w-full">
                {login.isPending ? "로그인 중…" : "로그인"}
              </Button>

              <div className="flex items-center gap-3 py-1 text-xs text-muted-foreground">
                <span className="h-px flex-1 bg-border" />또는<span className="h-px flex-1 bg-border" />
              </div>

              {/* 소셜 로그인 — 카카오/네이버 공식 버튼 에셋 사용 */}
              <div className="space-y-2">
                <a href="/oauth2/authorization/kakao" className="block">
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img src="/social/kakao_login.png" alt="카카오 로그인"
                    className="h-12 w-full rounded-md object-cover" />
                </a>
                <a href="/oauth2/authorization/naver" className="block">
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img src="/social/naver_login.png" alt="네이버 로그인"
                    className="h-12 w-full rounded-md object-cover" />
                </a>
              </div>

              <p className="pt-2 text-center text-sm text-muted-foreground">
                아직 회원이 아니신가요? <Link href="/signup" className="text-primary">회원가입</Link>
              </p>
            </form>
          </CardContent>
        </Card>

        {/* 안내 박스 */}
        <Card className="h-fit bg-muted/30">
          <CardContent className="space-y-3 pt-6 text-sm">
            <div className="flex items-center gap-2 font-medium">
              <ShieldCheck className="h-4 w-4 text-primary" /> 안내
            </div>
            <ul className="space-y-2 text-muted-foreground">
              <li>· 안전한 로그인을 위해 비밀번호는 암호화되어 보관됩니다.</li>
              <li>· 소셜 로그인 계정은 이메일/비밀번호 로그인을 사용할 수 없습니다.</li>
              <li>· 로그인 후 예매 진행 시 본인 인증이 필요할 수 있습니다.</li>
            </ul>
          </CardContent>
        </Card>
      </div>
    </main>
  );
}
