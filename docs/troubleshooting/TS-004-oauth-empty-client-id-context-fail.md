# TS-004 · 키 없는 환경에서 컨텍스트 부팅 실패 — OAuth2 client-id 빈 문자열

- 슬라이스: `S01`(인증)
- 날짜: 2026-06-29
- 유형: 설정/환경 — CI 부팅 실패
- 관련 커밋: `ecedb95` (fix: OAuth2 client-id 빈값 부팅 실패)
- 관련 문서: [TS-002](TS-002-local-redis-version-zpopmin.md)(환경 전제 계열)

> 순서: 증상 → 조사 → 근본 원인 → 해결 → 재발 방지.

## 1. 증상
CI(및 OAuth 키가 없는 로컬 dev)에서 `@SpringBootTest` **통합테스트 6건이 컨텍스트 로딩
단계에서 실패**. 애플리케이션이 아예 기동되지 않음.

## 2. 조사
- 스택은 `ApplicationContext` 초기화 실패 — 특정 테스트 로직이 아니라 **부팅 자체**가 깨짐.
- `application.yml`의 네이버 OAuth 설정:
  ```yaml
  client-id: ${NAVER_CLIENT_ID:}       # 미설정 시 빈 문자열
  client-secret: ${NAVER_CLIENT_SECRET:}
  ```
- CI엔 `NAVER_CLIENT_ID`가 없으니 placeholder가 **빈 문자열**로 해석됨.
- Spring Boot의 `OAuth2ClientProperties` 검증은 등록된 client에 대해 **client-id가
  비어있으면 안 됨**을 강제 → 부팅 시 validation 실패.

## 3. 근본 원인
**"미설정"과 "빈 값"을 구분하지 못한 placeholder 기본값.** `${VAR:}`는 변수가 없을 때
빈 문자열을 넣는데, OAuth2 client registration은 *존재하는 registration의 빈 client-id*를
설정 오류로 취급한다. 즉 "설정은 됐는데 값이 비었다"로 읽혀 부팅이 막힘.

## 4. 해결
- 기본값을 **비어있지 않은 sentinel**로:
  ```yaml
  client-id: ${NAVER_CLIENT_ID:not-configured}
  client-secret: ${NAVER_CLIENT_SECRET:not-configured}
  ```
- 실제 값은 **환경변수로만 주입**(비밀은 코드/설정에 하드코딩 금지). 미설정 시 부팅은
  성공하고 **네이버 로그인만 런타임에 실패**하도록 격리. 키 없이도 앱이 기동되는 DX 회복.

## 5. 재발 방지
- **선택적 외부 연동(OAuth/결제 등)의 설정 placeholder는 빈 문자열 기본값을 피하고
  non-empty sentinel을 사용.** 없는 키가 부팅 전체를 막지 않게.
- **CI는 실제 키 없이도 그린이어야 한다** — 비밀은 환경변수 주입, 코드/설정/채팅에 노출 금지.
- "특정 테스트가 아니라 통합테스트가 무더기로 깨짐" = **컨텍스트 부팅 실패**를 먼저 의심하고
  설정/환경부터 확인.

## 한계 / 남은 것
- `not-configured`인 채로 네이버 로그인 시도 시 런타임 에러가 나므로, 실제 운영에선 키
  주입이 전제. 미설정 상태를 UI에서 선제 차단하는 처리는 범위 밖(런타임 실패로 족함).
