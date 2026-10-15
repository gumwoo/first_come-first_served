# TS-052 · 사이트 경유 SSE 이벤트가 브라우저에 실시간으로 오지 않음 — Next 응답 압축(gzip)이 SSE 프레임을 버퍼링

- 날짜: 2026-10-15
- 영역: 프론트 서버(Next)·백엔드(SSE 응답 헤더)
- 결과: **원인 확인 → 수정**(SSE 응답에 `Cache-Control: no-cache, no-store, no-transform`) — 실제 사이트 재검증 대기(배포 뒤 측정 세션)
- 관련: [kafka-removal-design.md](../deployment/kafka-removal-design.md) §6(Kafka 없는 세션의 운영 검증), ADR-015(SSE 재연결·재조회), TS-012(초기 프레임)

## 증상
- Kafka 제거 뒤 운영 검증에서 발견했다(측정 세션 `20261015-nok`, 2026-10-15). **Kafka 제거로 생긴 결함이 아니다.** 아래 근본 원인은 알림 경로(Kafka든 Redis든)와 무관하게 Next 앞단에서 생기며, 이 검증이 처음으로 "브라우저가 이벤트 자체를 받았는가"를 실제 사이트에서 단언했기 때문에 드러났다.
- 운영 검증 시험 1(카드 결제 → 브라우저 EventSource가 `order.paid`를 받는가)이 실패했다: 15초 동안 `order.paid` 미수신.
  - 같은 묶음의 시험 2(SSE 차단 중 입금 → 재연결로 완료)·시험 3(SSE 차단 중 결제 → 새로고침으로 완료)은 통과했다. 둘 다 이벤트가 아니라 재조회로 화면을 따라잡는 경로다.
- 서버 쪽은 정상이었다(같은 시험의 관측):
  - 아웃박스 3행 모두 `PUBLISHED`, 생성→발행 0.14~0.5초.
  - 파드별 publish 1회, `no_receivers` 0, 구독 메시지 파싱 실패 로그 없음.

## 재현
- 진단을 두 번 했다. 매번 한 주문에 SSE를 두 경로로 동시에 열고 결제했다 — A+B는 주문 4, A+C는 주문 5(세션 전용 진단, 저장소 밖 `artifacts/loadtest/20261015-nok/sse-diag.json`·`sse-diag-identity.json`). 시각은 각 진단의 첫 연결 직전 기준이다.

  | 경로 | `content-encoding` | 결제 뒤 `event:order.paid` (실측) |
  |---|---|---|
  | A. 사이트 경유(ALB → Next rewrite → api), gzip 수락 — 두 진단 모두 | `gzip` | **약 12초 동안 0바이트**(`:open`도 안 옴, 두 번 모두) |
  | B. api 직접(`kubectl port-forward` → api Service), 주문 4 | 없음 | `:open` +0.48초, 결제 0.35초 뒤 도착 |
  | C. 사이트 경유, `Accept-Encoding: identity`, 주문 5 | 없음 | `:open` +0.64초, 결제 0.63초 뒤 도착 |

