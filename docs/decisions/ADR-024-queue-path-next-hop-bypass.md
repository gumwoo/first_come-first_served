# ADR-024 · 대기열 진입·상태 경로만 Next 홉을 건너뛴다 — ALB URL rewrite로 좁게

- 상태: **Proposed** — 측정(IMP)으로 효과를 확인한 뒤 Accepted
- 날짜: 2026-10-06
- 슬라이스: S10(부하·확장)
- 관련: [[ADR-023]] §1 (b)·§실행 순서 ① 결과(이 ADR의 근거), `k8s/base/ingress.yaml`(ALB는 web만 — 이 ADR이 예외를 하나 둔다),
  `apps/web/next.config.mjs`(rewrite), 하네스 k8s 규칙 2·3

## 맥락

[[ADR-023]] 실행 순서 ①의 분리 측정(사전 등록 판정, 실측)에서 대기열 진입 POST 1건의 CPU 중 web(Next 프록시)이 **44.1%**(100/s)·48.6%(1,000/s)를 썼다.
web을 거치면 api 쪽 비용도 1,000/s에서 1.078 → 1.483 CPU-ms/req로 늘었다(원인 미확인). 공개 ALB 경로는 진단 NLB 경로와 ±2% 안에서 같았다(100/s에서만 비교).
1,000/s에서 먼저 한계에 다가간 것도 web 파드가 있는 노드였다(15초 평균 0.75~0.82, api 직접 경로는 0.35 이하).

지금 구조는 "ALB는 web만 보고, `/api/*`는 Next rewrite가 `/api`를 떼어 api로 넘긴다"이다(`ingress.yaml` 주석). ALB에서 `/api`를 api로 바로 보내지 못한 이유는
ALB가 경로 접두어를 떼지 못했기 때문이다 — Spring 매핑에는 `/api`가 없다(`@PostMapping("/events/{id}/queue/token")`).

