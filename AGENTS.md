# AGENTS.md — FlowTicket 하네스 규칙

선착순 티켓팅 시스템(FlowTicket). 에이전트가 이 문서를 항상 컨텍스트에 두고,
`docs/` 스펙대로 **기능 슬라이스 단위**로 구현한다.

---

## 1. 기술 스택 (확정 — 변경 금지)

### 백엔드 (`apps/api`)
- Java 17 (Temurin) / Spring Boot **3.3.x** / Gradle 8.x (Kotlin DSL)
- groupId: `com.flowticket`
- Spring Web / Security / Data JPA + **QueryDSL 5.x (jakarta)**
- 인증: **JWT(Access/Refresh) + OAuth2 소셜(카카오·네이버)**
- PostgreSQL 16 / Flyway / Redis 7.4(Lettuce) / Kafka(KRaft) + DLQ
- 테스트: JUnit5 + Testcontainers 1.20.x

### 프론트 (`apps/web`)
- Node 20 LTS / pnpm 9.x / Next.js **14.2.x (App Router)** / TS 5.5
- Tailwind 3.4 + shadcn/ui
- TanStack Query 5 + Zustand 4 / React Hook Form 7 + Zod 3
- **데이터는 처음부터 실제 Spring API 연동 (Mock 금지)**

### 외부 연동
- **KOPIS** 공연정보 OpenAPI → 백엔드 배치로 `events` 테이블에 동기화 (XML 파싱)
- 좌석·가격·재고 등 거래용 필드는 우리가 직접 채운다 (KOPIS엔 없음)

### 인프라
- Docker Compose v2 (postgres, redis, kafka, api) / k6 (부하테스트)

---

## 2. 작업 루프 (한 번에 슬라이스 1개)

1. `docs/screens/_index.md`에서 `status: todo`인 **첫 슬라이스** 선택
2. 슬라이스에 속한 화면 MD + 참조 이미지 + `docs/common/*` + **그 슬라이스의 도메인 규칙**
   (`docs/rules/domain/<area>.md`, 인덱스는 `docs/rules/domain-rules.md`) 읽기
   - 횡단 불변식(정합성/enum/SSE)은 `domain-rules.md`만 참조
3. **세로 슬라이스로 구현**: DB 스키마 → API → 프론트 화면 → 통합검증
4. 아래 DoD 통과 시 `_index.md`의 status를 `done`으로 변경
5. 실패 시 최대 3회 재시도 → 그래도 안 되면 `status: blocked` + 사유 기록

> "처음부터 실제 API" 원칙 때문에 작업 단위는 화면이 아니라 **기능 슬라이스**다.
> 한 화면만 따로 구현하지 말 것.

---

## 3. 완료 정의 (DoD) — 공통

- [ ] `apps/api`: `./gradlew build` 통과 (테스트 포함)
- [ ] `apps/web`: `pnpm typecheck` + `pnpm lint` 통과
- [ ] 화면 MD의 **모든 state**가 렌더됨
- [ ] 화면 MD 체크리스트 요소가 전부 존재
- [ ] 공통 헤더/레이아웃 재사용 (재구현 금지) — `common/layout.md`
- [ ] `common/design-system.md` 토큰만 사용 (색상/간격 하드코딩 금지)
- [ ] API는 `common/api-contract.md` 계약과 일치
- [ ] 프론트가 실제 API에 붙어 동작 (Mock 아님)

---

## 4. 명령어

```
# 하네스 (루트에서 단일 명령) — 최초 1회: npm run harness:install
npm run harness:check    # schema + meta + backend + frontend 전부
npm run harness:schema   # 계약 형식만
npm run harness:meta     # 하네스 자체 검증(위반 fixture 차단 확인)
npm run harness:backend  # 백엔드 계약/패턴 검사
npm run harness:frontend # 프론트 계약/계층 검사

# api
./gradlew build          # 빌드+테스트 (apps/api)

# web
pnpm dev / pnpm build / pnpm typecheck / pnpm lint   # apps/web

# infra
docker compose up -d     # postgres/redis/kafka 기동
k6 run infra/k6/<scenario>.js
```

---

## 5. 절대 규칙

- **같은 사실은 한 곳에만.** 공통 내용은 `common/` 참조, 복붙 금지.
- 진행상태는 `_index.md` 단 하나. 다른 상태 추적 파일 만들지 말 것.
- 스펙에 없는 화면/기능 임의 추가 금지.
- **시크릿(KOPIS 키, OAuth secret, JWT secret) 하드코딩·커밋 절대 금지.**
  값은 환경변수/`*-local.yml`(gitignore)로만 주입.
- 외부 API(KOPIS)를 프론트에서 직접 호출 금지. 항상 우리 백엔드 경유.
- 선착순 재고는 항상 **우리 DB/Redis 기준**으로 차감.

## 6. 동시성·위험 도메인 규칙

- **동시성 제어는 검증된 원자화 패턴만 유지 — Redisson 등 외부 분산 락 라이브러리 추가 금지.**
  - 대기열: Redis Lua(`DefaultRedisScript`) 원자 승격 — [[ADR-002]]
  - 좌석·주문: DB 조건부 UPDATE(`UPDATE ... WHERE status = ...`) — [[ADR-003]](재고)·[[ADR-006]](주문 전이)
  - `@Modifying(clearAutomatically)` 벌크 UPDATE **전에** 로드한 엔티티는 `saveAndFlush`로 확정([[TS-007]]·[[TS-010]]).
- **위험 도메인(대기열·좌석·결제/환불) 변경 시 "분석 먼저"**: 코드 수정 전, 해당 도메인 규칙
  (`docs/rules/domain/*`)과 관련 ADR을 읽고 **영향 범위·사이드 이펙트를 파악한 뒤 최소 범위로** 수정한다.
  (백엔드 통합테스트는 로컬 gradle 없음 → CI(Testcontainers)로 검증 — §4 참고.)
