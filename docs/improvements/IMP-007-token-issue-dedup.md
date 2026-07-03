# IMP-007 · 1인1토큰 중복 발급 — user 키 SET NX 원자 예약

- 슬라이스: `S03`
- 날짜: 2026-07-02
- 유형: 정량(동시성 테스트로 측정) — 동시성·정합성
- 관련 커밋: `9ba8eb0` (feat: 1인1토큰 SET NX 원자화 + 이탈)
- 벤치 파일: [`benchmarks/queue-issue-dedup-before.json`](../../benchmarks/queue-issue-dedup-before.json)
  → [`benchmarks/queue-issue-dedup-after.json`](../../benchmarks/queue-issue-dedup-after.json)

> 흐름: 문제 정의 → 증상(수치) → 가설 → 도구로 검증 → 해결 → 재측정 → 한계.

## 1. 상황 (Context)
대기 진입(`POST /events/{id}/queue/token`)은 "한 사용자는 이벤트당 활성 토큰 1개"를 보장해야 한다.
초기 구현은 `GET user 키 → 없으면 토큰 생성 + SET`로 중복을 막으려 했다.

> **IMP-004와 대비**: IMP-004(정원 초과)는 승격이 단일 워커라 현재 직렬화돼 라이브가 아니었다.
> 이 문제는 **발급이 Tomcat 요청 스레드(다수)에서 처리**되므로, **단일 서버에서도 실재하는 레이스**다.

## 2. 문제 정의 + 분류
`GET → 없으면 SET`은 check-then-act라, 같은 유저의 **동시 요청(더블클릭/재시도)** 에 중복 토큰이 생긴다.

- 계층 분류: **동시성**(요청 스레드 간 check-then-act 레이스).
- 왜: 두 요청이 거의 동시에 `GET user = null`을 통과 → 각자 토큰 생성 + 대기열 등록.

## 3. 증상 (측정된 증거)
- 측정: `QueueIntegrationTest` — 같은 userId로 20스레드 동시 진입(레이스 창 20ms).
- before:
  | 지표 | 값 |
  |------|----|
  | 같은 유저 동시 요청 | 20 |
  | 생성된 토큰 | **20** |
  | 중복(초과 발급) | **19** |

## 4. 가설 (검증 전)
- `GET`과 `SET` 사이에 다른 요청이 끼어들어, 모두 "user 키 없음"을 보고 각자 발급한다.

## 5. 검증 (도구로 확인 → 확정 원인)
- 도구: 동시성 통합 테스트(Testcontainers Redis). 비원자 GET→SET을 20스레드로 재현.
- 결과: 대기열(wait ZSet)에 같은 유저 토큰이 여러 개 등록됨(> 1) → check-then-act 레이스 확정.

## 6. 조치 (해결)
user 키 예약을 **`SET NX`(원자)** 로 바꿔 발급 소유권을 한 요청만 갖게 한다:
```java
Boolean reserved = redis.opsForValue()
        .setIfAbsent(userKey, token, Duration.ofSeconds(tokenTtl)); // SET NX
if (!Boolean.TRUE.equals(reserved)) {
    return currentOrWaiting(get(userKey), eventId); // 기존 토큰 반환(1인1토큰)
}
// 예약 성공한 요청만 대기열 등록
```
경합에서 진 요청은 이미 예약된 기존 토큰을 반환한다.

## 7. 결과 (재측정 — 동일 조건)
| 지표 | before | after | 변화 |
|------|--------|-------|------|
| 생성된 토큰 | 20 | **1** | 1인1토큰 |
| 중복 발급 | 19 | **0** | **-100%** |

→ 같은 유저 20동시 요청에도 토큰은 하나. `같은_유저_동시발급도_토큰이_하나만_생긴다` 테스트로 보장.

## 8. 트레이드오프 / 한계 / 다음 개선
- **IMP-004와 달리 이건 단일 서버 실재 버그** — 발급은 요청 스레드(다수)라 원자화가 즉시 유효.
- 예약(SET NX)과 대기열 등록(ZADD/HSET) 사이 극미한 창에서, 경합에 진 요청이 아직 미등록
  토큰을 보게 될 수 있어 상태를 WAITING으로 낙관 처리(다음 폴링 2s로 정정). 실사용 영향 없음.
- 근본적으로도 SET NX는 Redis-native라 다중 인스턴스에서도 그대로 유효(계층 일치, [ADR-002]).

## 9. 배운 점 (면접 답변용)
> 대기열 발급의 1인1토큰을 `GET→없으면 SET`으로 막았는데, 발급은 요청 스레드가 여럿이라
> 같은 유저 더블클릭이면 중복 토큰이 생기는 단일 서버 실재 레이스였습니다. 같은 유저 20동시
> 요청에서 20개가 생기는 걸 재현한 뒤 user 키 예약을 `SET NX`로 원자화해 1개로 만들었고
> 테스트로 보장했습니다. 승격의 Lua 원자화(IMP-004)가 확장 대비였다면, 이건 지금 실제로 나는
> 버그를 상태가 있는 계층(Redis)에서 고친 사례입니다.
