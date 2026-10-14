# TS-051 · 실시간 랭킹 감쇠가 파드 수만큼 반복 적용됨 — ShedLock `lockAtLeastFor=PT0S`는 "주기당 한 번"을 보장하지 않는다

- 날짜: 2026-10-14
- 영역: 백엔드(스케줄러·Redis 랭킹)
- 결과: **재현(CI) → 해결**(경과 시간 기반 감쇠)
- 관련: [workload-responsibilities.md](../deployment/workload-responsibilities.md)(작업별 겹침 안전성), [operating-requirements.md](../deployment/operating-requirements.md) §5(별도 우선 과제), `docs/rules/domain/event.md`(감쇠 규칙)

## 증상
- 운영에서 관측한 증상은 **없다**. 워크로드 역할을 정리하다 코드에서 발견했다(workload-responsibilities.md 작성 중, 2026-10-14).
- 설계 의도는 "5분마다 ×0.8 → 반감기 ~15분"이다(`docs/rules/domain/event.md`). 그런데 감쇠 작업은 실행 **횟수**만큼 곱하고, 실행 횟수는 파드 수에 따라 늘 수 있었다.

## 재현
- 테스트 `RankingIntegrationTest.같은_주기에_감쇠가_두번_실행돼도_한번만_줄어든다`: hot 1.0에서 `decay()`를 연달아 두 번 부르고 0.8을 기대했다.
  - 락 이름이 같으므로, 같은 빈을 연달아 부르는 것이 "같은 주기 안 다른 파드의 틱"과 같은 상황이다.
- 기존 코드에서 CI 결과(PR #408 첫 커밋, Actions run 38565826144): **실패, 실제값 `0.6400000000000001`**. 344개 테스트 중 이 하나만 실패했다.
  ```
  RankingIntegrationTest > 같은_주기에_감쇠가_두번_실행돼도_한번만_줄어든다() FAILED
      Expecting actual:
        0.6400000000000001
      to be close to:
        0.8
  ```

## 근본 원인
- `RankingService.decay()`는 `@Scheduled(fixedRate = 5분)`이라 **파드마다** 자기 주기로 실행된다(사실 — 코드).
- 락은 `@SchedulerLock(name = "ranking-decay", lockAtMostFor = "PT4M", lockAtLeastFor = "PT0S")`다(사실 — 코드).
  - `lockAtLeastFor = PT0S`라 실행(Redis 명령 2개)이 끝나는 즉시 락이 풀린다.
  - 같은 5분 안에 다른 파드의 틱이 오면 락을 다시 잡고 또 실행한다.
  - 락이 막는 것은 "동시에 실행"이지 "주기당 한 번"이 아니다.
- 본문은 `ZUNIONSTORE hot 1 hot WEIGHTS 0.8`(곱셈)이다. 그래서 실행 횟수 k만큼 ×0.8ᵏ가 된다.
- 파드 5개(B 평시 구성: api 3 + 대기열 2)면 5분마다 ×0.8⁵ ≈ ×0.33이고, 반감기는 약 3분으로 줄어든다(계산 — 운영에서 측정하지 않았다).
- 전제가 된 서술도 사실과 달랐다. `SchedulerLockConfig`의 Javadoc은 "ShedLock으로 각 틱을 한 Pod만 실행하게 한다"고 적었다. 다른 작업(만료 sweep·대사·아웃박스)은 조건부 UPDATE·멱등이라 이 차이가 드러나지 않았고, 곱셈인 감쇠만 결과가 달라진다(workload-responsibilities.md의 겹침 안전성 표).

## 해결
- **횟수가 아니라 흐른 시간으로 곱한다.** `score × 0.8^(경과/5분)`.
  - 마지막 감쇠 시각을 Redis 키 `{event:views:hot}:decayed-at`에 두고, 읽기·곱하기·임계 미만 제거·시각 갱신을 Lua 한 번에 한다.
  - 해시 태그를 HOT 키 이름 전체로 둬서, Redis Cluster에서도 HOT 키와 같은 슬롯에 놓인다. 지금 ElastiCache는 단일 샤드다.
  - 같은 주기 안의 두 번째 실행은 경과가 0이라 아무것도 바꾸지 않는다.
  - 틱이 늦거나 빠져도 반감기는 시간 기준으로 유지된다.
- 시각은 Java `Clock`으로 넘긴다. 그래서 테스트에서 시계를 돌릴 수 있다(`MutableClock`).
  - 파드 시계가 어긋나 지금이 마지막 감쇠보다 과거로 보이면 아무것도 하지 않는다.
  - 기록이 없으면(첫 실행·키 유실) 한 주기만큼 감쇠한다. 기존 동작과 같다.
- 락 설정(`PT0S`)은 바꾸지 않았다.
  - `lockAtLeastFor`를 주기 가까이 늘리는 방법은 파드마다 주기의 위상이 달라 "주기당 정확히 한 번"을 만들지 못한다.
  - 결과가 실행 횟수에 기대지 않게 만드는 편이 위상과 무관하다.
- `SchedulerLockConfig` Javadoc을 실제 보장("동시에 실행되지 않는다")으로 정정했다.

## 검증
- `RankingIntegrationTest`(CI Testcontainers Redis):
  - 같은 주기 두 번 실행 → 0.8(재현 테스트 — 수정 뒤 통과 여부는 PR #408 CI 기록)
  - 10분 경과 → ×0.64
  - 시계가 뒤처져 보이면 불변
  - 기존 4개 테스트(누적·실시간 분리, 임계 미만 제거 등)는 주기마다 시계를 5분씩 돌리도록 바꿨다.
- 운영에서 반감기가 실제로 짧아졌었는지는 **측정하지 않았다**. 실시간 랭킹은 클러스터를 측정 세션 동안만 띄워 사용자 관측이 없다.

## 재발 방지
- workload-responsibilities.md의 작업별 표에 "겹쳐 실행돼도 안전한가"를 적어 두었다. 이 사례로 감쇠 행을 "예(경과 시간 기반)"로 바꿨다.
- 새 `@SchedulerLock` 작업은 "주기당 한 번"을 전제하지 말고, 반복 실행돼도 결과가 같게(멱등·조건부·시간 기반) 만든다. 하네스 규칙으로 강제할 수 있는지(예: `lockAtLeastFor=PT0S`인 작업에 멱등 근거 주석을 요구)는 정하지 않았다 — 후속 검토.
