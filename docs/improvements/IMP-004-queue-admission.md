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
승격은 워커가 주기적으로 돌고, 다중 인스턴스/스레드에서 동시에 실행될 수 있다.

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
- Lua는 단일 키 슬롯(같은 노드)에서 원자. Redis Cluster로 샤딩 시 관련 키를 **hash tag로 동일 슬롯**에 두어야 함(후속 고려).
- 슬롯 회수(입장 후 미진행 만료)도 같은 원자화 필요 → `RECLAIM_LUA`로 처리(회수 후 카운트 감소 원자).
- 승격 지연은 워커 주기(기본 1.5s)에 종속 — capacity/주기 튜닝 여지.

## 9. 배운 점 (면접 답변용)
> 대기열 정원 관리를 "카운트 확인 → 대기열 pop → 증가"로 나눴더니 동시 승격에서
> 정원을 초과하는 레이스가 있었습니다. capacity=3에 10스레드를 동시에 던져 초과를
> 재현한 뒤, 확인부터 증가까지를 Redis Lua 단일 스크립트로 원자화해 초과를 0으로
> 만들었고 동시성 테스트로 보장했습니다. 선착순의 핵심인 "정원 정확성"을 애플리케이션
> if문이 아니라 원자 연산으로 보장한 사례입니다.
