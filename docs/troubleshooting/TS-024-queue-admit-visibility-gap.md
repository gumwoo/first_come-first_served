# TS-024 · 승격 커밋이 Lua 밖으로 샌다 — 상태 가시성 창과 정원 영구 누수

- 슬라이스: S10(부하 시험) — 발견 경로는 [`benchmarks/spike-queue/`](../../benchmarks/spike-queue/README.md)
- 날짜: 2026-08-12
- 유형: 결함 발견 → 수정 — **원인 확정, 조치 완료(CI 검증 대기)**
- 관련: [IMP-004](../improvements/IMP-004-queue-admission.md)(승격 Lua 원자화), ADR-002(원자성 계층)
- 상태: **해결** — 승격 커밋을 한 Lua로 모으고 판정 규칙을 통일했다. 회귀 테스트 3건 추가

## 0. 증상 — 하나가 아니라 둘이었다

**같은 원인에서 증상이 두 개 나왔다.** 처음 잡은 것은 ①이고, 고치려고 코드를 읽다가 ②를 찾았다.
②가 더 심각하다.

| | 증상 | 성격 |
|---|---|---|
| ① | 진입하자마자 상태가 `EXPIRED`로 보인다 | UX — 정합성은 안 깨진다 |
| ② | Pod가 특정 창에서 죽으면 **정원이 영구히 줄어든다** | 가용성 — 자동 복구 경로가 없다 |

## 0-1. 증상 ① — 진입 응답이 EXPIRED

대기열 스파이크 측정 run-2에서 3,000명 중 **10명이 진입 응답으로 `EXPIRED`(rank 0)** 를 받았다.
같은 스크립트·같은 절차의 run-1과 run-3에서는 0건이라 재현이 불안정했다.

| 실행 | `WAITING`/`ADMITTED` | `EXPIRED` |
|---|---|---|
| run-1 | 3,000 / 3,000 | 0 |
| **run-2** | **2,990 / 3,000** | **10** |
| run-3 | 3,000 / 3,000 | 0 |

문제는 이것이 이 측정의 세 번째 명제 **"입장 못 한 사람은 에러가 아니라 대기 상태를 받는다"**
를 정면으로 깨뜨린다는 점이다.

## 1. 처음 세운 가설 — 틀렸다

처음에는 **측정 아티팩트**로 봤다. 실행 사이에 Redis를 수동으로 비웠는데
`queue:token:*`은 지워지고 `queue:user:*`가 SCAN 경합으로 남아, 죽은 토큰이
`isReusable()`의 fallback으로 재사용됐다는 시나리오다. 이 가설을 PR 본문에 **추정으로** 적었다.

**검증했더니 아니었다.** 정리 직후 상태를 직접 감사하니 잔존이 0이다.

```
정리직후 user키=0
정리직후 token키=0
정리직후 admit키=0
정리직후 admitcount=  wait=0  seq=
```

정리는 완전했다. 원인은 내 절차가 아니라 애플리케이션에 있다.

## 2. 확정된 원인 — 원자 구간이 pop에서 끝난다

`QueueAdmissionService.admit()`:

```java
List<?> popped = redis.execute(ADMIT_SCRIPT, ...);   // ① ZPOPMIN — wait에서 제거(원자)
if (popped == null || popped.isEmpty()) return 0;
long expiresAt = Instant.now().getEpochSecond() + admitTtl;
for (int i = 0; i < popped.size(); i += 2) {
    String token = String.valueOf(popped.get(i));
    redis.opsForValue().set(QueueKeys.admit(token), "1", ...);        // ② admit 키 생성
    redis.opsForZSet().add(QueueKeys.admitExp(eventId), token, expiresAt);
    sse.send(token, "queue.admitted", ...);                           // ③ 토큰마다 SSE 전송
    admitted++;
}
```

**①은 원자지만 ②는 Lua 밖이다.** ① 직후 ② 전까지 그 토큰은

- `queue:wait`에 없고(pop됨)
- `queue:admit:{token}`도 아직 없다

`statusOf()`는 정확히 이 두 가지만 본다.

```java
private QueueStatus statusOf(String token, Long eventId) {
    if (Boolean.TRUE.equals(redis.hasKey(QueueKeys.admit(token)))) return QueueStatus.ADMITTED;
    Long r = redis.opsForZSet().rank(QueueKeys.wait(eventId), token);
    return r != null ? QueueStatus.WAITING : QueueStatus.EXPIRED;   // ← 이 창에서 여기로 떨어진다
}
```

