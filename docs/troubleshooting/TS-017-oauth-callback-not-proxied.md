# TS-017 · 소셜 로그인 콜백이 404 — 시작 경로만 프록시하고 콜백을 빠뜨렸다

- 슬라이스: S01(인증) · S09(배포)
- 날짜: 2026-08-08
- 유형: 배포 환경 결함(설정) — **로컬에서는 재현되지 않음**
- 관련: `apps/web/next.config.mjs`, [[TS-016]](같은 배포에서 나온 기동 결함)
- 상태: 해결

## 1. 증상

EKS 배포 후 인증 흐름을 점검하다 발견했다. 회원가입·로그인·`/me`는 정상인데
**소셜 로그인만** 콜백에서 404가 났다.

```
GET /oauth2/authorization/naver  → 302 ✅
  redirect_uri=https://flow-ticket.com/login/oauth2/code/naver

GET /login/oauth2/code/naver     → 404 ❌
  본문: <!DOCTYPE html><html lang="ko">... /_next/static/...
```

**본문이 Next.js 페이지였다.** Spring이 받지 못했다는 뜻이다.

## 2. 근본 원인

브라우저는 항상 같은 오리진으로 요청하고 Next가 백엔드로 프록시한다.

```js
// next.config.mjs
{ source: "/api/:path*",    destination: `${apiOrigin}/:path*` },
{ source: "/oauth2/:path*", destination: `${apiOrigin}/oauth2/:path*` },
// /login/oauth2/code/* 가 없다
```

**시작 경로는 프록시하는데 콜백 경로는 안 했다.** Spring Security의 리다이렉션 엔드포인트
기본값은 `/login/oauth2/code/{registrationId}`이고, 이건 `/oauth2/*`와 접두어가 다르다.

제공자(네이버·카카오)가 사용자를 돌려보내면 Next.js가 받아 404를 낸다.

## 3. 왜 로컬에서는 안 드러났나

**로컬은 Next(3000)와 Spring(8080)이 같은 머신에서 돈다.** 개발 중에는 브라우저가 8080으로
직접 접근하거나, 콘솔에 등록된 콜백 URL이 `http://localhost:8080/...`이라 **Next를 거치지 않았다.**

즉 **프록시 경로 누락이 로컬에서는 증상을 만들지 않는다.** 배포해서 "브라우저 → 한 오리진 →
Next 프록시" 구조가 되어야 비로소 드러난다.

> E2E도 이걸 못 잡는다. CI E2E는 `pnpm start`로 Next를 띄우고 `API_ORIGIN` 기본값
> (`localhost:8080`)으로 프록시하지만, **소셜 로그인을 테스트하지 않는다**(외부 제공자 의존).

## 4. 함께 발견한 것 — 성공 후 갈 곳이 localhost

```yaml
oauth:
  success-redirect: ${OAUTH_SUCCESS_REDIRECT:http://localhost:3000}
  failure-redirect: ${OAUTH_FAILURE_REDIRECT:http://localhost:3000/login?error=oauth}
```

**ConfigMap에 둘 다 없었다.** 콜백을 고쳐도 인증에 성공하고 나서 `http://localhost:3000`으로
보내진다 — 이것도 **배포에서만 드러나는** 유형이다.

## 5. 해결

```js
// 소셜 로그인 콜백 — 제공자가 사용자를 여기로 돌려보낸다.
{ source: "/login/oauth2/:path*", destination: `${apiOrigin}/login/oauth2/:path*` },
```
```yaml
OAUTH_SUCCESS_REDIRECT: "https://flow-ticket.com"
OAUTH_FAILURE_REDIRECT: "https://flow-ticket.com/login?error=oauth"
```

`/login` 페이지가 따로 있지만 **더 구체적인 경로가 우선**하므로 충돌하지 않는다(빌드로 확인).

제공자 콘솔에도 콜백 URL이 등록돼야 한다 — 저장소 밖의 조건이라 코드로 강제할 수 없다.

## 6. 재발 방지 — 하네스 규칙 8

**"백엔드가 노출하고 브라우저가 전체 이동으로 도달하는 경로"** 는 XHR과 달리 눈에 잘 띄지 않는다.
`application.yml`에 OAuth2 클라이언트 등록이 있으면 `next.config.mjs`가 **시작·콜백 양쪽**을
프록시하는지 검사한다.

```
OAuth 콜백 경로 프록시 누락: apps/web/next.config.mjs에 "/login/oauth2/:path*" rewrite가 없다.
브라우저가 전체 이동으로 도달하는 경로라 Next가 받아 404가 된다(TS-017)
```

콜백 rewrite를 임시로 지워 **실제 검출을 확인**했다.

## 7. 교훈

1. **프록시는 "쌍"으로 생각해야 한다.** 시작이 있으면 콜백이 있다. 한쪽만 뚫으면 흐름이 끊긴다.
2. **로컬에서 재현되지 않는 결함이 있다.** 이 프로젝트에서 두 번째다 —
   [[TS-016]](Kafka 기동 의존)도 같은 성격이었고, 둘 다 **첫 배포 당일**에 드러났다.
   "로컬에서 되니까 된다"가 성립하지 않는 영역이 분명히 있다.
3. **기본값이 `localhost`인 설정은 배포에서 조용히 틀린다.** 에러가 아니라 엉뚱한 곳으로
   보내지므로 더 늦게 발견된다. `REDIS_SSL_ENABLED`·`API_ORIGIN`에 이어 세 번째 사례다.