2025년 10월부터 ALB가 URL rewrite(정규식 치환)를 지원하고, AWS Load Balancer Controller는 v2.14.1(2025-10-17 릴리스)부터 `alb.ingress.kubernetes.io/transforms.<서비스>` 주석으로 이를 건다
([공지](https://aws.amazon.com/about-aws/whats-new/2025/10/application-load-balancer-url-header-rewrite/), [컨트롤러 문서](https://kubernetes-sigs.github.io/aws-load-balancer-controller/latest/guide/tasks/url_rewrite/)).
이 클러스터의 컨트롤러는 v3.5.0이다(2026-10-06 `public.ecr.aws/eks/aws-load-balancer-controller:v3.5.0` 이미지로 확인).

## 결정

대기열의 **진입·상태 조회 두 경로만** ALB가 api로 직접 보낸다. 나머지 `/api/*`·`/oauth2/*`는 지금처럼 Next를 거친다.

| 브라우저 경로 | ALB → | api가 받는 경로 |
|---|---|---|
| `POST /api/events/{id}/queue/token` | api Service (URL rewrite) | `/events/{id}/queue/token` |
| `GET /api/queue/status?token=…` | api Service (URL rewrite) | `/queue/status?token=…` |
| 그 밖의 `/api/*`, `/oauth2/*`, `/login/oauth2/*`, 페이지 | web (지금 그대로) | — |

- Ingress 경로 규칙(같은 Ingress·같은 host). 컨트롤러는 규칙을 Exact → Prefix(긴 것부터) → ImplementationSpecific 순으로 정렬하고, 와일드카드는
  ImplementationSpecific에서만 허용한다(컨트롤러 ingress spec 문서). 지금의 기본 경로 `/`(Prefix)를 그대로 두면 Prefix가 와일드카드 규칙보다 앞에 놓여
  진입 POST가 계속 web으로 간다(오류 없이). 그래서:

  | 순서 | path | pathType | 백엔드 |
  |---|---|---|---|
  | 1 | `/api/queue/status` | Exact | api |
  | 2 | `/api/events/*/queue/token` | ImplementationSpecific | api |
  | 3 | `/*` (지금의 `/` Prefix를 바꿈) | ImplementationSpecific | web |

  2와 3은 같은 ImplementationSpecific이라 매니페스트에 적은 순서가 우선순위가 된다(컨트롤러 url_rewrite 문서의 예시와 같은 배치). 기본 경로의 표기가
  바뀌므로 적용 PR에서 페이지·`/api/*`·OAuth 경로가 모두 web으로 가는지 확인한다.
- rewrite는 api Service 이름으로 거는 transform 하나이고(주석 키가 서비스 이름 기준), ALB 규칙당 URL rewrite는 하나라 두 경로를 한 정규식으로 쓴다.
  예: `^/api/(events/[0-9]+/queue/token.*|queue/status.*)$` → `/$1`(ALB 정규식은 lookahead·backreference 미지원). 끝을 `.*`로 둔 것은 정규식이 쿼리 문자열까지
  대상으로 하는지 문서에 명시가 없어서다 — 쿼리가 포함돼도 맞고, 안 맞으면 원래 경로가 그대로 전달돼 api가 `/api/queue/status`를 404로 낸다(적용 PR의 확인 항목).
- 경로 규칙이 두 경로에만 맞으므로 `/api/actuator/*` 등 다른 `/api/*` 요청은 이 규칙으로 api에 닿지 않는다(지금처럼 web으로 간다).
- Ingress 경로로는 HTTP 메서드를 구분하지 않는다. Spring에서 두 경로는 각각 한 메서드로만 매핑돼 있어(진입 POST, 상태 GET) 다른 메서드는 405다.
- 브라우저는 같은 오리진(`flow-ticket.com/api/...`)을 계속 쓴다 — 프론트 코드·CORS·쿠키 변경 없음. 진입은 Bearer 헤더 인증이라 쿠키에 기대지 않는다.
- 상태 조회 경로는 [[ADR-023]] §2(폴링 주 경로)에서 지속 부하가 되므로 처음부터 같이 둔다.
- 이탈(`DELETE /api/queue/token`)·SSE(`/api/sse/queue/*`)는 이번 범위가 아니다(트래픽이 작거나 [[ADR-023]] §2에서 정리 대상).

### 하네스

- 규칙 2("공개 Ingress가 API Service로 직결")를 "**허용 목록 경로 + 해당 transforms 주석이 있을 때만** api 직결 허용"으로 바꾼다. 허용 목록 밖 경로나
  주석 없는 직결은 지금처럼 실패. 위반 fixture(허용 밖 경로 직결, 주석 누락)를 `harness/fixtures/violations/`에 만들고 메타테스트에 등록한다.
- 규칙 3(`/actuator` 공개 경로 금지)은 그대로 둔다.

## 효과 확인 (IMP로)

[[ADR-023]] 실행 순서 ①과 같은 조건·같은 측정식으로 before(공개 ALB → web → api, 100/s는 측정 완료)와 after(공개 ALB → api)를 잰다.
합격식은 ADR-023 단계 2 사전 등록의 IMP 합격식이다 — "대상 컨테이너 CPU-ms/req 중앙값 개선 ≥ T, T = max(10%, 2 × (before 3회 최대 − 최소) ÷ before 중앙값)",
p95 중앙값 비열화(≤ 1.10배), 오류·dropped 비증가, 정합성 위반 0. 이 변경에서는 "대상 컨테이너"를 **web + api 합**으로 해석한다 — 우회는 web 몫을 없애고
api 몫을 바꾸는 변경이라 한쪽만 보면 판단할 수 없다. 이 해석은 ① 결과를 본 뒤 정한 것이다(사전 등록 문구에는 컨테이너 지정이 없다).
1,000/s의 before는 공개 ALB 경로로 다시 잰다(지금 1,000/s 값은 진단 NLB 경로뿐이다).

## 결과(예상 — 측정 전)

- 진입·상태 요청은 web을 거치지 않는다 — 측정에서 web이 쓰던 몫이 빠질 것으로 본다(예측; 실제 감소량은 IMP에서 잰다).
- ALB 대상 그룹이 둘(web, api)이 된다. Ingress 전역 `healthcheck-path: /`를 api에 그대로 쓰면 api의 `/`는 인증 필요라 401 → unhealthy가 된다.
  헬스체크 주석은 Service 주석이 Ingress 주석보다 우선하므로 `flowticket-api` Service에 `alb.ingress.kubernetes.io/healthcheck-path: /actuator/health/readiness`(permitAll)를 둔다.
- api 파드가 ALB 대상이 된다. 대상 해제 지연(`deregistration_delay` 10초, Ingress 전역)과 preStop이 api에도 적용되는지, 스케일아웃 시 ALB 등록·헬스체크만큼
  새 파드가 늦게 트래픽을 받는다는 점을 적용 PR에서 확인한다. Pod readiness gate는 쓰지 않는다(flowticket 네임스페이스에 주입 라벨 없음, 2026-10-06 확인) —
  그래서 api 파드의 Ready가 ALB 상태에 묶이지 않는다.
- api가 인터넷에 직접 노출되는 범위가 생긴다 — 두 경로뿐이고 둘 다 기존에도 Next를 통해 같은 요청이 그대로 전달되던 경로다. 진입은 Bearer 인증,
  상태 조회는 permitAll이고 추측하기 어려운 토큰(UUID)을 쿼리로 받는 방식이다(지금과 같음).

## 검토했으나 채택하지 않은 대안

- **ALB `/api/*` 전체를 api로 + rewrite** — actuator(`/api/actuator/*`)·관리자 경로까지 한 번에 열린다. 필요한 것은 대기열 두 경로뿐이다.
- **Spring에 `/api` 접두어를 같이 매핑**(컨트롤러 이중 매핑 또는 접두어 제거 필터) — ALB 규칙과 앱 코드가 함께 바뀌고, 접두어가 있는 경로·없는 경로가 앱 안에 공존해
  보안 설정(`SecurityConfig` 경로 규칙)을 두 벌로 관리해야 한다.
- **api 전용 서브도메인**(`api.flow-ticket.com`) — 교차 오리진이 되어 진입 POST마다 CORS preflight(OPTIONS)가 붙을 수 있고(캐시 전), 쿠키·OAuth 설정이 바뀐다.
- **web 파드만 늘림** — 측정상 web 몫(약 1.4~1.6 CPU-ms/req)이 그대로 남아 필요 코어가 줄지 않는다. 사전 확장([[ADR-023]] §1 (c))과는 별개로 볼 문제다.

## 한계 / 미확정

- web 경유 시 api 비용이 늘어나는 원인(연결 재사용, 헤더 처리 등)은 확인하지 않았다. 우회로 그 몫도 빠질지는 IMP에서 잰다.
- ALB URL rewrite가 퍼센트 인코딩을 디코딩하는 문제가 보고됐다가 2026-05-04 닫혔다(ALB 데이터플레인 쪽 수정, 제보자 확인 —
  [#4579](https://github.com/kubernetes-sigs/aws-load-balancer-controller/issues/4579)). 두 경로의 가변부는 숫자 id와 UUID라 인코딩할 문자가 없다 —
  적용 PR에서 실제 요청으로 확인한다. 쿼리 문자열 보존도 같은 PR에서 확인한다.
- 공개 경로 `/api/actuator/prometheus`가 지금 Next rewrite를 통해 외부에 200을 낸다(2026-10-06 확인). 이 ADR과 별개로 고친다 — 이 ADR의 규칙은 그 경로를 새로 열지 않는다.
