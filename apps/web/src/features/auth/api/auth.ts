import { api } from "@/lib/apiClient";

export type TokenResponse = { accessToken: string; refreshToken: string };
export type Me = { id: number; email: string; name: string; role: string; provider: string };

export type SignupBody = {
  email: string;
  password: string;
  name: string;
  phone: string;
  termsAccepted: boolean;
};

export const requestPhoneCode = (phone: string) =>
  api<void>("/auth/phone/request", { method: "POST", body: { phone } });

export const verifyPhoneCode = (phone: string, code: string) =>
  api<void>("/auth/phone/verify", { method: "POST", body: { phone, code } });

export const signup = (body: SignupBody) =>
  api<void>("/auth/signup", { method: "POST", body });

export const login = (email: string, password: string) =>
  api<TokenResponse>("/auth/login", { method: "POST", body: { email, password } });

export const logout = (token: string | null) =>
  api<void>("/auth/logout", { method: "POST", token });

export const getMe = (token: string | null) => api<Me>("/me", { token });