**따라서 그 창 안의 조회는 `EXPIRED`가 된다.** 창은 짧지 않다 — 루프가 최대 `capacity`(100)개를
돌면서 토큰마다 Redis 왕복 2회 + **SSE 전송**을 한다.

### 왜 신규 발급 경로에서만 보이나

`issue()`는 두 경로로 갈린다.

```java
if (issued != null && issued == 1L) {
    return tokenResponse(token, eventId);        // 신규 발급 — 방어 없음
}
...
if (existing != null && isReusable(existing, eventId)) {
    return currentOrWaiting(existing, eventId);  // 재사용 — EXPIRED를 WAITING으로 낙관 처리
}
```

`currentOrWaiting()`에는 이미 이 주석이 붙어 있다.

> 소유자가 아직 대기열 등록 전(경합)이면 EXPIRED로 보일 수 있어 WAITING으로 낙관 처리.

**같은 방어가 신규 발급 경로에는 없다.** 원 작성자가 이 종류의 창을 이미 알고 한쪽만 막아둔
셈이다.

## 2-1. 증상 ② — 같은 창에서 Pod가 죽으면 정원이 영구히 준다

①을 고치려고 카운터의 수명을 따라가다 찾았다. **`admitcount`를 감소시키는 경로는 두 개뿐이고,
둘 다 `admitExp`를 근거로 움직인다.**

| 경로 | 감소 근거 |
|---|---|
| `RECLAIM_LUA` | `admitExp`에서 만료분(`score <= now`)을 제거한 만큼 `DECRBY` |
| `LEAVE_ADMIT_SCRIPT` | `admitExp`에서 실제로 제거된 요청만 `DECRBY` |

그런데 `ADMIT_LUA`는 `INCRBY admitcount`까지만 하고 **`admitExp` 등록은 Java 루프(위 코드
61행)** 였다. 그 사이에 Pod가 죽으면:

```
admitcount   +N 된 채로 남음
admitExp     비어 있음  →  RECLAIM도 LEAVE도 이 슬롯을 볼 수 없다
```

**그 N개의 슬롯은 영원히 돌아오지 않는다.** `admitcount`를 실제와 대조해 교정하는 코드는
어디에도 없다(전체 참조를 확인했다). 정원 100이 조금씩 깎여 내려가고, 재시작으로도 복구되지
않는다 — Redis에 남기 때문이다.

동시에 그 사용자는 `wait`에서 빠졌는데 입장 표시도 없어 **영구 미아**가 된다(토큰 TTL 30분까지).

롤링 배포·HPA 축소·OOM 어느 것으로든 닿을 수 있는 경로다. 발생 확률은 낮지만 **자동 복구가
없다는 점**에서 ①보다 나쁘다.

### 왜 run-2에만 나왔나

**확정하지 않았다.** 세 실행의 절차는 동일하고, 차이는 승격 워커의 루프 실행 시점과 3,000건
진입이 겹치는 타이밍뿐이다. run-2는 샘플러가 `admit=37`(상승 중간값)을 잡은 유일한 실행이기도
해서, **진입과 승격 루프가 가장 많이 겹친 실행이었을 가능성**이 있으나 이는 **추정**이다.

## 3. 영향

**증상 ①**

- **정합성은 깨지지 않는다.** 정원 초과가 아니고 순서도 아니다. 그 사용자는 실제로 승격됐고
  잠시 뒤 조회하면 `ADMITTED`다. **틀린 것은 상태 스냅샷 하나다.**
- **사용자 경험은 깨진다.** 진입하자마자 "만료"를 보는 것은 대기열의 계약을 어기는 응답이다.
- 폴링 경로(`GET /queue/status`)도 같은 `statusOf()`를 쓰므로 같은 창에 노출된다.
  (다만 토큰 메타는 TTL 1800s로 살아 있어 410이 아니라 본문 `status=EXPIRED`가 된다.)

**증상 ②**

- **자동 복구가 없다.** 누수된 슬롯은 재시작으로도 안 돌아온다(상태가 Redis에 있다).
- 정원이 조용히 깎이므로 **증상이 "요즘 대기가 길다"로만 보인다** — 원인 추적이 어렵다.
- 발생 빈도는 낮다(창이 좁고 Pod 종료가 겹쳐야 한다). 그러나 ①과 달리 **누적된다.**