- A와 C는 같은 진단(주문 5)에서 경로가 같고 압축 수락 여부만 다르다. 그래서 원인을 "Next 앞단의 응답 압축"으로 좁혔다.
- 수정 전 코드에서의 자동 재현(PR #420, 테스트를 수정보다 먼저 올림):
  - run 38702913681(7fba737): `SseResponseHeaderIntegrationTest` 2개 실패(348개 중 이 둘만, `no-transform` 없음). E2E 2개는 헤더 단언에서 실패 — 받은 값 `no-cache, no-store, max-age=0, must-revalidate`.
  - E2E가 증상보다 원인(헤더)에서 먼저 멈췄으므로, 이벤트 수신을 먼저 단언하도록 순서를 바꿔 다시 돌렸다.
  - run 38703702268(09a8486): E2E 2개가 **증상에서** 실패 — Next 경유 EventSource가 결제 뒤 `order.paid`, 선점 뒤 `seat.held`를 10초 안에 받지 못했다(나머지 E2E 25개 통과). 실제 사이트에서 본 미수신이 CI(`next start`)에서도 재현된다.

## 조사
- 화면만 보면 정상이었다. 결제 화면은 결제 응답(`orderStatus: PAID`)을 받으면 SSE와 무관하게 직접 완료 화면으로 이동하고(`orders/[id]/pay/page.tsx`의 결제 핸들러), PENDING 주문은 5초 폴링·`onopen`·포커스 재조회로도 따라잡는다(사실 — 코드). 기존 E2E(`payment.spec.ts`, `sse-reconnect-resync.spec.ts`)는 화면 전이를 단언하므로 이 결함을 볼 수 없었다.
- Next 14.2.15 소스로 압축 동작을 확인했다(사실 — `node_modules/next`):
  - `server/lib/router-server.js`: `config.compress !== false`면 `compression()` 미들웨어를 만들고 **모든 요청에** 건다. rewrite로 외부(api)에 프록시하는 응답도 이 `res`를 지난다.
  - `compiled/compression`: `shouldTransform`이 응답의 `Cache-Control`에 `no-transform`이 있으면 압축하지 않는다(`nocompress("no transform")`).
  - 프록시는 `res.flush()`를 부르지 않으므로, 압축기는 작은 SSE 프레임을 내보낼 만큼 모일 때까지 쥐고 있는다(추론 — gzip 버퍼 동작. 관측 A의 0바이트와 일치한다).
- `next.config.mjs`에 `compress` 설정이 없다(기본 true). 문서·설정 어디에도 SSE와 압축의 관계에 대한 기록이 없었다.

## 근본 원인
- 브라우저 → ALB → Next(`/api/*` rewrite) → api 경로에서 Next가 SSE 응답을 gzip으로 압축하고, SSE 프레임이 압축 버퍼에 머문 채 브라우저에 닿지 않았다.
- api의 SSE 응답에는 `no-transform`이 없었다. Spring Security 기본 캐시 헤더 `no-cache, no-store, max-age=0, must-revalidate`만 붙어 Next를 거쳐 나갔다(사실 — 수정 전 CI의 E2E가 Next 경유 응답에서 이 값을 받았다, run 38702913681).

## 해결
- 두 SSE 엔드포인트(`/sse/orders/{id}`, `/sse/events/{id}/seats`) 응답에 `Cache-Control: no-cache, no-store, no-transform`을 단다(`global/sse/SseHeaders`).
  - `no-cache, no-store`는 수정 전 Security 기본값에서 가져와 유지한다. 컨트롤러가 `Cache-Control`을 먼저 달면 Security가 자기 캐시 헤더(`Pragma`·`Expires` 포함)를 쓰지 않으므로, 저장 금지를 여기서 다시 명시한다.
  - `no-transform`: 프록시가 본문을 바꾸지(압축하지) 말라는 표준 지시어(RFC 9111 §5.2.2.6). Next의 압축 미들웨어가 이를 보고 비켜 간다.
  - Spring Security의 캐시 헤더 작성기는 응답에 `Cache-Control`이 이미 있으면 덮어쓰지 않는다. 수정 후 통합 테스트가 필터 체인을 거친 실제 응답에서 `no-transform`을 확인했다(아래 검증).
- 고르지 않은 대안:
  - `next.config.mjs`의 `compress: false`: 사이트 전체(HTML·JS) 압축이 꺼진다. 영향 범위가 SSE보다 훨씬 넓다.
  - SSE 전용 프록시 경로(Next를 거치지 않는 ALB 경로 규칙): 경로 규칙·인증서·CORS·하네스 k8s 규칙을 함께 바꿔야 한다. A가 실패할 때 검토할 순서로 남겼다.

## 검증
- 수정 후 CI run 38704367491(0c7d0a0): 백엔드 전체(통합 테스트 포함) `BUILD SUCCESSFUL`, E2E 27개 전부 통과 — `sse-through-web.spec.ts` 주문 1.8s·좌석 0.99s(테스트 소요 시간, 이벤트 지연이 아님).
- 헤더 값을 `no-cache, no-store, no-transform`으로 바꾼 뒤 CI run 38706357072(79c31f9): 백엔드 전체 `BUILD SUCCESSFUL`(통합 테스트에 `no-store` 단언 추가), E2E 27개 전부 통과.
- `SseResponseHeaderIntegrationTest`(CI): 실제 HTTP 응답(필터 체인 전부 통과)에서 두 엔드포인트의 `Cache-Control`에 `no-transform`이 있고, 초기 프레임 `:open`이 흘러나온다.
- `e2e/tests/sse-through-web.spec.ts`(CI, `next start` = 압축 켜짐):
  - Next를 거친 응답에 `content-encoding`이 없고 `Cache-Control`에 `no-transform`이 있다.
  - 테스트가 직접 연 EventSource가 결제 직후 `order.paid`를, 선점 직후 `seat.held`를 받는다(화면 전이가 아니라 이벤트 수신을 단언).
- 실제 사이트 재검증(배포 뒤, 측정 세션): 응답 헤더, 사이트 경유 `order.paid` 도착 시간, 좌석 SSE·새로고침·재연결·파드 간 팬아웃 회귀. 결과는 세션 기록과 이 문서에 덧붙인다.

## 재발 방지
- E2E가 화면 전이가 아니라 **이벤트 수신 자체**를 Next 경유로 단언한다. 기존 두 엔드포인트에서 헤더가 빠지면 E2E와 통합 테스트가 잡는다.
- **새 SSE 엔드포인트의 헤더 누락은 잡지 못한다** — 두 테스트 모두 고정된 두 URL만 연다. 새 엔드포인트는 `SseHeaders.preventProxyBuffering`을 함께 호출해야 하며, 하네스 규칙으로 강제할지는 정하지 않았다(후속 검토).
