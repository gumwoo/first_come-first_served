# 검증 하네스 — 우리 방식의 Harness Engineering

> 살아있는 문서(계속 갱신). FlowTicket이 "사람이든 AI 에이전트든, 만든 코드가 옳음을
> **스스로 증명**하도록" 만든 검증 체계 전체를 한 장으로 조망한다.
> 조각 문서로 가는 링크 허브이자, 신입/에이전트의 온보딩 컨텍스트.

---

## 0. 한 줄
**"에이전트가 실수할 때마다, 그 실수가 다시는 반복되지 않도록 모델 밖(코드/계약/테스트/CI)에
환경을 구축한다."** — 이 프로젝트의 모든 문서·가드·테스트는 그 목적을 위해 존재한다.

## 1. 왜 (문제 정의)
AI 에이전트로 코드 변경 속도가 폭발적으로 빨라지면, **검증이 병목**이 된다. 모델은 충분히
똑똑하지만 자기 코드에 관대하고, "작업 완료"라고 보고한 것이 실제로는 경계에서 깨져 있곤 한다.
그래서 필요한 건 더 똑똑한 모델이 아니라, **에이전트가 스스로 검증할 수 있는 독립적 환경(하네스)**이다.

핵심 원칙 3가지:
- **계약이 먼저다.** 기대(enum/API/이벤트/스택/계층/에러)를 기계가 읽는 단일 진실원으로 고정한다.
- **의도적 naive → 재현·측정 → 개선 → 재측정.** 성능/정합성은 말이 아니라 수치로 박제한다.
- **이해는 위임 못 한다.** CI가 다 초록이어도 "이 변경이 사업적으로 맞나"는 사람이 판단한다.

## 2. 테스팅 트로피 — 각 계층이 뭘 잡나
```
        ▲  E2E (Playwright)      실제 브라우저: FE↔BE 경계·SSR·SSE·라우팅·UI 상태
       ▲▲  Integration           Testcontainers: 백엔드 동시성·상태기계(실 DB/Redis)
      ▲▲▲  Unit                  함수/컴포넌트 단위
     ▲▲▲▲  Static                계약 하네스 + tsc + ESLint (가장 싸고 빠름)
```
버그의 성격에 따라 잡히는 계층이 다르다. 우리가 실제로 겪은 결함들은 대부분 **경계 결함**이라,
Static/Unit/Integration이 다 초록인데도 E2E 계층(사람의 수동 클릭)에서만 드러났다 →
[TS-009](troubleshooting/TS-009-why-e2e-adoption.md).

## 3. 4겹 방어선

### ① 계약 (단일 진실원) — [`contracts/`](../contracts)
기대를 YAML로 고정한다. 코드/타입/문서는 이 계약과 **diff**된다.
- [`enums.yaml`](../contracts/enums.yaml) 도메인 enum + 상태 전이
- [`api.yaml`](../contracts/api.yaml) 엔드포인트(슬라이스 태깅)
- [`events.yaml`](../contracts/events.yaml) 발행/구독 이벤트 + **`implemented`**(실제 발행 강제)
- [`error-codes.yaml`](../contracts/error-codes.yaml) · [`allowed-stack.yaml`](../contracts/allowed-stack.yaml) · [`layer-rules.yaml`](../contracts/layer-rules.yaml)

### ② 정적 가드 + 메타테스트 — [`harness/`](../harness)
계약을 코드와 대조해 드리프트를 막는다. `npm run harness:check`.
- 백엔드: 금지 의존성, enum ordinal 금지, @Enumerated STRING, 생성자 주입, 시큐리티 개방 금지,
  Flyway 버전 유일, **구현 이벤트 발행 검증**(계약 선언 후 미구현 차단) 등.
- 프론트: allowed-stack, enum 미러, 이벤트 구독 일치, 계층 import 경계, 죽은 API 경로 등.
- **메타테스트(24)**: "하네스도 검증 대상." 위반 fixture를 넣어 하네스가 **실제로 실패를 잡는지**
  검사한다. 통과해버리면(false negative) 메타테스트가 실패한다.

> 철학: **"계약에 있으나 미구현 = 허용"**(슬라이스 완료 시 채움). 그래서 전체가 아니라 `implemented`·
> done-criteria로 "이미 완성된 것"만 강제한다.

### ③ 측정·결정·회고 규율 — `docs/`
- **정량 개선 → IMP** ([_index](improvements/_index.md)): 의도적 naive를 만들어 수치로 재현하고,
  개선 후 재측정해 `benchmarks/`에 before/after를 박제. 예: 초과판매 19→0(IMP-003), 정원 초과 7→0(IMP-004).
