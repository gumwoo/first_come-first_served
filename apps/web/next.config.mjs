/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  async rewrites() {
    return [
      // 프론트는 외부(KOPIS) 직접호출 금지 — 항상 우리 BE 경유
      { source: "/api/:path*", destination: "http://localhost:8080/:path*" },
    ];
  },
};

export default nextConfig;
