# FlowTicket — 선착순 티켓팅 시스템

AI 협업 품질 관리를 위한 **CI 기반 개발 하네스** 위에서 구현하는 선착순 예매 시스템.

## 구조
```
AGENTS.md          # AI 작업 진입점 (짧은 지도 + 문서 라우팅)
assets/screens/    # 화면 레퍼런스 이미지 27장 (정답 기준)
contracts/         # 기계가 읽는 기대 계약 (enum/api/event/stack/layer/error)
docs/
  common/          # 디자인 시스템 / 레이아웃 / API 계약(산문)
  rules/           # 도메인·BE·FE·API·디자인·완료기준·성능·PR·환류 룰
  screens/         # 슬라이스 작업 큐(_index) + 화면 스펙 27
apps/              # web(Next.js) + api(Spring Boot)   ← 예정
harness/           # 계약 검사 스크립트 + 메타 fixture   ← 예정
```

## 스택
- **Backend**: Java 17 · Spring Boot 3.3 · JPA+QueryDSL · PostgreSQL · Redis · Kafka
- **Frontend**: Next.js 14.2 · TypeScript · Tailwind · shadcn/ui · TanStack Query
- **Infra/Test**: Docker Compose · k6 · GitHub Actions CI

## 하네스 원칙
사람이 만든 코드든 AI가 만든 코드든, main 반영 전 금지 의존성·enum/타입 계약·
API endpoint·이벤트 계약·계층 경계·테스트·린트·빌드를 **모두 통과**해야 한다.
계약 변경은 항상 관련 코드/타입/테스트/문서를 같은 PR에서 함께 수정한다.
