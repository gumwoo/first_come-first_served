# FlowTicket

동시 접속 상황의 정합성을 중심으로 설계한 선착순 공연 예매 시스템입니다. 공연 탐색부터 대기열, 좌석 선점, 주문·결제, 환불과 운영 모니터링까지 하나의 수직 슬라이스로 다룹니다.

FlowTicket은 화면을 먼저 연결하는 데서 멈추지 않고, **대기열 순서·좌석 재고·주문 상태 전이·이벤트 발행**이 경쟁 상황에서도 일관되게 유지되는지를 계약, 테스트, 정적 검사로 확인하는 데 초점을 둡니다.

## 서비스 흐름

```text
공연 탐색 → 대기열 진입 → 입장 허용 → 좌석 선점 → 주문 생성 → 결제 → 예매 내역 / 환불
```

운영자는 공연·좌석을 관리하고, 주문·DLQ·알림 상태를 확인합니다. 공연 기본 정보는 KOPIS OpenAPI를 백엔드 배치가 동기화하며, 거래에 필요한 좌석·가격·재고는 FlowTicket이 직접 관리합니다.

## 핵심 설계

- **대기열**: Redis Lua 스크립트로 토큰 발급·입장 승격을 원자적으로 처리합니다.
- **좌석과 주문**: PostgreSQL 조건부 `UPDATE`와 영향 행 수 검증으로 중복 선점·초과판매를 막습니다.
- **결제 상태 전이**: 주문·결제·좌석·hold 상태를 명시적으로 전이하고 멱등성 키를 사용합니다.
- **이벤트 전달**: 비즈니스 트랜잭션과 Outbox 기록을 함께 저장하고 Kafka 발행·DLQ 처리를 분리합니다.
- **실시간 반영**: 대기열·좌석·주문 상태는 SSE와 재조회 보정으로 사용자 화면에 반영합니다.
- **외부 데이터 분리**: KOPIS 호출은 요청 경로가 아닌 배치 동기화 경로에서 처리합니다.

설계 선택의 근거와 트레이드오프는 [ADR 문서](docs/decisions/_index.md), 실제 개선 측정은 [IMP 문서](docs/improvements/_index.md)에서 확인할 수 있습니다.

## 백엔드 아키텍처

```mermaid
flowchart LR
    Browser["Browser"] --> Web["Next.js Web"]
    Web -->|"/api proxy · SSE"| API["Spring Boot API"]

    API --> Auth["Auth\nJWT · Refresh Rotation · OAuth2"]
    API --> Event["Event\nSearch · KOPIS Sync"]
    API --> Queue["Queue\nRedis Lua"]
    API --> Booking["Seat · Order · Payment · Refund"]
    API --> Admin["Admin · Alert · DLQ"]

    Auth --> Postgres[("PostgreSQL")]
    Event --> Postgres
    Event --> Kopis["KOPIS OpenAPI"]
    Queue --> Redis[("Redis")]
    Booking --> Postgres
    Booking --> Redis
    Booking --> Outbox["Transactional Outbox"]
    Outbox --> Kafka[("Kafka")]
    Kafka --> Dlq["DLQ / Consumer"]
    Admin --> Postgres
    Admin --> Kafka
```

백엔드는 Controller → Service → Repository/Gateway 경계로 구성됩니다. 위험 도메인에서는 일반적인 엔티티 저장보다 상태 조건을 포함한 명시적 전이를 우선합니다.

| 영역 | 책임 | 주요 구성 |
|---|---|---|
| 인증 | 회원가입·로그인·토큰 재발급·소셜 로그인 | JWT Access/Refresh, Redis rotation, Kakao/Naver OAuth2 |
| 공연 | KOPIS 동기화·검색·상세·인기 지표 | 스케줄 기반 동기화 작업, PostgreSQL |
| 대기열 | 발급·순번 조회·입장 승격·만료 | Redis 7.4, Lua, SSE |
| 예매 | 좌석 hold·주문·결제·환불 | JPA, QueryDSL, 조건부 UPDATE, 멱등성 |
| 이벤트 | 주문 완료 이벤트와 실패 격리 | Transactional Outbox, Kafka, DLQ |
| 운영 | 공연·주문·DLQ·알림 관리 | Spring Security RBAC, 관리자 API |

## 인프라 아키텍처

```mermaid
flowchart TB
    Internet["Internet"] --> ALB["ALB / Ingress"]
    ALB --> Web["Next.js Pods"]
    Web --> API["Spring Boot API Pods"]

    API --> RDS["PostgreSQL 16\nPrivate RDS"]
    API --> Redis["Redis 7.4"]
    API --> Kafka["Kafka KRaft / Strimzi"]
    API --> KOPIS["KOPIS OpenAPI"]

    Terraform["Terraform"] -. 인프라 .-> EKS["AWS EKS"]

    Dev["git push"] --> CI["GitHub Actions"]
    CI -->|이미지 build/push| ECR["ECR"]
    CI -->|매니페스트 newTag 커밋| Git["Git (k8s/overlays)"]
    Git -->|pull 기반 동기화·selfHeal| Argo["Argo CD"]
    Argo -->|앱| EKS
    ECR -. image pull .-> EKS
```

배포는 **push가 아니라 pull**이다. CI는 이미지를 ECR에 올리고 매니페스트의 태그를 Git에
커밋하는 데서 멈추며(`.github/workflows/image.yml`), 클러스터에 적용하는 것은 Argo CD다.
`Terraform=인프라 / Argo CD=앱`으로 소유를 나눈다 — 근거는
[ADR-009](docs/decisions/ADR-009-gitops-cd-argocd.md).

