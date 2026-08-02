/** @type {import('next').NextConfig} */

// 브라우저는 항상 같은 오리진의 /api·/oauth2로 요청하고, Next 서버가 백엔드로 프록시한다.
// 목적지를 하드코딩하면 컨테이너/K8s에서 localhost가 'web Pod 자신'이 되어 API 호출이 전부 실패한다.
// rewrites()는 서버 기동 시 평가되므로 런타임 환경변수로 주입할 수 있다(빌드 이미지 재사용 가능).
const apiOrigin = process.env.API_ORIGIN ?? "http://localhost:8080";

const nextConfig = {
  // 컨테이너 이미지를 얇게: 실행에 필요한 것만 담은 독립 실행 번들(node server.js)을 생성한다.
  // 이미지 빌드에서만 켠다 — standalone 출력은 심볼릭 링크를 만들어서 Windows 로컬 빌드가
  // 권한(EPERM)으로 실패한다. 로컬 검증(typecheck/lint/build)은 기본 출력 그대로 두고,
  // Dockerfile이 NEXT_OUTPUT_STANDALONE=true로 켠다.
  output: process.env.NEXT_OUTPUT_STANDALONE === "true" ? "standalone" : undefined,
  reactStrictMode: true,
  async rewrites() {
    return [
      // 프론트는 외부(KOPIS) 직접호출 금지 — 항상 우리 BE 경유
      { source: "/api/:path*", destination: `${apiOrigin}/:path*` },
      // 소셜 로그인 시작 — BE OAuth2 엔드포인트로 프록시(브라우저 전체 이동)
      { source: "/oauth2/:path*", destination: `${apiOrigin}/oauth2/:path*` },
    ];
  },
};

export default nextConfig;
