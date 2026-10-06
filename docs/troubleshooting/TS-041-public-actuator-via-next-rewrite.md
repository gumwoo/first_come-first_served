# TS-041 · 인터넷에서 `/api/actuator/prometheus`가 열려 있었다 — Next rewrite가 actuator까지 넘겼고, 경로 규칙으로는 인코딩 변형을 막지 못한다

- 슬라이스: S10(부하·확장) — 부하 측정 도구 검증(G1) 중 발견
- 날짜: 2026-10-06
- 유형: 보안 결함(정보 노출) — 신뢰 경계를 경로 규칙에 두었는데 그 전제가 틀렸다
- 관련: `SecurityConfig`, `next.config.mjs`, `k8s/base/api-deployment.yaml`, `servicemonitor-api.yaml`, [[ADR-024]], [[IMP-024]]
- 상태: **수정 중(두 릴리스)** — N: 앱(메인 포트 /livez·/readyz, 관리 포트 설정 자리) / N+1: 매니페스트(관리 포트 8081·probe·수집). 운영 반영 후 외부 404·내부 수집을 실측해 이 문서에 적는다

## 1. 증상 (실측, 2026-10-06 운영)

| 요청(인터넷에서) | 응답 |
|---|---|
| `GET https://flow-ticket.com/api/actuator/prometheus` | **200**, 약 145KB(메트릭 전체) |
| `GET https://flow-ticket.com/api/%61ctuator/prometheus` | **200**, 같은 메트릭 |
| `GET https://flow-ticket.com/api/actuator%2Fprometheus` | 400 |
| `GET https://flow-ticket.com/api/actuator/env` | 401 |

메트릭 본문에는 JVM·톰캣·DB 풀·대기열 지표가 그대로 나온다. 시크릿이 실리는 `env`·`configprops`는 인증이 필요해 401이었다.

## 2. 왜 열려 있었나

`SecurityConfig`는 Prometheus가 인증 없이 긁도록 `/actuator/prometheus` 한 경로만 permitAll로 두고, 그 근거를 이렇게 적었다.

> 이 경로는 인터넷에서 도달할 수 없다: ALB Ingress는 web Service만 보고, Next rewrites도 /api·/oauth2만 프록시한다.

앞 절반은 맞다. 뒤 절반이 틀렸다. `next.config.mjs`의 rewrite는 `/api/:path*` → `${API_ORIGIN}/:path*`라서 `/api` 아래의 **모든 경로**를
접두어만 떼어 api로 넘긴다. `/api/actuator/prometheus`는 `/actuator/prometheus`가 되어 api에 닿고, permitAll이라 그대로 응답한다.
하네스 k8s 규칙 3은 "Ingress에 `/actuator` 경로가 있는가"만 보므로 Next를 거치는 이 경로를 잡지 못했다.

## 3. 경로 규칙으로 막으면 안 되는 이유 (로컬 실측)

Next rewrite에서 actuator를 빼는 수정(`source: "/api/:path((?!actuator(?:/|$)).*)"`)을 로컬 Next 14.2.15 dev 서버와 경로를 그대로 돌려주는 가짜 백엔드로 시험했다.

| 요청 | Next의 처리 |
|---|---|
| `/api/actuator/prometheus`, `/api/Actuator/...`, `/api/./actuator/...`, `/api/x/../actuator/...`, `/api/%2e/actuator/...` | 404(막힘) |
| `/api/%61ctuator/prometheus` | **백엔드로 전달**(`/%61ctuator/prometheus`) |

Next는 인코딩된 경로를 그대로 비교·전달하고, api(톰캣)는 `%61`을 `a`로 디코딩해 `/actuator/prometheus`로 처리한다. 운영에서 같은 요청이 200인 것(§1)이 그 결과다.
경로 문자열로 신뢰 경계를 그으면 프록시와 서버의 정규화 차이가 곧 우회로가 된다.

## 4. 조치

