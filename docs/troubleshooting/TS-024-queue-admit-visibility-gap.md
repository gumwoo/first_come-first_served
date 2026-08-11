# TS-024 · 승격 순간 상태가 `EXPIRED`로 보이는 창 — ZPOPMIN과 admit 키 사이

- 슬라이스: S10(부하 시험) — 발견 경로는 [`benchmarks/spike-queue/`](../../benchmarks/spike-queue/README.md)
- 날짜: 2026-08-12
- 유형: 결함 발견 — **원인 확정, 수정은 미실시**
- 관련: [IMP-004](../improvements/IMP-004-queue-admission.md)(승격 Lua 원자화), ADR-002(원자성 계층)
- 상태: **미해결** — 원인은 코드로 확정했고 조치는 별도 작업으로 남긴다

## 0. 증상

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

### 왜 run-2에만 나왔나

**확정하지 않았다.** 세 실행의 절차는 동일하고, 차이는 승격 워커의 루프 실행 시점과 3,000건
진입이 겹치는 타이밍뿐이다. run-2는 샘플러가 `admit=37`(상승 중간값)을 잡은 유일한 실행이기도
해서, **진입과 승격 루프가 가장 많이 겹친 실행이었을 가능성**이 있으나 이는 **추정**이다.

## 3. 영향

- **정합성은 깨지지 않는다.** 정원 초과가 아니고 순서도 아니다. 그 사용자는 실제로 승격됐고
  잠시 뒤 조회하면 `ADMITTED`다. **틀린 것은 상태 스냅샷 하나다.**
- **사용자 경험은 깨진다.** 진입하자마자 "만료"를 보는 것은 대기열의 계약을 어기는 응답이다.
- 폴링 경로(`GET /queue/status`)도 같은 `statusOf()`를 쓰므로 같은 창에 노출된다.
  (다만 토큰 메타는 TTL 1800s로 살아 있어 410이 아니라 본문 `status=EXPIRED`가 된다.)

## 4. 조치 방향 (미실시)

두 층위가 있고, **이 문서는 선택을 확정하지 않는다.**

**A. 읽기 경로 방어(최소 변경)** — 신규 발급 경로도 `currentOrWaiting()`을 쓰게 한다.
재사용 경로와 동작이 같아지고 변경이 한 줄이다. 다만 **증상을 가리는 쪽**이고, 폴링 경로는
여전히 창에 노출된다.

**B. 쓰기 경로 원자화(근본)** — pop과 `admit` 키 생성을 한 Lua에 넣는다. 창 자체가 사라진다.
대신 Lua 안에서 `queue:admit:`+token 형태로 **키 이름을 계산**하게 되는데, 이는 IMP-004 §8이
이미 지적한 **Redis Cluster 슬롯 문제**와 직접 충돌한다(관련 키를 hash tag로 같은 슬롯에 묶어야
함). 즉 B는 한 줄 수정이 아니라 키 규약 결정을 동반한다.

SSE 전송을 루프 밖으로 빼 창을 좁히는 것은 어느 쪽이든 별개로 검토할 가치가 있다.

## 5. 배운 점

**원자성은 "어디까지"가 핵심이다.** IMP-004는 `확인 → pop → 카운트 증가`를 Lua로 묶어
over-admit을 0으로 만들었고 그 판단은 지금도 맞다. 그런데 **원자 구간이 pop에서 끝나고,
"입장했다"를 실제로 나타내는 표시는 그 밖에서 만들어진다.** 불변식(정원)은 지켜지지만
**상태 가시성**에는 창이 남았다.

> 원자화할 때 "무엇을 깨뜨리지 않을 것인가"만 정하고 "언제부터 관측 가능한가"를 정하지 않으면,
> 정합성은 맞는데 사용자에게는 틀린 값이 보이는 구간이 생긴다.

그리고 이 결함은 단위 테스트나 소규모 통합테스트로는 나오지 않았다. **정원의 30배가 실제로
도착하는 상황에서만** 진입과 승격 루프가 충분히 겹쳤다. 부하 시험이 성능이 아니라
**정합성 결함**을 찾아낸 사례다.

## 6. 남은 것

- [ ] 조치 A/B 중 선택 후 수정
- [ ] 수정 후 스파이크 재측정으로 `EXPIRED 0` 재확인(3회 이상)
- [ ] run-2에만 나타난 이유(타이밍) 확정 — 현재 추정
