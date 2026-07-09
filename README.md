# FlowTicket — 선착순 티켓팅 시스템

AI 협업 품질 관리를 위한 **검증 하네스** 위에서 구현하는 선착순 예매 시스템.
사람이든 AI 에이전트든, 만든 코드가 옳음을 **스스로 증명**하도록 계약·정적 가드·측정·E2E·CI를 엮었다.

> 📐 **이 프로젝트의 엔지니어링 철학과 검증 체계 전체 → [docs/HARNESS.md](docs/HARNESS.md)** (필독)

## 구조
```
AGENTS.md / CLAUDE.md  # AI 작업 진입점(짧은 지도) + 필수 작업 규칙
assets/screens/        # 화면 레퍼런스 이미지 (정답 기준)
contracts/             # 기계가 읽는 기대 계약 (enum/api/event/stack/layer/error)
harness/               # 계약 검사 스크립트 + 메타 fixture(24) — npm run harness:check
apps/
  api/                 # Spring Boot 3.3 (Java 17)
  web/                 # Next.js 14.2 (TypeScript)
e2e/                   # Playwright E2E (크리티컬 플로우) — npm run e2e
docs/
  HARNESS.md           # 검증 하네스 개요(허브)
  common/ rules/       # 디자인 시스템·레이아웃·도메인/BE/FE/API 룰
  decisions/ (ADR)     # 설계 결정
  improvements/ (IMP)  # 정량 개선(before/after 박제)
  troubleshooting/(TS) # 사건 회고(증상→근본원인→재발방지)
  testing/             # E2E 규칙
  screens/             # 슬라이스 작업 큐(_index) + 화면 스펙
.github/workflows/     # CI (하네스 → 백엔드/프론트 → E2E 게이팅)
```

## 스택
- **Backend**: Java 17 · Spring Boot 3.3 · JPA · PostgreSQL · Redis · Kafka
- **Frontend**: Next.js 14.2 · TypeScript · Tailwind · shadcn/ui · TanStack Query
- **Infra/Test**: Docker Compose · Testcontainers · Playwright · GitHub Actions CI

## 검증 하네스 (4겹 방어선)
1. **계약**(`contracts/`) = 단일 진실원 — 코드/타입/문서를 계약과 diff.
2. **정적 가드 + 메타테스트**(`harness/`) — 드리프트 차단. "하네스도 검증 대상"(위반 fixture 24).
3. **측정·결정·회고 규율** — 정량 개선 IMP / 설계 결정 ADR / 사건 회고 TS.
4. **E2E + CI 자가개선 루프** — 경계·UI 결함을 실제 브라우저로. 실패 시 머지 차단 + trace 업로드.

> 사람이 만든 코드든 AI가 만든 코드든, main 반영 전 금지 의존성·enum/타입 계약·API·이벤트 계약·
> 계층 경계·테스트·린트·빌드·**크리티컬 플로우 E2E**를 모두 통과해야 한다.
> 계약 변경은 항상 관련 코드/타입/테스트/문서를 같은 PR에서 함께 수정한다.

자세한 철학·조각 문서 링크·명령어는 → **[docs/HARNESS.md](docs/HARNESS.md)**
