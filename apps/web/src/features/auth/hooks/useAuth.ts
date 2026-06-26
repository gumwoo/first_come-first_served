import { useMutation } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import * as authApi from "@/features/auth/api/auth";
import { useAuthStore } from "@/features/auth/store/authStore";

export function useLogin() {
  const router = useRouter();
  const { setAccessToken, setUser } = useAuthStore();
  return useMutation({
    mutationFn: (v: { email: string; password: string; remember: boolean }) =>
      authApi.login(v.email, v.password, v.remember),
    onSuccess: async (token) => {
      setAccessToken(token.accessToken);
      try {
        setUser(await authApi.getMe(token.accessToken));
      } catch {
        /* 프로필 조회 실패는 치명적이지 않음 */
      }
      router.push("/");
    },
  });
}

export function useLogout() {
  const router = useRouter();
  const { accessToken, clear } = useAuthStore();
  return useMutation({
    mutationFn: () => authApi.logout(accessToken),
    onSettled: () => {
      // 서버 응답과 무관하게 클라 상태는 정리
      clear();
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