⚠️ **운영에서 이 누수가 실제로 발생했다는 증거는 없다.** 코드 경로로 확인한 것이고,
과거 `admitcount`가 부당하게 높았던 관측 기록은 찾지 않았다.

## 4. 조치

### 4-1. 검토한 선택지

| | 내용 | 판정 |
|---|---|---|
| A | 신규 발급 경로도 `currentOrWaiting()`을 쓰게 한다(1줄) | **기각** — ①의 증상만 가린다. 폴링 경로는 그대로 노출되고 **②는 전혀 안 고쳐진다** |
| B | `admitExp` 등록을 `ADMIT_LUA` 안으로 | **채택** |
| C | `queue:admit:{token}` 키를 폐기하고 `admitExp` 단일 권위 | 보류 — 가장 깔끔하지만 `isAdmitted`·`leave`·좌석 게이트까지 번져 변경 범위가 크다 |

**B를 고른 이유**는 `admitExp`가 **이벤트 단위 키**라 `KEYS`로 넘길 수 있다는 점이다.
Lua 안에서 키 이름을 만들지 않으므로 IMP-004 §8이 지적한 **Redis Cluster 슬롯 제약을 새로
만들지 않는다.** (토큰 단위인 `queue:admit:{token}`을 Lua로 넣으려면 이름을 계산해야 해서
그 제약이 생긴다 — 그래서 그 키는 Lua 밖에 뒀다.)

### 4-2. 승격 커밋을 한 원자 단위로

```lua
local popped = redis.call('ZPOPMIN', KEYS[1], free)
local n = #popped / 2
if n > 0 then
  redis.call('INCRBY', KEYS[2], n)
  for i = 1, #popped, 2 do
    redis.call('ZADD', KEYS[3], ARGV[2], popped[i])   -- KEYS[3]=admitExp  ← 추가
  end
end
```

이제 **pop · 카운트 · 만료등록이 함께 일어나거나 함께 일어나지 않는다.** 카운트만 오른 상태가
존재할 수 없으므로 **②가 사라진다.**

Lua 뒤에 남은 것은 부수 작업뿐이고, 중간에 죽어도 슬롯이 새지 않는다.

- `queue:admit:{token}` — 상태 조회의 **빠른 경로**(권위는 `admitExp`)
- SSE 알림 — 놓쳐도 클라이언트 폴백 폴링이 받는다

### 4-3. 판정 규칙을 하나로

`admit` 키만 보던 판정을 바꿔, 없으면 `admitExp`를 본다.

```java
private boolean admittedNow(String token, Long eventId) {
    if (Boolean.TRUE.equals(redis.hasKey(QueueKeys.admit(token)))) {
        return true; // 대부분 여기서 끝난다(왕복 1회)
    }
    Double expiresAt = redis.opsForZSet().score(QueueKeys.admitExp(eventId), token);
    return expiresAt != null && expiresAt > Instant.now().getEpochSecond();
}
```

**`statusOf()`와 `isAdmitted()`를 함께 고친 것이 중요하다.** 한쪽만 고치면
"대기열은 입장이라고 하는데 좌석 요청은 거절"이라는 **더 나쁜 불일치**가 생긴다.
`admitExp`는 이벤트 단위 ZSet이라 소속 이벤트 검사를 겸한다(`admit` 키 폴백 경로에서는
기존대로 토큰 메타의 `eventId`를 확인한다 — 다른 이벤트의 입장으로 좌석을 잡지 못하게).

A안(`currentOrWaiting`)은 채택하지 않았다. 창 자체가 없어졌으므로 낙관 처리가 필요 없다.

### 4-4. 회귀 테스트

`QueueAdmitVisibilityIntegrationTest` 3건. `admit-ttl`을 60초로 둔 별도 클래스인데,
기존 `QueueIntegrationTest`는 1초라 "만료 전인가" 판정이 **초 경계에서 뒤집혀 플래키**해진다.

| 테스트 | 무엇을 고정하나 |
|---|---|
| `승격이_확정됐으면_admit키가_없어도_ADMITTED로_판정한다` | admit 키를 지워 창을 결정적으로 재현. `status()`와 `isAdmitted()` **둘 다** 확인 |
| `승격은_카운트와_만료등록이_함께_움직인다` | `admitcount == admitExp 원소 수` — 어긋나면 그만큼 영구 누수 |
| `만료_회수는_카운트와_만료등록을_함께_되돌린다` | 회수 후 둘 다 0 |

