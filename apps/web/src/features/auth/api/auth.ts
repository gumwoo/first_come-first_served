import { api } from "@/lib/apiClient";

// Refresh는 httpOnly 쿠키로 오가므로 본문엔 accessToken만.
export type AccessResponse = { accessToken: string };
export type Me = { id: number; email: string; name: string; role: string; provider: string };

export type SignupBody = {
  email: string;
  password: string;
  name: string;
  phone: string;
  termsAccepted: boolean;
  marketingOptIn: boolean;
};

export const requestPhoneCode = (phone: string) =>
  api<void>("/auth/phone/request", { method: "POST", body: { phone } });

export const verifyPhoneCode = (phone: string, code: string) =>
  api<void>("/auth/phone/verify", { method: "POST", body: { phone, code } });

export const signup = (body: SignupBody) =>
  api<void>("/auth/signup", { method: "POST", body });

export const login = (email: string, password: string, remember: boolean) =>
  api<AccessResponse>("/auth/login", { method: "POST", body: { email, password, remember } });

/** 앱 로드 시 httpOnly 쿠키로 Access 재발급(로그인 유지). */
export const refresh = () => api<AccessResponse>("/auth/refresh", { method: "POST" });

export const logout = (token: string | null) =>
  api<void>("/auth/logout", { method: "POST", token });

export const getMe = (token: string | null) => api<Me>("/me", { token });