- 로컬 개발·통합 환경은 Docker Compose로 PostgreSQL, Redis, Kafka, API를 구성합니다.
- 클러스터 구성은 Kubernetes manifests와 Terraform을 사용하며, API는 health probe, graceful shutdown, HPA/PDB 설정을 가집니다.
- 비밀값은 환경변수와 External Secrets 경로로 주입하며 코드에 저장하지 않습니다.
- 배포·운영 문서는 [docs/deployment](docs/deployment/_index.md)와 [infra](infra)에서 관리합니다.

## 기술 스택

| 영역 | 기술 |
|---|---|
| Backend | Java 17 (Temurin), Spring Boot 3.3.x, Gradle 8, Spring Web, Spring Security, Spring Data JPA, QueryDSL 5 (Jakarta) |
| Data & Messaging | PostgreSQL 16, Flyway, Redis 7.4 (Lettuce), Kafka KRaft, DLQ |
| Authentication | JWT Access/Refresh, OAuth2 Client, Kakao·Naver |
| Frontend | Node.js 20 LTS, pnpm 9, Next.js 14.2 App Router, TypeScript 5.5 |
| Frontend State & UI | Tailwind CSS 3.4, shadcn/ui, TanStack Query 5, Zustand 4, React Hook Form 7, Zod 3 |
| Infrastructure | Docker Compose v2, Kubernetes, Terraform, Argo CD, AWS EKS |
| Verification | JUnit 5, Testcontainers 1.20, Playwright, k6, GitHub Actions |

## 저장소 구조

```text
apps/
  api/                 Spring Boot API, Flyway migration, 도메인·인프라 코드
  web/                 Next.js App Router 사용자·운영 화면
assets/screens/        화면 레퍼런스 이미지
contracts/             enum, API, event, error, stack, layer 계약
docs/
  common/              공통 API·레이아웃·디자인 시스템
  rules/               도메인·백엔드·프론트 규칙
  screens/             기능 슬라이스와 화면 스펙
  decisions/           ADR 설계 결정
  improvements/        IMP 측정 기반 개선 기록
  troubleshooting/     TS 장애·회고 기록
  deployment/          배포·인프라 문서
  testing/             E2E·성능 검증 규칙
e2e/                   Playwright 크리티컬 플로우
harness/               계약·구조 드리프트 정적 검사
infra/                 Docker Compose, Terraform, k6
k8s/                   Kubernetes base, overlay, Kafka, monitoring, Argo CD
```

## 품질 검증 방식

하네스는 이 프로젝트의 기능 자체가 아니라, 기능 구현이 계약과 규칙에서 벗어나지 않는지 확인하는 안전망입니다.

```mermaid
flowchart LR
    Contract["contracts/"] --> Schema["Schema Check"]
    Contract --> Backend["Backend Check"]
    Contract --> Frontend["Frontend Check"]
    Rules["docs/rules/"] --> Docs["Docs / K8s Check"]
    Meta["Invalid Fixtures"] --> MetaTest["Meta Test"]
    Schema --> CI["GitHub Actions"]
    Backend --> CI
    Frontend --> CI
    Docs --> CI
    MetaTest --> CI
    E2E["Playwright E2E"] --> CI
```

검증은 다음 원칙을 따릅니다.

- 계약 파일에서 enum·API·이벤트·오류·계층 경계를 관리합니다.
- 하네스는 계약 형식, 백엔드·프론트 구조, Kubernetes·문서의 알려진 드리프트를 검사합니다.
- 메타테스트는 일부러 만든 위반 fixture가 실제로 차단되는지 확인합니다.
- 단위·통합 테스트는 동시성, 상태 전이, 인증, Outbox/Kafka 같은 런타임 정합성을 검증합니다.
- Playwright E2E와 k6는 사용자 흐름과 부하 상황을 확인합니다.

하네스의 범위, 명령, 검사 한계는 [docs/HARNESS.md](docs/HARNESS.md)에 정리되어 있습니다.

## 문서 지도

| 목적 | 문서 |
|---|---|
| 현재 기능 슬라이스·화면 상태 | [docs/screens/_index.md](docs/screens/_index.md) |
| 도메인 불변식 | [docs/rules/domain-rules.md](docs/rules/domain-rules.md) |
| API·이벤트·enum 계약 | [contracts](contracts) |
| 공통 API·레이아웃·디자인 | [docs/common](docs/common) |
| 설계 결정과 대안 | [docs/decisions](docs/decisions/_index.md) |
| 측정 기반 개선 기록 | [docs/improvements](docs/improvements/_index.md) |
| 장애 분석과 재발 방지 | [docs/troubleshooting](docs/troubleshooting/_index.md) |
| 배포·E2E·성능 검증 | [docs/deployment](docs/deployment/_index.md), [docs/testing](docs/testing/e2e-rules.md) |

## 작업 원칙

기능은 화면 하나가 아니라 **DB 스키마 → API → 프론트 화면 → 통합 검증**의 수직 슬라이스로 완성합니다. 위험 도메인인 대기열·좌석·결제·환불을 변경할 때는 해당 도메인 규칙과 ADR을 먼저 확인합니다.

상세 작업 규칙은 [AGENTS.md](AGENTS.md), 계약·하네스의 상세 설명은 [docs/HARNESS.md](docs/HARNESS.md)를 참고합니다.
