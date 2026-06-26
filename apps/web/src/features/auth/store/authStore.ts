import { create } from "zustand";

// Access Token은 메모리에만 보관(보안). Refresh는 httpOnly 쿠키 가정.
type AuthState = {
  accessToken: string | null;
  setAccessToken: (token: string | null) => void;
  clear: () => void;
};

export const useAuthStore = create<AuthState>((set) => ({
  accessToken: null,
  setAccessToken: (token) => set({ accessToken: token }),
  clear: () => set({ accessToken: null }),
}));