**두 릴리스로 나눈다(Expand-Contract).** 새 probe(`/livez`)가 옛 이미지보다 먼저 적용되면 새 파드가 Ready가 안 돼 롤링이 멈춘다.
N에서 앱이 두 방식을 모두 받게 만들고(경로 추가, 포트는 기본값 = 메인 포트 — 동작 변화 없음) 운영에 반영한 뒤 N+1에서 매니페스트를 바꾼다.
**N이 없애는 것은 "새 probe + 옛 이미지" 조합 하나뿐이다.** 관리 포트 값을 고정 이름 ConfigMap에 넣으면 "옛 템플릿 파드 + 새 ConfigMap"(관리 포트 8081을 읽었는데
probe는 옛 `/actuator/health/*`:8080 → 404 → 재시작 반복)과 "새 템플릿 + 옛 ConfigMap"(probe는 통과하지만 8080에 actuator가 남아 조용히 열린 채)이
이미지와 무관하게 생길 수 있다 — 롤아웃 중 옛 ReplicaSet 컨테이너 재시작·HPA 스케일·부분 동기화에서. 이 조합은 N+1 설계로 막는다(포트 값을 probe와 같은
Deployment 템플릿에 두는 등, N+1 PR에서 결정). ServiceMonitor는 ArgoCD 추적 밖(`kubectl apply -k k8s/monitoring`)이라 N+1 절차에 적용·수집 확인을 넣는다.

**actuator를 관리 포트(8081)로 분리한다.** 인터넷에서 닿는 경로는 전부 메인 포트(8080, Service 80)로만 가므로 경로 표기와 무관하게 actuator에 닿지 않는다.

- `application.yml`: `management.server.port: ${MANAGEMENT_SERVER_PORT:}` — 비우면 메인 포트와 같다(로컬·CI는 지금과 동일). 운영 ConfigMap에 `MANAGEMENT_SERVER_PORT: "8081"`.
- api Service에 `management`(8081) 포트, ServiceMonitor는 그 포트로 `/actuator/prometheus`를 긁는다. 장애 시험 스크립트도 8081로.
- **probe는 관리 포트로 옮기지 않는다.** 관리 포트는 별도 톰캣이라 8080 요청 스레드가 포화돼도 성공한다 — readiness는 요청을 못 받는 파드에
  트래픽을 계속 보내고, liveness는 멈춘 메인 포트를 놓친다([[IMP-024]]에서 겪은 상황). 대신 `management.endpoint.health.probes.add-additional-paths: true`로
  같은 health 그룹을 메인 포트의 `/livez`·`/readyz`에 내고 probe가 그 경로를 쓴다. 두 경로는 `SecurityConfig`에서 permitAll(상태 문자열만 응답).
- (N+1) 하네스 k8s 규칙 ⑩: ConfigMap에 `MANAGEMENT_SERVER_PORT`가 없거나 8080이면 실패, api probe가 관리 포트나 `/actuator` 경로를 보면 실패(위반 fixture 2개).
- 통합 테스트(`ManagementPortIntegrationTest`, N): 메인 포트 `/actuator/prometheus`·`/%61ctuator/prometheus` 404, `/livez`·`/readyz` 200,
  관리 포트 `/actuator/prometheus` 200, 관리 포트 `/actuator/metrics` 401. `@SpringBootTest`는 메트릭 내보내기를 기본으로 꺼서
  `@AutoConfigureObservability` 없이는 prometheus 엔드포인트가 없어 "메인 포트 404"가 분리와 무관하게 통과한다(첫 CI에서 관리 포트 200 단언이 404로 실패해 드러남).

## 5. 검증

- [ ] N: CI 통합 테스트(로컬 gradle 없음), 운영 반영 뒤 파드에서 `/livez`·`/readyz` 200(기존 probe는 그대로)
- [ ] N+1 운영 반영 뒤: 외부 `/api/actuator/prometheus`·`/api/%61ctuator/prometheus` 404, Prometheus의 api 타깃 up·지표 수집 유지, 파드 probe 정상

## 6. 남는 것

- `/api/livez`·`/api/readyz`는 Next를 거쳐 외부에서 닿는다 — UP/DOWN만 돌려주며, 지금까지 외부에서 닿던 `/api/actuator/health/*`와 같은 수준이다.
- 교훈: 신뢰 경계는 경로 문자열이 아니라 네트워크 경계(포트·서비스)로 긋는다. 프록시가 있는 구조에서 "이 경로는 밖에서 안 닿는다"는 주장은
  프록시 규칙과 서버 정규화를 함께 확인해야 한다.