⚠️ **이 창의 레이스 자체를 시간으로 재현하지는 않았다.** 창을 상태로 재현했다(admit 키 삭제).
실제 동시성 하에서의 재현은 스파이크 재측정 몫이다.

### 4-5. 기존 테스트 하나가 깨졌고, 그게 맞다

CI에서 `SeatInventoryIntegrationTest > 입장창이_만료된_토큰으로_선점하면_거부된다()`가
실패했다(191개 중 1개). **내 변경이 낸 회귀이고, 테스트가 진짜를 잡았다.**

그 테스트는 만료를 이렇게 흉내 냈다.

```java
redisTemplate.delete("queue:admit:" + token); // 입장창 만료 시뮬레이션(admit 키 소멸)
```

**`admit` 키의 부재가 두 가지를 동시에 뜻하고 있었다** — "아직 안 쓰였다(창)"와 "만료됐다".
수정 후에는 그 둘이 갈린다. 구분 신호는 `admitExp`의 score다.

| 상태 | `admit` 키 | `admitExp` score | 의미 |
|---|---|---|---|
| 승격 확정 직후 | 없음 | **미래** | 입장 중(표시 전) |
| 입장창 만료 | 없음(TTL) | **과거** | 만료 |
| 이탈(`leave`) | 없음 | 없음 | 이탈 |

**운영에서 admit 키 TTL과 `admitExp` score는 같은 시점에 함께 지난다**(둘 다 승격 시각 +
`admitTtl`). 따라서 "admit 키만 사라지고 admitExp는 미래"인 상태는 **만료로는 발생할 수 없고**
창으로만 발생한다. 즉 기존 테스트의 시뮬레이션이 더 이상 만료를 나타내지 못하게 된 것이라,
시뮬레이션을 실제 만료(둘 다 지남)로 고쳤다.

**테스트를 통과시키려고 단언을 바꾼 것이 아니다** — 단언(`만료 토큰은 좌석 선점 거부`)은 그대로고,
만료를 만드는 방법만 실제와 맞췄다.

## 5. 배운 점

**원자성은 "어디까지"가 핵심이다.** IMP-004는 `확인 → pop → 카운트 증가`를 Lua로 묶어
over-admit을 0으로 만들었고 그 판단은 지금도 맞다. 그런데 **원자 구간이 pop에서 끝나고,
"입장했다"를 실제로 나타내는 표시는 그 밖에서 만들어진다.** 불변식(정원)은 지켜지지만
**상태 가시성**에는 창이 남았다.

> 원자화할 때 "무엇을 깨뜨리지 않을 것인가"만 정하고 "언제부터 관측 가능한가"를 정하지 않으면,
> 정합성은 맞는데 사용자에게는 틀린 값이 보이는 구간이 생긴다.

**그리고 카운터는 그것을 되돌리는 경로와 같은 원자 단위에 있어야 한다.** ②가 그 교훈이다.
`admitcount`를 올리는 곳과, 그것을 내리는 근거(`admitExp`)가 서로 다른 원자 단위에 있었다.
증가와 감소 근거가 분리되면 **한쪽만 반영된 상태가 영구히 남을 수 있고**, 되돌릴 방법도 없다.
"올리는 연산"이 아니라 **"내리는 연산이 무엇을 보는가"**를 기준으로 원자 경계를 그었어야 했다.

그리고 이 결함은 단위 테스트나 소규모 통합테스트로는 나오지 않았다. **정원의 30배가 실제로
도착하는 상황에서만** 진입과 승격 루프가 충분히 겹쳤다. 부하 시험이 성능이 아니라
**정합성 결함**을 찾아낸 사례다.

## 6. 남은 것

- [x] 조치 선택(B) 및 수정
- [x] 회귀 테스트 3건
- [ ] **CI 검증** — 로컬 gradle이 없어 컴파일·통합테스트를 돌리지 못했다. Testcontainers는 CI에서만 돈다
- [ ] **스파이크 재측정으로 `EXPIRED 0` 재확인(3회 이상)** — 이게 끝나야 Phase 4를 닫는다
- [ ] run-2에만 나타난 이유(타이밍) 확정 — 현재 추정
- [ ] C안(`admit` 키 폐기, `admitExp` 단일 권위) 검토 — 지금은 진실의 원천이 둘이다
- [ ] SSE 전송을 승격 루프 밖으로 빼는 건 — 창은 없어졌지만 루프가 여전히 길다
