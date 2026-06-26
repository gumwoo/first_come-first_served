import { useMutation } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import * as authApi from "@/features/auth/api/auth";
import { useAuthStore } from "@/features/auth/store/authStore";

export function useLogin() {
  const router = useRouter();
  const setAccessToken = useAuthStore((s) => s.setAccessToken);
  return useMutation({
    mutationFn: (v: { email: string; password: string; remember: boolean }) =>
      authApi.login(v.email, v.password, v.remember),
    onSuccess: (token) => {
      setAccessToken(token.accessToken);
      router.push("/");
    },
  });
}

export function useSignup() {
  const router = useRouter();
  return useMutation({
    mutationFn: (body: authApi.SignupBody) => authApi.signup(body),
    onSuccess: () => router.push("/login"),
  });
}

export function useRequestPhoneCode() {
  return useMutation({ mutationFn: (phone: string) => authApi.requestPhoneCode(phone) });
}

export function useVerifyPhoneCode() {
  return useMutation({
    mutationFn: (v: { phone: string; code: string }) => authApi.verifyPhoneCode(v.phone, v.code),
  });
}
