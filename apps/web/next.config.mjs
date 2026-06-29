/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  async rewrites() {
    return [
      // 프론트는 외부(KOPIS) 직접호출 금지 — 항상 우리 BE 경유
      { source: "/api/:path*", destination: "http://localhost:8080/:path*" },
      // 소셜 로그인 시작 — BE OAuth2 엔드포인트로 프록시(브라우저 전체 이동)
      { source: "/oauth2/:path*", destination: "http://localhost:8080/oauth2/:path*" },
    ];
  },
};

export default nextConfig;
