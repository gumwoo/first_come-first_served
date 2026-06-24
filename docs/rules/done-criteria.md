# Done Criteria (슬라이스 완료 기준)

슬라이스가 `done`이 되려면 아래를 **모두** 통과. (AGENTS DoD의 상세판)

## 공통
- [ ] 해당 슬라이스 화면 MD의 모든 state 구현
- [ ] 화면 MD DoD 체크리스트 요소 전부 존재
- [ ] 공통 헤더/레이아웃/디자인 토큰 사용(재구현·하드코딩 없음)

## 백엔드
- [ ] `./gradlew harnessCheck` 통과 (stack/enum/api/layer 계약)
- [ ] `./gradlew test` 통과 (단위 + 통합)
- [ ] enum/이벤트/endpoint 변경 시 `contracts/*` 동기 수정
- [ ] @Transactional 경계·생성자주입·공통 래퍼 준수

## 프론트엔드
- [ ] `pnpm harness` 통과 (types/events/api-path/stack 계약)
- [ ] `pnpm typecheck` + `pnpm lint` + `pnpm build` 통과
- [ ] FE 타입/이벤트 구독이 `contracts/*`와 일치
- [ ] 실제 BE API 연동 동작(Mock 아님)

## 계약 변경이 포함된 경우
- [ ] git-pr-rules.md의 "계약 변경 PR 체크리스트" 충족

## 정적으로 판별 불가 → 테스트로 위임
- 트랜잭션 원자성/동시성 정합성 → 통합·동시성 테스트
- 응답 의미/실제 동작 → 통합 테스트
- 성능 → performance-rules.md 기준 부하 테스트
