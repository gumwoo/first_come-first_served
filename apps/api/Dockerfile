# FlowTicket API 운영 이미지.
# 빌드 컨텍스트는 저장소 루트(.dockerignore를 한 곳에서 관리) — `docker build -f apps/api/Dockerfile .`
#
# 멀티스테이지: 빌드에는 Gradle·소스·APT가 필요하지만 실행에는 JRE와 jar만 있으면 된다.
# 두 단계를 나누면 최종 이미지에 빌드 도구가 남지 않아 크기와 공격면이 함께 줄어든다.

# ── 1단계: 빌드 ───────────────────────────────────────────────
# 래퍼 스크립트(gradlew)가 저장소에 없어 CI와 동일하게 gradle 이미지를 쓴다.
# 버전은 gradle-wrapper.properties(8.10.2)와 맞춘다 — 빌드 재현성.
FROM gradle:8.10.2-jdk17 AS builder

# gradle 이미지는 비루트 `gradle` 유저로 동작한다 → 소유권을 맞춰 복사해야 빌드 산출물을 쓸 수 있다.
# 의존성 정의를 먼저 복사해, 소스만 바뀐 재빌드에서 의존성 해석 레이어가 캐시되게 한다.
WORKDIR /home/gradle/src
COPY --chown=gradle:gradle apps/api/settings.gradle.kts apps/api/build.gradle.kts ./
RUN gradle dependencies --no-daemon --quiet || true

COPY --chown=gradle:gradle apps/api/src ./src
# 테스트는 CI(Testcontainers)가 담당한다 — 이미지 빌드가 도커 인 도커를 요구하지 않도록 제외.
RUN gradle bootJar --no-daemon -x test

# ── 2단계: 실행 ───────────────────────────────────────────────
FROM eclipse-temurin:17-jre AS runner
WORKDIR /app

# curl은 헬스체크·운영 디버깅용(런타임 이미지에 기본 포함되지 않는다).
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# 루트로 돌리지 않는다(컨테이너 침해 시 권한 최소화).
RUN useradd --system --uid 10001 --create-home flowticket

COPY --from=builder --chown=flowticket:flowticket /home/gradle/src/build/libs/*.jar app.jar

USER flowticket
EXPOSE 8080

# 컨테이너 메모리 한도를 JVM이 인식하게 한다(K8s limits와 어긋난 힙으로 OOMKill 되는 것 방지).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"

# K8s는 probe로 상태를 보지만, compose·단독 실행에서도 준비 상태를 알 수 있게 헬스체크를 둔다.
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=10 \
  CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

# graceful shutdown이 동작하려면 JVM이 PID 1로 SIGTERM을 직접 받아야 한다(exec).
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
