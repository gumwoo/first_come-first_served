# IMP-004 · 대기열 정원 초과(over-admit): 승격 로직 Redis Lua 원자화

- 슬라이스: `S03`
- 날짜: 2026-07-01
- 유형: 정량(동시성 테스트로 측정), 동시성·정합성
- 관련 커밋: `219694b` (feat: 대기열 코어)
- 벤치 파일: [`benchmarks/queue-admission-before.json`](../../benchmarks/queue-admission-before.json)
  → [`benchmarks/queue-admission-after.json`](../../benchmarks/queue-admission-after.json)

> 흐름: 문제 정의 → 증상(수치) → 가설 → 도구로 검증 → 해결 → 재측정 → 트레이드오프·다음 개선.

## 1. 상황 (Context)
선착순 대기열은 이벤트당 정원(capacity)만큼만 좌석선택(S04)으로 입장시킨다.
입장 승격은 "현재 입장 수를 보고, 여유가 있으면 대기열 head를 꺼내 입장 처리"한다.

> 적용 범위: 현재 승격은 단일 서버·단일 스케줄러 스레드만 호출하므로 직렬화되어 있다.
> 이 IMP는 승격이 동시에 불릴 수 있는 조건(다중 인스턴스 스케일아웃, 이벤트드리븐 승격)에서
> 정원 불변식이 깨지지 않도록 선제적으로 원자화한 것이다. 상태가 Redis에 있으니 원자성도 Redis에
> 둔다(계층 선택, [ADR-002]). 비용은 1 RTT다. 아래 수치는 그 조건을 테스트로 재현해 측정한 값이다.

## 2. 문제 정의 + 분류
승격을 여러 Redis 명령으로 나눠 하면 동시 실행 시 정원 초과(over-admit)가 난다.

- 계층 분류: 동시성(check-then-act 레이스).
- 왜: `GET admitcount` → 비교 → `ZPOPMIN` → `INCR`가 원자적이지 않아, 여러 실행이
  같은 "여유 있음"을 보고 동시에 입장시킨다.

## 3. 증상 (측정된 증거)
- 측정: `QueueIntegrationTest`, capacity=3, 10스레드가 동시에 비원자 승격 시도(레이스 창 20ms).
- before:
  | 지표 | 값 |
  |------|----|
  | capacity | 3 |
  | 동시 시도 | 10 |
  | 실제 입장(admitted) | 10 |
  | 정원 초과(over-admit) | 7 |

## 4. 가설 (검증 전)
- 여러 스레드가 `admitcount<capacity`를 동시에 통과 → 각자 pop+increment → 정원 초과.

## 5. 검증 (도구로 확인 → 확정 원인)
- 도구: 동시성 통합 테스트(Testcontainers Redis, CountDownLatch로 동시 시작).
- 결과: 비원자 경로에서 admitted가 capacity를 넘김(위 표) → check-then-act 레이스로 확정.

## 6. 조치 (해결)
승격 전체를 Redis Lua 단일 스크립트로 원자화했다.
```lua
local admitted = tonumber(redis.call('GET', KEYS[2]) or '0')
local free = tonumber(ARGV[1]) - admitted     -- 정원 - 현재입장
if free <= 0 then return {} end
local popped = redis.call('ZPOPMIN', KEYS[1], free)  -- 여유만큼만 head pop
local n = #popped / 2
if n > 0 then redis.call('INCRBY', KEYS[2], n) end   -- 그만큼만 카운트 증가
return popped
```
Redis가 스크립트를 단일 실행하므로 "확인~증가"가 쪼개지지 않는다. (IMP-001 RTR Lua와 같은 원자화 패턴 재사용.)

## 7. 결과 (재측정, 동일 조건)
| 지표 | before | after | 변화 |
|------|--------|-------|------|
| 실제 입장(admitted) | 10 | 3 | 정원 준수 |
| 정원 초과(over-admit) | 7 | 0 | -100% |

→ 동시 승격에도 입장은 정확히 capacity(3)까지만 이뤄진다. `원자_승격은_정원을_초과하지_않는다` 테스트로 보장.