- **설계 결정 → ADR** ([_index](decisions/_index.md)): 원자성 계층 선택, 가격 단일 소스 등 되돌아볼 근거.
- **사건 회고 → TS** ([_index](troubleshooting/_index.md)): 실사용/수동 검증에서 발견한 결함을
  증상→조사→근본원인→해결→재발방지로 기록(TS-001~009). "어떻게 원인을 좁혔는가"가 핵심.

### ④ E2E + CI 자가개선 루프 — [`e2e/`](../e2e), [`ci.yml`](../.github/workflows/ci.yml)
경계·UI 상태 결함을 실제 브라우저로 잡는다. 규칙: [e2e-rules.md](testing/e2e-rules.md).
- 크리티컬 플로우만(전 페이지 X). **A기법 상태 빌드업**("순간이동": 실제 API로 로그인·대기열을
  미리 세팅하고 브라우저는 검증 화면부터 시작).
- **과거 손으로 잡던 버그를 회귀 테스트로 박제**(선점취소/1인초과/매진 = TS-006·007 회귀).
- flaky 방지 3원칙: 하드 대기 금지(조건 대기)·시맨틱 셀렉터·번인(`--repeat-each`). 공유 좌석 경합은
  직렬 실행으로 제거.
- CI가 PR마다 **풀스택**(postgres16+redis7.4 → 백엔드 → 좌석 시드 → 프론트 → Playwright)을 띄워
  실행하고, 실패 시 **머지 차단 + trace(CCTV) 아티팩트 업로드**.

## 4. 자가 개선 루프 (회로가 닫힌다)
```
  ┌─ 행동 전: 가이드 ─────────────┐        ┌─ 행동 후: 센서 ──────────────┐
  │ 계약 · CLAUDE.md · 명세형     │        │ Playwright 실행 + trace(CCTV) │
  │ 테스트(읽으면 명세서)         │        │ · 스크린샷/네트워크/DOM       │
  └───────────────┬───────────────┘        └───────────────┬───────────────┘
                  ▼                                          ▼
        에이전트가 코드 작성  ──────────►  CI에서 E2E 실행  ──── 실패 ────┐
                  ▲                                                        │
                  └──────── trace 파싱해 원인 분석·수정 ◄──────────────────┘
```
LLM을 평가자로 쓰면 비싸고 비결정적이지만, 그 자리에 **결정론적이고 저렴한 Playwright**를 넣어
회로를 닫았다. 실제로 이 루프를 돌린 사례: 브라우저 미설치 벽 → 온보딩 스크립트화, 병렬 경합
SOLD_OUT 오탐 → CCTV로 원인 특정 → 직렬 격리(그 과정에서 초과판매 방어가 작동함도 재확인).

## 5. 사람의 역할 (위임 못 하는 것)
1. **루프 설계**: 에이전트가 유기적으로 도는 파이프라인(가드·센서·CI)을 만든다.
2. **맥락 제공**: 도메인 지식·제약을 계약/문서/하네스로 인코딩해 에이전트에 컨텍스트를 준다.
3. **하네스 진화**: 모델이 좋아질수록 더 고차원 도메인 판단을 내리며 하네스를 함께 키운다.

> "사고는 위임할 수 있어도 이해는 위임할 수 없다." CI 초록은 "안 깨졌다"일 뿐, "사업적으로 옳다"는
> 최종 책임은 사람의 몫이다.

## 6. 링크 허브 (조각 문서)
| 조각 | 위치 |
|------|------|
| 작업 진입점(에이전트) | [AGENTS.md](../AGENTS.md) · [CLAUDE.md](../CLAUDE.md) |
| 계약(단일 진실원) | [contracts/](../contracts) |
| 정적 가드 + 메타테스트 | [harness/](../harness) · `npm run harness:check` |
| 정량 개선(IMP) | [docs/improvements/_index](improvements/_index.md) |
| 설계 결정(ADR) | [docs/decisions/_index](decisions/_index.md) |
| 사건 회고(TS) | [docs/troubleshooting/_index](troubleshooting/_index.md) |
| E2E 규칙 | [docs/testing/e2e-rules.md](testing/e2e-rules.md) |
| E2E 테스트·CI | [e2e/](../e2e) · [.github/workflows/ci.yml](../.github/workflows/ci.yml) |
| 슬라이스 작업 큐 | [docs/screens/_index](screens/_index.md) |

## 7. 명령어 한눈에
```bash
npm run harness:check          # 계약/정적 가드 (meta 24 + backend + frontend)
corepack pnpm@9.12.0 --dir apps/web typecheck | lint | build
npm run e2e                    # E2E (로컬 dev 스택 필요) — 게이트는 CI가 자동 수행
npm run e2e:burn               # 번인(--repeat-each=20) — flaky 사전 차단
```
