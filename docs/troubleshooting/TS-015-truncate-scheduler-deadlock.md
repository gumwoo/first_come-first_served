# TS-015 · CI가 6번 깨졌다 — "꺼 뒀다고 믿은" 스케줄러가 TRUNCATE와 데드락

- 슬라이스: 횡단(테스트 인프라)
- 날짜: 2026-08-06
- 유형: 테스트 인프라 / 진단 회고
- 관련 PR: [#170](https://github.com/gumwoo/first_come-first_served/pull/170)(진단 심기),
  [#176](https://github.com/gumwoo/first_come-first_served/pull/176)(수정)
- 상태: 기전 차단(재발 여부는 관측 중)

## 1. 증상

`QueueIntegrationTest > 입장상태에서_재발급하면_같은_토큰이_유지된다()`가 **6번** 실패했다.
**매번 재실행하면 통과**했다.

```
QueueIntegrationTest > 입장상태에서_재발급하면_같은_토큰이_유지된다() FAILED
    org.springframework.dao.PessimisticLockingFailureException
```

이상한 점이 둘 있었다.

- **실패한 테스트는 Redis만 쓴다.** DB 락 예외가 나올 이유가 없다.
- 실패 위치가 테스트 본문이 아니라 **`@BeforeEach`의 초기화**였다.

## 2. 왜 오래 걸렸나 — 진단이 없었다

이건 **재현을 통제할 수 없는 실패**다. CI에서 간헐적으로만 나고, 러너는 끝나면 사라진다.
1~4회차에는 예외 **클래스 이름만** 남았다.

```
java.lang.IllegalStateException at TestContainers.java:110
```

**Gradle 기본 콘솔 출력은 예외의 클래스명·줄번호까지만 내보낸다.** 메시지는
`build/test-results/*.xml`에만 남는데 CI가 그 리포트를 업로드하지 않아 러너와 함께 사라졌다.
**진단을 심어 놓고 그 진단을 읽지 못하는 상태**였다(PR #170에서 `exceptionFormat=FULL` +
아티팩트 업로드로 해결).

## 3. 조사 — 틀린 가설로 시작했다

PR #169에서 다음 가설을 세우고 진단을 심었다.

> TRUNCATE는 ACCESS EXCLUSIVE를 요구한다. **앞선 테스트가 트랜잭션을 흘리면** 이 초기화가
> 그 락을 무기한 기다린다.

그래서 `lock_timeout = 20s`로 시간을 끊고, 끊길 때 `pg_stat_activity`를 예외 메시지에 박제했다.

**6회차에 처음으로 그 내용이 로그에 찍혔고, 가설이 틀렸음을 보여줬다.**

```
Caused by: PSQLException: ERROR: deadlock detected
  Detail: Process 120 waits for AccessExclusiveLock on relation 16472; blocked by process 119.
          Process 119 waits for RowExclusiveLock on relation 16425; blocked by process 120.

[pg_stat_activity]  (35개 세션, idle in transaction 0개)
  pid=76 state=idle ... q=update seats set status='AVAILABLE' where id=$1
  pid=77 state=idle ... q=update seats set status='AVAILABLE' where id=$1
  pid=78 state=idle ... q=update seats set status='AVAILABLE' where id=$1
```

**락 대기 타임아웃이 아니라 데드락이었다.**

```
TRUNCATE 세션(120)      어떤 테이블의 ACCESS EXCLUSIVE 보유 → 다른 테이블 락 대기
스케줄러 세션(119)       다른 테이블의 RowExclusive 보유    → TRUNCATE가 쥔 테이블 대기
                        → 서로 기다리며 데드락
```

**119가 "waits"라는 것이 결정적이다.** 누수된 트랜잭션이라면 `idle in transaction`으로
**가만히 있지 락을 기다리지 않는다.** 상대는 그 순간 **살아 움직이던 백그라운드 작업**이었고,
`update seats set status='AVAILABLE'`이 그 정체를 가리켰다 — 좌석 만료 스윕이다.

## 4. 근본 원인 — 주석은 의도였을 뿐 사실이 아니었다

```java
// 배경 워커 비활성 — 필요한 테스트는 서비스 메서드를 직접 호출해 결정적으로 검증한다.
"queue.admit-interval-ms=3600000",
"seat.sweep-interval-ms=3600000",
...
```

**`@Scheduled(fixedRateString = ...)`에는 `initialDelay`가 없다.** Spring은 이를
**컨텍스트가 뜨는 순간 즉시 한 번** 실행한다. 주기를 1시간으로 늘려 미뤄지는 것은
**두 번째 실행뿐**이다.

컨텍스트가 6개라 **스위트 중간에 새 컨텍스트가 뜰 때마다 스윕 6종이 한 번씩 발사**됐고,
그게 다른 테스트의 `@BeforeEach` TRUNCATE와 겹치면 데드락이 났다.
**간헐적이었던 이유가 정확히 이것이다** — 컨텍스트 기동과 TRUNCATE의 타이밍이 겹칠 때만 난다.

## 5. 해결

주기가 아니라 **스케줄링 자체를** 끈다.

```java
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "flowticket.scheduling.enabled",
                       havingValue = "true", matchIfMissing = true)
public class SchedulingConfig { }
```

`@EnableScheduling`을 애플리케이션 클래스에 두면 끌 방법이 없어 분리했다.
`matchIfMissing = true`라 **프로덕션 동작은 그대로**고, 테스트만 `false`를 준다.

**스윕이 필요한 테스트는 전부 이미 메서드를 직접 호출하고 있었다**(`ranking.decay()`,
`admissionService.admit()`, `relay.publishPending()`, `reconcileOrphanApprovals()`).
자동 실행에 기대는 테스트는 하나도 없었다 — 이 변경은 **의도했던 상태를 실제로 만드는 것**이지
검증을 줄이는 게 아니다.

**부수 효과**: `RankingIntegrationTest`의 `@TestPropertySource(ranking.decay-rate-ms)`가
불필요해져 제거하니 공유 컨텍스트로 합쳐졌다(6→5). `gradle build` 8분 52초 → 8분 4초.
**n=1이라 확정하지 않는다.**

## 6. 재발 방지 · 남긴 것

- **`flowticket.scheduling.enabled=false`** 를 `IntegrationTestSupport`와 Kafka 계열 테스트 4개에 적용.
- **진단은 지우지 않았다.** 이번 원인이 스케줄러였을 뿐 다른 경로(트랜잭션 누수 등)가 배제된 것은
  아니다. 다만 메시지가 **틀린 방향을 가리키고 있어** 문구를 고쳤다 —
  두 가설을 다 살리고 **구분하는 방법**을 넣었다.
  > `TRUNCATE 실패 — 백그라운드 스케줄러 DML과의 데드락이 가장 유력하다.`
  > `(누수된 트랜잭션이 원인이라면 아래 목록에 idle in transaction으로 나타난다)`

## 7. 교훈

1. **"비활성화했다"는 주석은 검증되지 않는다.** 주기를 늘린 것과 끈 것은 다르다.
   프레임워크의 기본 동작(`initialDelay` 없음)을 확인하지 않고 의도를 주석에 적었다.
2. **재현할 수 없는 실패는 "다음에 읽을 수단"부터 만든다.** 원인을 추측해 고치는 대신
   진단을 심었고(PR #169·#170), 그게 **내 가설이 틀렸다는 것까지** 알려줬다.
   추측으로 고쳤다면 트랜잭션 누수를 찾아 헤맸을 것이다.
3. **진단을 심는 것과 읽을 수 있는 것은 별개다.** 4회차까지 진단은 작동하고 있었지만
   Gradle 기본 출력에 가려 보이지 않았다. **심었으면 읽히는지도 확인해야 한다.**
4. **재실행으로 넘기면 비용이 누적된다.** 6번 동안 "플레이크"로 처리했고, 그동안 인프라 PR마다
   무관한 실패가 섞여 진짜 실패와 구분이 어려웠다.

## 8. 한계 (정직)

- **"플레이크가 사라졌다"는 증명되지 않았다.** 원래 간헐적이라 1회 통과는 근거가 약하다.
  근거는 **기전이 막혔다**는 것이고, 재발 여부는 앞으로의 실행으로만 확인된다.
- **다른 데드락 경로는 배제되지 않았다.** `pg_stat_activity` 스냅샷은 **실패한 뒤**에 찍히므로,
  대기 중 락을 쥐고 있던 세션이 그 사이 정리됐을 수 있다 — 이 진단의 구조적 한계다.
- 컨텍스트 감소로 인한 시간 단축은 **n=1**.
