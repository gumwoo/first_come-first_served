# FlowTicket

동시 접속 상황의 정합성을 중심으로 설계한 선착순 공연 예매 시스템입니다. 공연 탐색부터 대기열, 좌석 선점, 주문·결제, 환불과 운영 모니터링까지 하나의 수직 슬라이스로 다룹니다.

선착순 예매는 **틀려도 조용히 틀린다**는 점이 어렵습니다. 좌석이 두 번 팔리거나 대기열 순번이 뒤집혀도 화면은 멀쩡해 보입니다. 그래서 이 저장소는 "만들었다"에서 멈추지 않고 **정말 그렇게 동작하는지 확인하는 방법**을 함께 남깁니다.

```
동시성 정합성   →  Redis Lua · 조건부 UPDATE · Outbox 로 코드에서 막는다
구조 드리프트   →  계약 + 하네스 정적 검사로 CI에서 막는다
운영 중 장애    →  실제로 주입하고 측정한다
```

### 눌러 보고 잰 것들

설정을 켜 두는 것과 그것이 동작함을 아는 것은 다릅니다. 아래는 전부 클러스터를 띄우고 실제로 장애를 넣어 측정한 기록입니다.

| 무엇을 | 어떻게 확인했나 |
|---|---|
| 롤링 배포 무중단 | 배포 중 부하를 걸어 5xx를 셌다 — **수정 전 4/4 실행에서 5xx 발생, 수정 후 2/2 실행 무결**(각 6,001건 중 0건) ([IMP-015](docs/improvements/IMP-015-rolling-zero-downtime.md), 원인 [TS-035](docs/troubleshooting/TS-035-rolling-deregistration-race.md)) |
| 노드 오토스케일 | HPA 상한을 넘겨 Pending을 만들었다 — **Pending 4개 → 노드 3→4, 110초** ([IMP-021](docs/improvements/IMP-021-cluster-autoscaler-node-scaling.md)) |
| DB·캐시 페일오버 | RDS·Redis를 강제 전환했다 — 파드 재시작 0, 그러나 **30초 대기 후 500이라는 결함**을 발견 ([TS-037](docs/troubleshooting/TS-037-rds-redis-failover-app-behavior.md)) |
| 그 결함의 개선 | 타임아웃을 바꿔 같은 장애를 재측정 — 대기 시간 총합 −70%, **대신 실패 건수 +121%** ([IMP-022](docs/improvements/IMP-022-rds-connection-timeout.md)) |

마지막 줄이 이 저장소의 태도를 잘 보여줍니다. **수치가 좋아진 쪽만 적지 않습니다.** IMP-022는 "개선"이 아니라 트레이드오프로 기록했고, 한 번만 측정한 값으로는 채택하지 않았습니다.

### 확인하지 못한 것도 적습니다

측정 기록에는 *"이건 재지 않았다"*, *"이건 추론이다"*가 함께 남습니다. 실제로 이 저장소에는 **잘못 쟀다가 정정한 기록**([TS-036](docs/troubleshooting/TS-036-measurement-tooling-false-failures.md)), **측정 도구가 조용히 틀려서 결론이 뒤집힐 뻔한 기록**, 스스로 만든 회귀를 되돌린 기록이 그대로 남아 있습니다. 결론보다 **그 결론을 어디까지 믿을 수 있는지**가 더 중요하다고 보기 때문입니다.

설계 근거와 대안은 [ADR 16편](docs/decisions/_index.md), 측정 기반 개선은 [IMP 22편](docs/improvements/_index.md), 장애·회고는 [TS 38편](docs/troubleshooting/_index.md)에 있습니다.

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
| 대기열 | 발급·순번 조회·입장 승격·만료 | Redis, Lua, SSE |
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
    API --> Redis["ElastiCache Redis 7.1"]
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
| Data & Messaging | PostgreSQL 16, Flyway, Redis (Lettuce) — 로컬·CI `7.4` / ElastiCache `7.1`, Kafka KRaft, DLQ |
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