## 8. 트레이드오프 / 다음 개선
- 현재는 단일 워커라 승격이 직렬화되어 있고, over-admit은 다중 인스턴스/이벤트드리븐 승격에서
  발생하는 조건이다. 이 원자화는 확장에 대비해 불변식을 미리 보장하는 조치이며 추가 비용이 거의 없다.
- `synchronized` 등 JVM 락은 상태(Redis)와 다른 계층이라 맞지 않는다(단일 서버에서만 성립). [ADR-002]
- Lua는 단일 키 슬롯(같은 노드)에서 원자적이다. Redis Cluster로 샤딩하면 관련 키를 hash tag로 동일 슬롯에 둔다(후속 고려).
- 슬롯 회수(입장 후 미진행 만료)도 같은 원자화가 필요해 `RECLAIM_LUA`로 처리했다(회수 후 카운트 감소 원자).
- 승격 지연은 워커 주기(기본 1.5s)에 종속된다. capacity/주기 튜닝 여지가 있다.

## 8-1. 운영 환경 실증 (2026-08-12 추가)

§3·§7 수치는 `capacity=3` · 10스레드 · 레이스 창 20ms의 통합테스트 결과다.
같은 불변식을 실제 클러스터에서 측정했다.

| | §3·§7 (통합테스트) | 8-1 (운영 환경) |
|---|---|---|
| 환경 | Testcontainers Redis, 단일 JVM | EKS, api 7파드 |
| capacity | 3 | 100 |
| 도착 | 10스레드(레이스 창 강제) | 3,000명, 실 API 경로 (정원의 30배) |
| over-admit | 0 | 관측된 모든 샘플에서 ≤ 100 (3회 독립 실행, 총 1,117샘플의 최대값이 100) |

Lua 원자화가 다중 파드에서도 정원 불변식을 유지하는 것을 운영 환경에서 확인했다.
상승 구간의 중간값(`admit=37`)도 1건 포착돼, 정원까지 차오른 뒤 그 위로 넘지 않는 모양이 관측됐다.
§8의 선제 대응 서술은 그대로이며, 근거가 합성 테스트에서 운영 환경 실측으로 넓어졌다.

같은 측정에서 순서 보존도 확인했다.
승격이 `ZPOPMIN`(최소 score)이므로 순서대로 뽑혔다면 `queue:wait`에 남은 score는 앞쪽만 빠진
연속 구간이어야 한다. 두 실행 모두 그랬다. run-2는 남은 2,800개가 201~3000,
run-3은 남은 2,900개가 101~3000으로 둘 다 구멍이 0이다.
종료 스냅샷에서 늦게 도착한 토큰이 먼저 빠져나간 흔적은 0건이다.

측정 조건: `admitcount`는 100~200ms 간격으로 샘플링했고, 순서 보존은 종료 스냅샷 기준으로 확인했다
(승격을 건별로 캡처하지는 않았다).

같은 측정에서 상태 가시성 문제도 발견했다. 승격 시 `ZPOPMIN`(Lua 안)과 `queue:admit:{token}`
생성(Lua 밖) 사이의 창에서 상태가 `EXPIRED`로 보였다. 정원·순서 불변식과는 별개의 문제다.
승격 커밋을 한 Lua로 모아 `admitExp` 등록까지 원자화해 해결했고, 이 IMP의 원자 경계를 한 단계 넓힌 셈이다
([TS-024](../troubleshooting/TS-024-queue-admit-visibility-gap.md)).

상세·원자료는 [`benchmarks/spike-queue/`](../../benchmarks/spike-queue/README.md).

## 9. 배운 점 (면접 답변용)
> 대기열 정원 관리에서 "카운트 확인 → pop → 증가"를 나누면 승격이 동시에 실행될 때
> 정원을 초과할 수 있습니다. 지금은 단일 워커라 직렬화돼 있지만, 다중
> 인스턴스로 확장하면 발생하는 문제라 선제 대응했습니다. 핵심 판단은 원자성을 어느
> 계층에 두느냐였습니다. 상태가 Redis에 있으니 Redis Lua로 원자화하는 게 맞다고 봤습니다
> (JVM 락은 단일 서버에서만 성립하고 확장하면 깨집니다). capacity=3에 10스레드로
> 초과를 재현한 뒤 Lua로 0을 만들고 테스트로 보장했습니다. 원자성을 둘 계층을 고르는
> 문제로 접근한 사례입니다.
