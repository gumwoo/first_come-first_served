# FlowTicket Web(Next.js) 운영 이미지.
# 빌드 컨텍스트는 저장소 루트: `docker build -f apps/web/Dockerfile .`
#
# Next.js `output: "standalone"`은 실행에 필요한 의존성만 추려 번들을 만든다.
# 그래서 최종 이미지에는 node_modules 전체가 아니라 추려진 결과만 들어간다.

# ── 1단계: 빌드 ───────────────────────────────────────────────
FROM node:20-bookworm-slim AS builder
WORKDIR /app
RUN corepack enable

# 락파일 기준으로 먼저 설치해 소스만 바뀐 재빌드에서 설치 레이어가 캐시된다.
COPY apps/web/package.json apps/web/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile

COPY apps/web/ ./

# API 오리진은 빌드 시점에 정해진다. next.config의 rewrites는 standalone 번들로 구워지므로
# 런타임 환경변수로는 바뀌지 않는다(주입해도 빌드 때 값으로 프록시해 ECONNREFUSED가 난다).
# 그래서 build-arg로 받는다.
#  - 로컬 compose: http://api:8080 (컨테이너 네트워크 서비스명)
#  - K8s: http://flowticket-api (Service DNS). ALB는 web만 보고 /api·/oauth2는 이 rewrite가
#    프록시한다. Ingress가 API로 직결하면 ALB가 접두어를 안 떼어 Spring이 404를 낸다.
#    포트를 붙이지 않는다: Service는 80을 열고 targetPort 8080으로 넘긴다.
ARG API_ORIGIN=http://localhost:8080
ENV API_ORIGIN=$API_ORIGIN

# 토스 결제창(ADR-005)용 공개 클라이언트 키. NEXT_PUBLIC_* 는 브라우저 번들에 구워지므로
# 런타임 주입(k8s Secret 등)으로는 바뀌지 않아 여기서 받는다. 공개 키라 repository variable로 둔다.
#
# 비어 있으면 에러 없이 결제창 없는 confirm 경로로 빠진다(pay/page.tsx). 하네스 규칙 6)이 ARG 누락을 막는다.
ARG NEXT_PUBLIC_TOSS_CLIENT_KEY=
ENV NEXT_PUBLIC_TOSS_CLIENT_KEY=$NEXT_PUBLIC_TOSS_CLIENT_KEY

# 대기열 상태 폴링의 클라이언트 최소 간격(ms). 다음 조회는 서버 retryAfterMs와 이 값 중 큰 값 + jitter다(ADR-023 §2).
# 비어도 useQueue가 기본값(2000)을 쓴다. 번들에 구워지는 값이라 바꾸려면 이미지를 다시 빌드해야 한다.
ARG NEXT_PUBLIC_QUEUE_POLL_INTERVAL_MS=
ENV NEXT_PUBLIC_QUEUE_POLL_INTERVAL_MS=$NEXT_PUBLIC_QUEUE_POLL_INTERVAL_MS

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
