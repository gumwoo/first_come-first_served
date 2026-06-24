import type { Config } from "tailwindcss";

const config: Config = {
  content: ["./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        // design-system.md 토큰과 매핑 (CSS 변수 기반)
        background: "hsl(var(--background))",
        foreground: "hsl(var(--foreground))",
        primary: "hsl(var(--primary))",
        muted: "hsl(var(--muted))",
        border: "hsl(var(--border))",
      },
    },
  },
  plugins: [],
};

export default config;
