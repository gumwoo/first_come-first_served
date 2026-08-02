# FlowTicket Web(Next.js) 운영 이미지.
# 빌드 컨텍스트는 저장소 루트 — `docker build -f apps/web/Dockerfile .`
#
# Next.js `output: "standalone"`은 실행에 필요한 의존성만 추려 번들을 만든다.
# 그래서 최종 이미지에는 node_modules 전체가 아니라 추려진 결과만 들어간다.

# ── 1단계: 빌드 ───────────────────────────────────────────────
FROM node:20-bookworm-slim AS builder
WORKDIR /app
RUN corepack enable

# 락파일 기준으로 먼저 설치 → 소스만 바뀐 재빌드에서 설치 레이어가 캐시된다.
COPY apps/web/package.json apps/web/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile

COPY apps/web/ ./

# API 오리진은 <b>빌드 시점</b>에 정해진다. next.config의 rewrites는 standalone 번들로 구워지므로
# 런타임 환경변수로는 바뀌지 않는다(실제로 런타임 주입을 시도했더니 빌드 때 값인 localhost:8080으로
# 프록시해 ECONNREFUSED가 났다). 그래서 build-arg로 받는다.
#  - 로컬 compose: http://api:8080 (컨테이너 네트워크 서비스명)
#  - K8s: 브라우저의 /api/* 는 Ingress가 API Service로 직접 라우팅하므로 이 값이 경로에 끼지 않는다.
ARG API_ORIGIN=http://localhost:8080
ENV API_ORIGIN=$API_ORIGIN

# standalone은 로컬(Windows) 빌드를 심볼릭 링크 권한 문제로 깨뜨리므로 이미지 빌드에서만 켠다.
ENV NEXT_OUTPUT_STANDALONE=true
ENV NEXT_TELEMETRY_DISABLED=1
RUN pnpm build

# ── 2단계: 실행 ───────────────────────────────────────────────
FROM node:20-bookworm-slim AS runner
WORKDIR /app
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1

RUN useradd --system --uid 10001 --create-home nextjs

# standalone 번들 + 정적 자산. static/public은 번들에 포함되지 않아 따로 복사해야 한다.
COPY --from=builder --chown=nextjs:nextjs /app/.next/standalone ./
COPY --from=builder --chown=nextjs:nextjs /app/.next/static ./.next/static
COPY --from=builder --chown=nextjs:nextjs /app/public ./public

USER nextjs
EXPOSE 3000

# 컨테이너 밖에서 접근하려면 루프백이 아니라 모든 인터페이스에 바인딩해야 한다.
ENV HOSTNAME=0.0.0.0
ENV PORT=3000

CMD ["node", "server.js"]
