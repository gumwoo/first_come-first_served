import { create } from "zustand";
import type { Me } from "@/features/auth/api/auth";

// Access Token은 메모리에만 보관(보안). Refresh는 httpOnly 쿠키.
type AuthState = {
  accessToken: string | null;
  user: Me | null;
  setAccessToken: (token: string | null) => void;
  setUser: (user: Me | null) => void;
  clear: () => void;
};

export const useAuthStore = create<AuthState>((set) => ({
  accessToken: null,
  user: null,
  setAccessToken: (token) => set({ accessToken: token }),
  setUser: (user) => set({ user }),
  clear: () => set({ accessToken: null, user: null }),
}));
