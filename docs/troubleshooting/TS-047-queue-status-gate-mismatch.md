# TS-047 · 회수된 입장 토큰이 상태 조회에서는 ADMITTED, 좌석 선점에서는 거부됐다 — 판정 규칙이 두 갈래였다

- 슬라이스: S03(대기열)·S04(좌석) — [loadtest-100k-plan §3.3](../testing/loadtest-100k-plan.md)에 알려진 한계로 적혀 있던 것을 외부 리뷰(2026-10-10)가 다시 지적, 코드로 확인
- 날짜: 2026-10-10
- 유형: 상태 일관성 결함(사용자 경험) — 정원 초과는 아니다(게이트는 이미 거부)
- 관련: `QueueService` `STATUS_LUA`·`STATUS_POLL_LUA`·`admittedNow`·`isAdmitted`, [[TS-024]](승격 직후 admit 키 없는 창), [[TS-043]]
- 상태: **해결** — 세 판정을 한 규칙으로, 통합 테스트(CI)

## 1. 증상 (코드 근거)
입장 여부를 세 곳에서 판정했는데 규칙이 달랐다.
- 상태 조회(`STATUS_LUA`·`STATUS_POLL_LUA`)와 재진입 판정(`admittedNow`): **admit 키가 있으면 바로 ADMITTED**, 없으면 admitExp 점수 > 지금.
- 좌석 게이트(`isAdmitted`): admitExp가 권위 — 점수 > 지금이면 통과, admitExp에 없으면(회수·이탈) admit 키가 있어도 **거부**,
  점수가 지났지만 회수 전이면 admit 키로 통과.

회수(`RECLAIM_LUA`)는 admitExp만 지우고 admit 키는 TTL까지 남긴다. 그 사이 상태 조회는 ADMITTED인데 좌석 선점은 `QUEUE_NOT_ADMITTED`였다.
화면은 입장으로 보이는데 좌석을 못 잡는다. 같은 이유로 재진입 판정은 회수된 토큰을 "살아 있는 입장 토큰"으로 돌려줬다.
창은 회수 직후 admit 키가 남은 동안이다(admit 키 TTL은 승격 뒤 시작해 점수보다 조금 늦게 끝난다 — 코드 주석, 길이는 재지 않았다).

## 2. 해결
세 판정을 게이트의 규칙으로 맞췄다 — **admitExp에 있고**, 입장창이 남았거나(점수 > 지금) 아직 회수 전이라 admit 키가 남아 있으면 입장.
- 게이트가 이미 허용하던 "입장창은 지났지만 회수 전" 구간(다음 회수 틱과 admit 키 만료 중 먼저 오는 시점까지 — admit 키는 점수 직후 곧 끝난다, 코드로 따진 추론이고 재지 않았다)은 그대로 둔다 — 정책을 바꾸지 않고 상태 조회만 게이트에 맞췄다.
- 승격 직후 admit 키가 아직 없는 창(TS-024)은 점수로 입장이다(예전과 같다).
- 상태 조회 Lua는 `ZSCORE`를 먼저 보고 admit 키는 점수가 지났을 때만 본다. 대기 중 토큰은 예전(EXISTS·ZSCORE·ZRANK)보다 명령이 하나 적다.

## 3. 검증
- 통합 테스트(Testcontainers Redis, CI) `QueueAdmitVisibilityIntegrationTest` 신규 4건: 회수 뒤 admit 키가 남아도 상태 EXPIRED·게이트 거부 /
  그 토큰으로 재진입하면 새 토큰 / 입장창이 지났지만 회수 전이면 상태·게이트 모두 입장 / 회수 전인데 admit 키도 없으면 모두 거부.
  기존 "승격 직후 admit 키가 없어도 ADMITTED"(TS-024) 테스트는 그대로 둔다.
