# Git / PR Rules

## 1. 브랜치
- main 보호. 작업은 `feat/Sxx-...`, `fix/...`, `chore/...`
- main 직접 push 금지. 모든 변경은 PR + CI 통과 후 머지.

## 2. 커밋
- 한 커밋 = 한 의도. 메시지: `타입: 요약` (feat/fix/refactor/test/docs/chore)
- 슬라이스 단위 작업은 `feat(S05): ...`처럼 슬라이스 표기.

## 3. 계약 변경 PR 체크리스트 ★ (false positive 방지의 핵심)
enum/이벤트/endpoint/응답형을 바꾸는 PR은 **다음을 한 PR에 함께** 포함:
- [ ] BE 코드(enum/controller/event publisher)
- [ ] `contracts/{enums,events,api,error-codes}.yaml`
- [ ] FE 타입(`types/contracts.ts`) + 이벤트 구독 목록
- [ ] FE api path fragment
- [ ] 통합/단위 테스트
- [ ] 관련 문서(api-contract.md / domain-rules.md)

→ 하나라도 빠지면 하네스가 불일치로 실패. 즉, 하네스는 변경을 막는 게
   아니라 **계약 변경을 명시적으로 드러내고 함께 고치게** 만드는 장치.

## 4. CI 게이트 (머지 조건)
- backend: harnessCheck → test
- frontend: harness → lint → build
- meta: harness-self-test(위반 fixture 전부 실패 확인)
- 전부 green이어야 머지 가능(사람/AI 동일 기준).

## 5. 실패 시 — 피드백 환류
실패하면 원인을 분류해 feedback-routing.md에 따라 승격 위치 결정.
일회성 실수는 승격하지 않음(하네스 비대화 방지).
