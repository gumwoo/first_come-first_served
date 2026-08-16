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
#  - K8s: http://flowticket-api (Service DNS). ALB는 web만 보고 /api·/oauth2는 **이 rewrite가**
#    프록시한다 — Ingress가 API로 직결하면 ALB가 접두어를 안 떼어 Spring이 404를 낸다.
#    포트를 붙이지 않는다: Service는 80을 열고 targetPort 8080으로 넘긴다.
ARG API_ORIGIN=http://localhost:8080
ENV API_ORIGIN=$API_ORIGIN

# 토스 결제창(ADR-005의 Toss 테스트 어댑터)을 띄우는 **공개** 클라이언트 키.
# NEXT_PUBLIC_* 는 브라우저 번들에 문자열로 구워지므로 런타임 주입이 원리적으로 불가능하다 —
# k8s Secret에 넣어도 아무 효과가 없다. 그래서 여기서 받는다.
#
# 비어 있으면 조용히 기능이 바뀐다: pay/page.tsx가 `TOSS_CLIENT_KEY`가 있을 때만 결제창
# 인증 경로를 타고, 없으면 결제창 없이 confirm으로 빠진다 — 에러가 아니라 **다른 흐름**이라
# 배포 후에도 눈치채기 어렵다. 하네스 규칙 ⑥이 ARG 누락을 막는다.
#
# 공개 키라 시크릿이 아니다(브라우저에 그대로 노출되는 것이 정상). GitHub Actions
# repository variable로 주입한다.
ARG NEXT_PUBLIC_TOSS_CLIENT_KEY=
ENV NEXT_PUBLIC_TOSS_CLIENT_KEY=$NEXT_PUBLIC_TOSS_CLIENT_KEY

# 대기열 상태 폴링 주기(ms). SSE가 아예 열리지 않는 환경을 위한 최종 안전망이라
# 환경별 조정이 필요할 수 있어 운영 파라미터로 둔다(ADR-015 ③).
#
# 여기서 받는 이유는 위 토스 키와 같다 — NEXT_PUBLIC_* 는 번들에 구워져 런타임 주입이
# 불가능하다. 다만 이 값은 **비어도 안전하다**: useQueue가 `|| 2000`으로 기본값을 쓴다.
# 그래도 ARG를 두는 것은, 나중에 운영값을 바꿔야 할 때 이미지 재빌드 경로가 여기뿐이기
# 때문이다(k8s Secret/ConfigMap으로는 못 바꾼다).
#
# ⚠️ E2E는 600000(10분)을 준다. 그건 **운영 후보값이 아니라** 폴링을 테스트 시간 밖으로
# 밀어내 SSE 재연결 복구(onopen)를 격리 검증하기 위한 값이다.
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
