# IMP-004 · 대기열 정원 초과(over-admit) — 승격 로직 Redis Lua 원자화

- 슬라이스: `S03`
- 날짜: 2026-07-01
- 유형: 정량(동시성 테스트로 측정) — 동시성·정합성
- 관련 커밋: `219694b` (feat: 대기열 코어)
- 벤치 파일: [`benchmarks/queue-admission-before.json`](../../benchmarks/queue-admission-before.json)
  → [`benchmarks/queue-admission-after.json`](../../benchmarks/queue-admission-after.json)

> 흐름: 문제 정의 → 증상(수치) → 가설 → 도구로 검증 → 해결 → 재측정 → 한계.

## 1. 상황 (Context)
선착순 대기열은 이벤트당 **정원(capacity)** 만큼만 좌석선택(S04)으로 입장시킨다.
입장 승격은 "현재 입장 수를 보고, 여유가 있으면 대기열 head를 꺼내 입장 처리"한다.

> **범위 정직화**: 현재 승격은 단일 서버·**단일 스케줄러 스레드**만 호출하므로 사실상
> 직렬화되어 있어 over-admit이 라이브로 발생하지는 않는다. 이 IMP는 "지금 있는 버그 수정"이
> 아니라, **승격이 동시에 불릴 수 있는 조건**(다중 인스턴스 스케일아웃, 이벤트드리븐 승격)에서
> 정원 불변식이 깨지지 않도록 **선제 원자화**한 것이다. 상태가 Redis에 있으니 원자성도 Redis에
> 두는 게 맞고(계층 선택, [ADR-002]), 비용도 1 RTT라 사실상 무료다. 아래 수치는 그 조건을
> 테스트로 재현해 측정한 값이다.

## 2. 문제 정의 + 분류
승격을 여러 Redis 명령으로 나눠 하면 동시 실행 시 **정원 초과(over-admit)** 가 난다.

- 계층 분류: **동시성**(check-then-act 레이스).
- 왜: `GET admitcount` → 비교 → `ZPOPMIN` → `INCR`가 원자가 아니라, 여러 실행이
  같은 "여유 있음"을 보고 동시에 입장시킨다.

## 3. 증상 (측정된 증거)
- 측정: `QueueIntegrationTest` — capacity=3, 10스레드가 동시에 비원자 승격 시도(레이스 창 20ms).
- before:
  | 지표 | 값 |
  |------|----|
  | capacity | 3 |
  | 동시 시도 | 10 |
  | 실제 입장(admitted) | **10** |
  | **정원 초과(over-admit)** | **7** |

## 4. 가설 (검증 전)
- 여러 스레드가 `admitcount<capacity`를 동시에 통과 → 각자 pop+increment → 정원 초과.

## 5. 검증 (도구로 확인 → 확정 원인)
- 도구: 동시성 통합 테스트(Testcontainers Redis, CountDownLatch로 동시 시작).
- 결과: 비원자 경로에서 admitted가 capacity를 넘김(위 표) → check-then-act 레이스로 확정.

## 6. 조치 (해결)
승격 전체를 **Redis Lua 단일 스크립트**로 원자화:
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

## 7. 결과 (재측정 — 동일 조건)
| 지표 | before | after | 변화 |
|------|--------|-------|------|
| 실제 입장(admitted) | 10 | **3** | 정원 준수 |
| **정원 초과(over-admit)** | 7 | **0** | **-100%** |

→ 동시 승격에도 입장은 정확히 capacity(3)까지만. `원자_승격은_정원을_초과하지_않는다` 테스트로 보장.

## 8. 트레이드오프 / 한계 / 다음 개선
- **현재는 단일 워커라 직렬화** — over-admit은 다중 인스턴스/이벤트드리븐 승격에서 실재화됨.
  즉 이 원자화는 라이브 버그 픽스가 아니라 **확장 대비 + 불변식 보장**(무비용)이다.
- `synchronized` 등 JVM 락은 상태(Redis)와 다른 계층이라 부적합(단일 서버서만 우연히 맞음). [ADR-002]
- Lua는 단일 키 슬롯(같은 노드)에서 원자. Redis Cluster로 샤딩 시 관련 키를 **hash tag로 동일 슬롯**에 두어야 함(후속 고려).
- 슬롯 회수(입장 후 미진행 만료)도 같은 원자화 필요 → `RECLAIM_LUA`로 처리(회수 후 카운트 감소 원자).
- 승격 지연은 워커 주기(기본 1.5s)에 종속 — capacity/주기 튜닝 여지.

## 9. 배운 점 (면접 답변용)
> 대기열 정원 관리에서 "카운트 확인 → pop → 증가"를 나누면 승격이 동시에 실행될 때
> 정원을 초과할 수 있습니다. 지금은 단일 워커라 직렬화돼 라이브 버그는 아니지만, 다중
> 인스턴스로 확장하면 실재화되는 문제라 선제 대응했습니다. 핵심 판단은 **원자성을 어느
> 계층에 두느냐**였는데, 상태가 Redis에 있으니 JVM 락이 아니라 Redis Lua로 원자화하는 게
> 맞다고 봤습니다(JVM 락은 단일 서버서만 우연히 맞고 확장하면 깨짐). capacity=3에 10스레드로
> 초과를 재현한 뒤 Lua로 0을 만들고 테스트로 보장했습니다. "측정할 문제가 아니라 계층을
> 고르는 문제"로 접근한 게 핵심입니다.
