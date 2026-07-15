# TS-011 · CI 통합테스트 간헐 실패 — 클래스별 Testcontainers 자원 경합(Redis timeout) → 공유 싱글톤

- 슬라이스: 횡단(테스트 인프라)
- 날짜: 2026-07-15
- 유형: 인프라/플레이크(CI) — Testcontainers 자원 경합
- 관련 커밋/PR: fix/shared-testcontainers
- 관련 문서: 여러 IMP의 동시성 통합테스트 근거(측정 안정성 확보)

> 순서: 증상 → 조사 → 근본 원인 → 해결 → 재발 방지.

## 1. 증상
CI `gradle build`에서 특정 동시성 테스트가 **간헐적으로** 실패. 코드 변경과 무관한 슬라이스에서 발생:
```
QueueIntegrationTest > 같은_유저_동시발급도_토큰이_하나만_생긴다() FAILED (line 92)
```
로그에는 실패 테스트와 무관하게 Redis 예외가 도배됨:
```
org.springframework.dao.QueryTimeoutException: Redis command timed out
  ... QueueAdmissionService.runOnce ...
Caused by: io.lettuce.core.RedisCommandTimeoutException: Command timed out after 1 minute(s)
Cannot reconnect to [localhost/...:32789]: Connection refused
```
로컬 재현 불가(로컬 gradle 없음), 재실행하면 대개 통과 → 전형적 플레이크. 슬라이스가 늘수록 빈도 증가.

## 2. 조사
- 실패한 단언 자체는 정상 로직(1인1토큰). 문제는 그 순간 Redis 명령이 **1분 타임아웃**난 것.
- `Connection refused ...:327xx` — 이미 **종료된 컨테이너 포트**에 재접속 시도. 즉 살아있는 컨테이너가 아님.
- 통합테스트 클래스 수를 셈: **@SpringBootTest 13개**, 각 클래스가 `@Container`로 postgres+redis를
  **따로** 기동 → **13쌍**. 클래스마다 컨테이너를 start/stop.
- 게다가 클래스마다 `@DynamicPropertySource` 조합이 달라 Spring 컨텍스트가 매번 새로 뜸(캐시 미스) →
  이전 컨텍스트의 `@Scheduled` 승격 워커가 **이미 종료된 Redis 컨테이너**를 계속 때림 → 타임아웃 누적.

## 3. 근본 원인
**"클래스당 컨테이너 한 쌍"** 구조가 클래스 수에 비례해 CI 러너 자원(포트/메모리/스레드)을 잠식.
종료된 컨테이너에 잔여 스케줄러가 붙어 Redis command timeout(1분)을 유발하고, 그 사이 실행 중인
동시성 테스트의 Redis 명령이 타임아웃 → 단언 실패. **코드 버그가 아니라 테스트 인프라의 자원 경합.**
S06에서 통합테스트 4개를 추가(9→13)하면서 임계에 더 가까워졌다.

## 4. 해결
컨테이너를 **공유 싱글톤**으로 전환 — 전체 통합테스트가 postgres/redis **한 쌍**을 재사용.
```java
// apps/api/src/test/java/com/flowticket/support/SharedContainers.java
public final class SharedContainers {
    public static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");
    public static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);
    static { POSTGRES.start(); REDIS.start(); }   // JVM당 1회, Ryuk가 종료 시 정리
}
```
- 13개 클래스의 `@Container static ... = new ...`를 `static final ... = SharedContainers.X;`로 교체
  (`@Testcontainers`·`@Container` 제거). 각 클래스의 `@DynamicPropertySource`(hold-ttl 등)는 유지.
- 효과: 컨테이너 **13쌍 → 1쌍**, 컨테이너가 실행 내내 살아있어 "종료 컨테이너 재접속" 스팸 소멸,
  자원 경합 급감 → 타임아웃 플레이크 제거.
- 테스트 간 데이터 격리는 각 클래스의 `@BeforeEach deleteAll`이 이미 담당 → 컨테이너 공유와 무관.

## 5. 재발 방지
- 새 통합테스트는 컨테이너를 **직접 띄우지 말고** `SharedContainers`를 참조(규약).
- 교훈: `@Container` per-class는 클래스가 적을 때만 안전 — 수가 늘면 **자원 경합이 플레이크로 표출**된다.
  상태 저장소(DB/Redis)는 테스트 전체가 공유하고, 격리는 데이터 정리로 푸는 게 확장에 맞다.
- (후보) 컨텍스트 캐시 히트를 높이려 `@DynamicPropertySource` 조합을 표준화하면 컨텍스트 수도 줄어듦 —
  hold-ttl 같은 소수 차이는 런타임 조정으로 흡수 가능(후속 개선).

## 한계 / 남은 것
- 공유 컨테이너라도 컨텍스트가 여러 개면 스케줄러 다중 기동은 남음 — 다만 컨테이너가 살아있어
  타임아웃은 안 남. 컨텍스트 표준화는 별도 개선으로 분리.
