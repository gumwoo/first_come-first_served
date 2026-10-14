# Kafka 제거 설계 — 아웃박스 → Redis Pub/Sub 전환, 의존성 목록, 단계별 PR·게이트·롤백

- 상태: **설계(구현 전)** — 2026-10-14 20:57 KST 작성, 21:34 KST 측정·삭제 순서 정정(사용자 결정: 기준선 (a) 세션 차이 수용). 결정은 [[ADR-028]](2026-10-14 20:39 KST 사용자 결정 — B. Kafka 제거)
- 관련: [[ADR-008]](Kafka 백본 + DLQ — 대체 대상), [[ADR-010]](아웃박스 — 유지), [[ADR-012]](3 AZ), [[ADR-022]](릴레이 임차), [[TS-023]](다중 Pod SSE),
  [zero-downtime-deployment.md](zero-downtime-deployment.md) §8(Expand-Contract), [operating-requirements.md](operating-requirements.md), [workload-responsibilities.md](workload-responsibilities.md)
- 표기: **사실** = 코드·설정·명령 출력으로 확인한 것. **추론** = 코드에서 끌어냈지만 실행으로 확인하지 않은 것. **계산** = 설정값으로 계산한 것(실측 아님).
  이 문서는 성능·비용 개선을 **증명하지 않는다**. 바뀌는 구조와 바꾸는 순서를 정한다.

## 0. 요약
- 바꾸는 것은 **아웃박스 릴레이의 발행 대상 하나**다(Kafka `order-events` → Redis 채널 `sse:order`). 결제 트랜잭션에 이벤트를 쓰는 부분(아웃박스 적재)과 프론트 SSE 계약은 그대로다.
- 대가: `PUBLISHED`의 의미가 "Kafka 로그에 복제·저장됨"에서 "**Redis에 발행 호출이 성공함**"으로 약해진다. 구독자·브라우저 수신은 지금도, 앞으로도 보장하지 않는다(§1).
- 함정 두 개를 설계로 막는다: 기존 `OrderSseRegistry.broadcast()`는 커밋 뒤로 발행을 미뤄 **마킹이 발행보다 먼저** 일어나고, `SsePubSub.publish()`는 **예외를 삼킨다**. 릴레이는 둘 다 쓰지 않고 실패를 던지는 별도 발행 메서드를 쓴다(§2).
- 순서(사용자 결정 2026-10-14 — 상세 §6): 랭킹 감쇠 수정(별도) → 프론트 보완(P1) → 관리 화면 확장(P2a, 추가만) → **앱 전환 = 릴리스 N(P2b)** → 자원 산정 문서·P3a·저장소 P3 준비 → 측정 세션[N 게이트(§6.1) → Kafka 운영 삭제 게이트(§6.2) → B₁ → B′ → 가용성 G1 시험] → 관찰 기간(§6.3) 뒤 저장소 P3 머지 → 충분히 안정된 뒤 `DROP`(P4 = N+1) → 문서 정리(P5). **새 경로가 검증되기 전에는 Kafka 인프라를 지우지 않는다**.
- 운영 중 전환은 ShedLock이 발행 경로 하나를 보장한다고 가정하지 않는다. 클러스터가 없을 때 전환한다(채택 — (가)). 떠 있는 클러스터라면 발행 대상을 설정으로 먼저 바꾼 뒤 Kafka 배출을 확인하고 코드를 지운다(§6).
- 노드: CPU requests만 보면 하한은 A' 3대·현재 B 5대·B 2+2 후보 4대이고, **Kafka 제거만으로 이 하한은 낮아지지 않는다**. 실제 필요 노드 수는 메모리·DaemonSet·배치 제약을 반영해 다시 산정한다(§7). 확실히 줄어드는 것은 구성 요소·운영 절차·EBS 30GiB다.
- 다음 B 측정은 "Kafka 제거 후 기존 설정(B₁)"과 "자원 최적화(B′)"를 나눠 잰다. 기준선은 **(a) 세션 차이를 받아들인다**(사용자 결정 2026-10-14) — 기존 B(B₀)와 B₁의 차이를 Kafka 제거만의 효과로 쓰지 않는다. B₁은 Kafka **인프라까지 운영 삭제한 뒤** 잰다(§6.2·§7.5).

## 1. 이벤트 전달 보장 — 무엇이 바뀌고 무엇이 그대로인가

### 1.1 구간별 비교 (사실 + 추론 표시)
| 구간 | 지금(Kafka) | 전환 후(Redis Pub/Sub) |
|---|---|---|
| ① 결제 트랜잭션 → 아웃박스 행 | 같은 커밋(`PaymentService.finalizePaid` → `appendOutbox("order.paid")`, `PaymentService.java:422`) | **그대로** |
| ② 릴레이 → 전송 계층 | `kafkaTemplate.send(...).get(sendTimeoutMs)` — acks=all, RF 3·min.insync 2 브로커 로그에 **저장**된 뒤 `markPublished`(`OutboxRelay.java:172-177`) | `PUBLISH sse:order` 성공 뒤 `markPublished`. Redis는 **저장하지 않는다** — 그 순간 구독 중인 연결에만 전달하고 끝 |
| ③ 전송 계층 → 소비자 | 소비자 그룹이 오프셋으로 이어 읽는다. 소비자 파드가 죽어도 메시지는 브로커에 남는다. 예외 시 300ms × 2 재시도 → `order-events.DLT` → `dlq_messages` | 없음. 각 파드의 구독 연결(`SseRedisConfig`)이 받는다. 그 순간 구독 연결이 끊겨 있던 파드는 **못 받고 끝** |
| ④ 소비자 → 파드 간 팬아웃 | `OrderEventConsumer` → `broadcast()` → `SsePubSub.publish()`. Redis 실패는 **삼키고 소비 성공 처리**(`SsePubSub.java:32-38`) → 그 이벤트는 사라지고 DLQ에도 안 간다(추론 — 리스너에 트랜잭션이 없어 `AfterCommit`이 즉시 실행, 예외가 리스너까지 안 올라감) | ②와 같은 홉이 된다. Redis 실패는 **릴레이가 받아 PENDING 유지 → 다음 틱 재시도**(§2) |
| ⑤ 파드 → 브라우저(SSE) | `deliverLocal` — 전송 실패면 emitter 제거, 재전송 없음 | **그대로** |
| ⑥ 브라우저 복구 | `onopen`·마운트 때 재조회. P1 이전에는 폴링 없음 | 그대로 + P1 보완(§4): PENDING 5초·만료까지, 탭 복귀 1회 |

### 1.2 PUBLISHED의 새 의미 (정책)
- **`PUBLISHED` = "릴레이가 Redis에 `PUBLISH`를 시도했고 그 호출이 오류 없이 끝났다".** 구독자 수(0 포함), 파드의 로컬 전달, 브라우저 수신을 뜻하지 않는다.
- 지금의 `PUBLISHED`도 "Kafka에 저장됨"까지다. 소비자 처리·브라우저 수신은 지금도 보장 밖이다(④·⑤).
- **잃는 것:** ②~③ 사이의 **보관**. Kafka는 소비자가 다시 붙을 때까지 메시지를 들고 있었다. Redis Pub/Sub은 그 순간 구독자가 없으면 버린다.
  - 버려지는 경우는 두 가지로 나눠 봐야 한다(추론, 측정 없음).
    - **파드가 죽은 경우:** 그 파드의 SSE 연결도 함께 끊기므로 브라우저가 재연결하고 `onopen` 재조회로 상태를 맞춘다.
    - **파드는 살아 있고 구독 연결만 끊긴 경우**(Redis 장애 조치·네트워크 순간 단절 등): 브라우저 SSE는 유지된 채 이벤트만 놓친다. **스스로 복구되지 않는다** — 새로고침이나 다음 재연결 전까지 화면이 멈춘다.
  - 두 번째 경우는 **지금 Kafka 경로에도 있다**(④ 뒤 같은 Redis 홉). Kafka가 보관해도 마지막 Redis 홉에서 같은 방식으로 빠진다. §4가 다룬다.
- **얻는 것:** 지금 ④에서 조용히 사라지던 Redis 실패가 ②로 옮겨와 **재시도 대상**이 된다(추론 — §2 설계를 지킨다는 전제).
- 중복: 릴레이는 at-least-once 그대로다(발행 성공 뒤 커밋 전 크래시 → 재발행). 소비자 SETNX 멱등(`OrderEventConsumer.reserve`)은 소비자와 함께 없어진다. 중복 수신의 결과는 프론트의 재조회 1회 추가뿐이다(`useOrder.ts` — 이벤트 payload를 읽지 않고 `refresh()`만 부른다). 그래서 멱등 키를 별도로 두지 않는다. `eventId`는 payload에 남겨 추적용으로 쓴다.
- **다시 보장을 올려야 하는 조건**(금전·재고 소비자, 다중 소비자, 재생 등)은 [[ADR-028]] §6 "다시 도입할 조건"을 따른다 — 먼저 Redis Streams.

## 2. 릴레이 설계 — 실패를 삼키지 않는 발행

### 2.1 쓰면 안 되는 것 두 가지 (사실)
1. **`OrderSseRegistry.broadcast()`** (`OrderSseRegistry.java:67-75`) — `AfterCommit.run`으로 감싸져 있다. 릴레이 `publishPending()`은 `@Transactional`이라, 이걸 부르면 발행이 **트랜잭션 커밋 뒤로 밀리고**, 그 사이 `markPublished`가 먼저 커밋된다. 순서가 publish-then-mark에서 **mark-then-publish로 뒤집혀** at-most-once가 된다. 게다가 `AfterCommit`은 커밋 후 예외를 로그만 남기고 삼킨다(`AfterCommit.java:34-38`).
2. **`SsePubSub.publish()`** (`SsePubSub.java:32-38`) — `catch (Exception e)` → `log.warn`. 실패해도 정상 반환하므로 릴레이는 성공으로 알고 `PUBLISHED`로 마킹한다.

### 2.2 새 발행 메서드
- `SsePubSub`에 **엄격 발행 메서드**를 추가한다(이름 예: `publishOrThrow`). 기존 `publish()`는 best-effort 호출부(좌석·주문 직접 알림)용으로 그대로 둔다.
  - **구현(P2b-1)에서 바뀐 점:** 1순위(전용 연결의 명령 타임아웃)를 쓰면 발행에 전용 Lettuce 연결이 필요하다. 그래서 엄격 발행은 `SsePubSub`의 메서드가 아니라 별도 컴포넌트 `RedisOutboxPublisher`(`OutboxPublisher` 구현)로 뒀다. `SsePubSub`는 바꾸지 않고, 봉투 타입 `SsePubSub.Envelope`과 채널 `OrderSseRegistry.CHANNEL`만 같이 쓴다. 릴레이는 `OutboxPublisher` 인터페이스에만 기대므로 테스트에서 실패·지연을 넣기 쉽다.
  - 연결이 아직 없을 때(기동 직후·재연결)는 연결 수립이 먼저다. Lettuce는 채널 등록 시점부터 handshake 타이머(RedisURI timeout = 명령 타임아웃 T)를 걸어 TCP 연결·TLS·HELLO까지 묶는다. 그래서 한 건 최악은 "연결 수립 ≤ T + PUBLISH ≤ T" ≈ 2T다(lettuce-core 6.3 바이트코드에서 끌어낸 추론 — 측정하지 않았고, DNS 해석이 타이머 안에 드는지는 미확인). 연결 타임아웃(`SocketOptions`)도 T로 둔 것은 TCP 단계의 중복 방어다(1차 G1 리뷰가 "기본 10초"라고 짚은 근거는 2차 리뷰에서 정정됨).
  - 필수 필드(type·orderId)가 빠진 payload는 역직렬화 단계에서 결정적 실패(DEAD)로 거른다. Jackson이 레코드의 빠진 값을 null로 채워 통과시키면 발행 단계 예외가 일시적 실패로 분류돼 그 행에서 배치가 매 틱 멈춘다(G1 리뷰 지적).
  - 직렬화·Redis 오류를 **그대로 던진다**.
  - 봉투는 지금 소비자 경로가 만드는 것과 같다: 채널 `OrderSseRegistry.CHANNEL`(`sse:order`), `Envelope(key = String.valueOf(orderId), event = row.type ("order.paid"), data = {"orderId": orderId})`(`OrderEventConsumer.java:42`와 동일 모양). 프론트가 보는 SSE 프레임은 바뀌지 않는다.
- `OutboxRelay.send()`는 `kafkaTemplate.send(...).get(sendTimeoutMs)` 자리를 엄격 발행으로 바꾸고, 성공한 뒤에만 `markPublished`한다. 실패 분기(`markAttemptFailed` → 배치 중단 → 다음 틱)는 그대로 쓴다. 결정적 실패(역직렬화 → DEAD)·aggregate 보류 로직도 그대로다.
- **대기 상한:** `spring.data.redis.timeout`이 설정돼 있지 않다(사실, `application.yml`). 그러면 Lettuce 명령 타임아웃 기본값(60초 — 라이브러리 기본값에서 나온 추론)을 따른다. 한 건이 60초를 물면 `tick-budget(45s) + send-timeout(3s) ≤ 임차(60s)`([[ADR-022]], 하네스 백엔드 규칙 22) 관계가 실제로는 깨진다.
  - 목표: 한 건의 발행 대기를 **`outbox.send-timeout-ms`(3초) 안으로 묶고**, 대기 중인 작업이 **쌓이지 않게** 한다. 중복 발행은 허용한다(§1.2). 쌓이는 구조는 허용하지 않는다.
  - **1순위 — Redis 클라이언트 명령 자체에 제한 시간을 건다.** 릴레이 발행 전용 커넥션 팩토리(`LettuceConnectionFactory`)와 템플릿을 따로 두고, 그 클라이언트 설정의 `commandTimeout`을 `send-timeout-ms`로 맞춘다.
    - 그러면 시간 초과는 Lettuce가 명령 단위로 판정해 예외를 던지고, 호출 스레드는 그 자리에서 돌아온다(추론 — Lettuce 동작). 시간 초과된 명령도 서버가 응답할 때까지 그 연결의 프로토콜 대기열에 남을 수 있지만, 릴레이 스레드나 실행기에 작업이 쌓이지는 않는다.
    - 서버가 명령을 늦게 처리해 실제로는 발행됐을 수 있다. 이 경우 다음 틱에 다시 나가 중복이 된다(허용).
    - **전용 팩토리는 Spring bean으로 등록하지 않는다.** Spring Boot 3.3.5(`build.gradle.kts:3`)의 Redis 자동 구성은 `RedisConnectionFactory`가 없을 때만 기본 팩토리를 만들고, `StringRedisTemplate`은 후보가 하나일 때만 만든다(라이브러리 동작 — 추론). 두 번째 팩토리나 템플릿을 bean으로 올리면 기본 팩토리가 물러나거나, 타입으로 주입받는 `SchedulerLockConfig`·`SseRedisConfig`·`SsePubSub`가 모호해진다. 그래서 릴레이 쪽 컴포넌트 안에서 팩토리를 직접 만들고 `afterPropertiesSet()`·`destroy()`로 수명을 관리한다. 대안은 기본 팩토리를 직접 정의해 `@Primary`로 두는 것인데, 그러면 자동 구성을 대체해야 하므로(설정 바인딩·TLS를 손으로 옮김) 쓰지 않는다.
    - 이렇게 하면 대기열 Lua·ShedLock·SSE 구독이 쓰는 기본 커넥션 팩토리의 타임아웃은 바뀌지 않는다. 대신 연결이 하나 늘고, 호스트·포트·TLS(`REDIS_SSL_ENABLED`) 설정을 기본 팩토리와 같은 프로퍼티에서 읽게 해야 한다. 테스트에서 전용 팩토리만 다른 Redis 컨테이너로 돌릴 수 있게(§2.3 대기 상한), 연결 설정은 생성자로 주입받는다(운영에서는 같은 프로퍼티 값을 넘긴다).
    - 구현 PR에서 확인할 것(추론 단계): 시간 초과 뒤 연결 상태, 그리고 커넥션 공유(`shareNativeConnection`) 여부에 따라 다른 명령이 같은 연결에서 밀리지 않는지.
  - **2순위(1순위가 안 될 때만) — 별도 실행기.** `Future.get(sendTimeoutMs)`가 시간 초과로 돌아와도 **Redis 명령은 계속 진행된다**(`cancel(true)`도 Lettuce 동기 호출을 멈춘다는 보장이 없다 — 추론). 그래서 다음 제한을 모두 둔다.
    - 스레드 1개
    - 대기열 0(`SynchronousQueue`)
    - 포화 시 거절(`AbortPolicy`)
    - 이전 호출이 안 끝났으면 새 발행을 넣지 않고 그 틱을 실패로 끝낸다.
    - 실행기 포화 횟수를 지표로 남긴다.
  - 전역 `spring.data.redis.timeout` 설정은 대기열 Lua·ShedLock·SSE 구독까지 같은 커넥션 팩토리를 쓰므로(`application.yml` 주석) **이 작업에서 하지 않는다**. 다만 지금 미설정(60초 추론) 자체가 다른 경로에도 위험이므로 **별도 과제로 분리**한다.
  - `outbox.send-timeout-ms` 키는 **유지**한다. 규칙 22가 `application.yml`에서 **처음 나오는** `send-timeout-ms`를 읽는데, 지금은 `outbox`가 `dlq`보다 앞이라 맞다. `dlq` 블록을 지우면 남는 것은 `outbox` 것 하나다.
- 지표: `flowticket.outbox.publish.ack`(이름 유지 — 대시보드 연속성), 설명만 "브로커 ACK" → "발행 확인 대기"로 바꾼다. `PUBLISH`의 수신자 수는 `Long`으로 받을 수 있다(확인됨 — §8). 받은 구독자가 0이면 `flowticket.outbox.publish.no_receivers` 카운터를 올린다(실패로 보지 않는다 — P2b-1 구현).
- 순서: 한 주문에 `order.paid`는 한 번이다. 릴레이는 생성 시각 순으로 한 건씩 동기 발행하므로 Kafka 파티션 키로 얻던 "같은 주문 내 순서"가 문제 될 이벤트가 없다(사실 — 아웃박스 이벤트 종류가 하나).

### 2.3 회귀 테스트 (P2b에 포함, CI Testcontainers Redis)
| 테스트 | 판정 |
|---|---|
| 정상 발행 | `sse:order` 구독자가 `Envelope{key=orderId, event="order.paid", data.orderId}`를 받고, 행이 `PUBLISHED`·`publishedAt` 설정 |
| 발행 → 마킹 순서 | **bean 릴레이**(프록시를 거쳐 `@Transactional`이 살아 있음)를 쓰되 테스트용 `LockProvider`(항상 락을 준다)로 ShedLock의 Redis 의존을 떼고, **실패하는 엄격 발행 목**을 넣는다. 또는 프록시 없이 만든 릴레이(`new OutboxRelay(...)`) 호출을 `TransactionTemplate`으로 감싼다 — 프록시 없는 릴레이를 그냥 부르면 ShedLock과 함께 `@Transactional`(`OutboxRelay.java:100`)도 빠져 `markAttemptFailed()`의 `attempts`·`lastError`가 DB에 반영되지 않는다(JPA 동작에서 끌어낸 추론). 판정은 **DB에서 다시 읽어** `attempts` 증가·`lastError` 기록·`PUBLISHED` 아님·배치 나머지 미발행. Redis 컨테이너 정지·`CLIENT PAUSE`는 쓰지 않는다 — `@SchedulerLock` 프록시는 직접 호출도 가로채므로(`KopisSyncLockIntegrationTest` 주석) 같은 Redis에 의존하는 락 획득이 먼저 실패하고, 공유 컨테이너를 멈추면 다른 테스트도 깨진다 |
| 대기 상한 | 1순위 설계(전용 팩토리의 Lettuce `commandTimeout`)는 **클라이언트가 시간을 재야** 검증된다. 그래서 릴레이 전용 팩토리를 **별도 Redis 컨테이너**에 연결하고 그 컨테이너에만 `CLIENT PAUSE`(또는 `DEBUG SLEEP`)를 건다 — 공유 컨테이너와 ShedLock은 영향이 없다. 판정: 발행이 `send-timeout-ms` 안에 예외로 돌아오고 행은 PENDING, 틱이 임차를 넘지 않음. 지연하는 발행 목은 2순위(별도 실행기)를 쓸 때만 유효하다 — 1순위에서는 목이 지연하면 릴레이가 그냥 기다릴 뿐이라 시간 제한을 검증하지 못한다 — 구현(P2b-1): 발행기 단위(`RedisOutboxPublisherTimeoutTest`)로 확인하고, 틱 상한은 규칙 22의 계산(틱 예산 + send-timeout ≤ 임차)에 맡긴다. 릴레이 전체를 지연 Redis에 붙인 틱 단위 테스트는 두지 않았다 |
| 지연 뒤 복구 | 지연이 풀린 뒤 같은 행이 두 번 발행될 수 있음을 허용하되, 릴레이 스레드 밖에 남은 작업이 없음(2순위 실행기를 쓰면 실행기 활성·대기 작업 수 0~1 상한) |
| ShedLock 락 실패 | 예외를 던지는 테스트용 `LockProvider`로 만든다. 기대: 틱이 건너뛰어지고 행은 그대로 PENDING(발행 시도 0, `attempts` 불변) — 재시도 경로와 구별되는 별도 사례. "건너뛰어진다"는 ShedLock 5.16.0 동작에서 끌어낸 추론이라 이 테스트로 확인한다 |
| 기본 팩토리 불변 | 컨텍스트의 `RedisConnectionFactory`·`StringRedisTemplate` bean이 각각 하나이고, 기본 팩토리의 명령 타임아웃이 전환 전과 같음 |
| 복구 | 실패하던 발행 목(또는 별도 컨테이너의 `CLIENT PAUSE`)이 풀린 뒤 다음 틱에 발행·마킹 |
| 결정적 실패 | payload 손상 행은 DEAD, 같은 aggregate 후속은 보류(기존 `OutboxPoisonEventIntegrationTest`를 Kafka 없이) |
| `broadcast()` 미사용 | `OutboxRelay`가 `OrderSseRegistry`에 의존하지 않음(생성자 의존성). `AfterCommit`은 정적 유틸이라 생성자로 막을 수 없으므로 **하네스 grep 규칙**으로 막는다(§5.4 신규 규칙 후보) |
| 기존 best-effort 경로 | `SseAfterCommitIntegrationTest` 그대로 통과(직접 알림은 여전히 커밋 후·실패 격리) |

## 3. 아웃박스를 왜 남기나 — 직접 발행과 비교
| | 아웃박스 유지 + 릴레이 → Redis(**채택**) | 커밋 후 직접 발행(`broadcast("order.paid")`) |
|---|---|---|
| 커밋 전 유령 알림 | 없음 | 없음(`AfterCommit`) |
| 커밋 직후 크래시·Redis 실패 | 행이 남아 재시도 | **유실**, 기록 없음(예외는 삼킴) |
| 실패 가시성 | `attempts`·`lastError`·DEAD, 지표 `flowticket_outbox_events{status}`·`oldest_pending.age`, 알림 `FlowticketOutboxDeadRows`·`FlowticketOutboxBacklog`(이미 있음) | 로그 한 줄 |
| 운영 조치 | `/admin/outbox` API로 requeue·discard(`AdminOutboxService:48-62`, 이미 있음) | 없음 |
| 지연 | 릴레이 주기(1초)만큼 늦다 | 커밋 즉시 |
| 비용 | 테이블·릴레이 틱·ShedLock·purge(이미 운영 중) | 없음 |
| 변경 크기 | 발행 대상 교체 | 결제 경로·테이블·관리 기능·지표·알림 철거 |
| 다시 도입할 조건이 생기면 | 릴레이 대상만 Redis Streams 등으로 교체 | 아웃박스부터 다시 |

- **채택 이유:** 변경이 가장 작고, 이미 운영 중인 기록·지표·알림·관리 기능을 그대로 쓰며, 결제와 묶인 이벤트의 "발생" 기록을 남긴다. 주 흐름이 `order.paid`에 기대지 않는다는 점([[ADR-028]] §2)은 직접 발행을 정당화하지만, 아웃박스를 걷어낼 이득(1초 지연 감소, 테이블 하나)이 기록을 잃는 대가보다 작다고 판단한다(판단 — 측정 근거 없음).
- **대안으로 남긴다:** 직접 발행은 "아웃박스 운영 비용이 문제가 되거나, `order.paid`의 기록이 필요 없다고 정할 때" 다시 본다.
- **비대칭(사실, 지금도 있음):** 아웃박스를 지나는 것은 `order.paid`뿐이다. `payment.vbank.deposited`(`PaymentService.java:329·364`), `order.cancelled`·`order.refunded`(`RefundService.java:217-218`, `RefundConverger.java:108-109`)는 `broadcast()`로 직접 나간다 — 크래시·Redis 실패 시 유실, 기록 없음.
  - 이 비대칭은 Kafka 제거와 무관하게 이미 있고, 제거가 바꾸지 않는다. 직접 알림 쪽은 "놓쳐도 재조회가 메운다"는 `AfterCommit` 주석의 전제로 운영돼 왔다.
  - 전부 아웃박스로 옮길지(결제·환불 경로에 DB 쓰기 추가), 그대로 둘지는 **별도 결정**으로 남긴다.
  - §4의 프론트 보완이 덮는 직접 알림은 `payment.vbank.deposited`뿐이다(`VBANK_WAITING`에서 탭 복귀 재조회). `order.cancelled`·`order.refunded`는 `useOrder`가 **구독하지 않는다**(`useOrder`의 `addEventListener`는 `order.paid`·`payment.vbank.deposited`·`order.failed`만). 이 두 알림의 유실은 §4와 무관하다.

## 4. 프론트 — SSE는 붙어 있는데 이벤트를 놓친 경우
- **현상(사실):** `useOrder.ts`는 마운트·`onopen`·이벤트 3종(`order.paid`·`payment.vbank.deposited`·`order.failed`) 때만 재조회한다. 폴링이 없다. SSE가 끊기지 않은 채 이벤트 하나가 빠지면(§1.1 ③·④·⑤ — 파드 구독 연결 순간 단절, `deliverLocal` 전송 실패 등) 화면은 사용자가 새로고침하거나 재연결이 일어날 때까지 이전 상태에 머문다. **지금 Kafka 경로에도 있는 문제**라 제거와 별개의 동반 보완(P1)으로 다룬다.
- **영향 범위(사실):** `useOrder`를 쓰는 화면은 결제(`orders/[id]/pay`)·완료·실패 3곳. 카드·간편결제는 결제 API 응답의 `orderStatus`로 화면을 넘겨 이 문제를 비켜 간다. 남는 것은 무통장 입금 대기(`VBANK_WAITING`)와 PG 리다이렉트 직후 `PENDING`.
- **선택지:**
  | 안 | 내용 | 대가 |
  |---|---|---|
  | 수용 | 지금 그대로 | 입금 확인·결제 완료가 화면에 늦게 반영될 수 있음(빈도 미측정) |
  | **A. 탭 복귀 재조회(권장)** | `visibilitychange`(visible)·`focus` 때 상태가 `PENDING`·`VBANK_WAITING`이면 `refresh()` | 요청은 사용자 행동당 1회. 탭을 보고 있는 동안의 유실은 못 메움 |
  | **B. 한정 재조회(권장, A와 함께)** | 상태가 `PENDING`일 때만 일정 간격 재조회를 **상한 시간 안에서** 돌리고, 종료 상태가 되면 멈춘다. `VBANK_WAITING`은 입금까지 길 수 있어 A만 적용 | 서버 조회 증가. 간격·상한은 구현 PR에서 정하고, 폭증 시 부하를 계산으로 보인다(대기열을 통과한 사용자만 결제 화면에 오므로 동시 결제 화면 수 × 간격 역수) |
  | C. 상시 폴링 | 상태와 무관하게 주기 조회 | SSE를 둔 이유가 약해진다 |
- **권장: A + B.** 이유: 유실이 어느 구간에서 나든(Kafka 유무 무관) 사용자 화면을 서버 진실로 되돌리는 마지막 장치가 프론트에 하나 있어야 하고, 범위를 진행 중 상태로 좁히면 비용이 상한으로 묶인다. 최종 채택 여부·수치는 P1 PR에서 사용자 확인을 받는다.
- **결정(사용자 2026-10-14): A + B, 5초 간격·주문 만료까지** — P1에서 구현(`useOrder.ts`).
  - PENDING: 탭이 보이는 동안 5초마다, `expiresAt`까지 재조회한다. 탭이 숨으면 멈춘다.
  - PENDING·VBANK_WAITING: `visibilitychange`(visible)·`focus` 때 1회 재조회한다. 둘이 연달아 오면 1초 안에서 한 번으로 묶는다.
  - 종료 상태가 되면 아무것도 하지 않는다.
  - 부하 추정(예측, 상한 아님): 동시 PENDING 주문이 정원(100) 수준이면 공연당 약 20 req/s. 입장 슬롯은 선점·주문으로 반환되지 않아(`admit-ttl` 300초 또는 `leave`로만), 주문 만료(선점 시각 + `hold-ttl` 300초)와 입장창이 겹치는 것만으로 약 2배가 될 수 있다. 1인 다중 홀드(최대 4주문 — `SeatService` 1인 4매, 주문은 홀드 단위), leave 후 재입장, 다중 창은 더 늘릴 수 있으나 이 추정에 넣지 않았다(모두 코드 경로에서 끌어낸 추론). 주기 재조회는 주문 하나에 만료까지 약 60회이고, 탭 복귀 재조회는 이와 별도다.
  - 응답이 5초보다 느리면 앞선 요청이 끝날 때까지 다음 주기 재조회를 내지 않는다. 매 틱 새로 내면 순번 가드가 앞선 응답을 모두 버린다(G1 리뷰 재현).
  - 효과 범위: 결제 화면은 PAID일 때만 다음 화면으로 넘어간다(`pay/page.tsx`). 재조회로 FAILED·EXPIRED를 받아도 화면 이동은 없다(기존 동작).
- **테스트(P1):** vitest — ① `PENDING`에서 간격마다 재조회, 상한 도달 시 중단 ② `PAID`·`FAILED` 등 종료 상태가 되면 타이머 해제 ③ 탭 복귀 시 1회 재조회, 종료 상태면 없음 ④ 언마운트 시 타이머 정리 ⑤ 기존 순번 가드(`latest`)로 늦게 온 `PENDING` 응답이 `PAID`를 덮지 않음. 하네스 프론트 규칙 7(EventSource면 `onopen`/폴링)은 그대로 만족.
  - 구현된 테스트(`tests/useOrder.refetch.test.ts`, 15개): 위 ①~⑤에 더해 느린 응답(6초)에도 PAID 반영(진행 중이면 틱 건너뜀), 주기 재조회 실패 뒤 다음 틱 재시도, 숨은 탭 마운트, 만료 뒤 복귀 1회, focus 1초 dedup, PENDING→VBANK_WAITING 주기 멈춤, 토큰 없음.

## 5. 의존성 목록 — 영역별로 무엇을 하나
"제거"·"교체"·"유지"·"문구"로 나눈다. **유지**로 적은 것은 지우지 않는다. 각 줄의 PR은 §6 참조.

### 5.1 백엔드 코드
| 대상 | 처리 | PR |
|---|---|---|
| `outbox/service/OutboxRelay` | 교체 — `KafkaTemplate` → 엄격 발행(§2). 클래스 주석의 "Kafka"·"소비자 멱등" 서술 갱신 | P2b |
| `global/sse/SsePubSub` | 엄격 발행 메서드 추가, 기존 `publish()` 유지. 주석 "Kafka를 대체하지 않는다" 갱신 | P2b |
| `order/event/OrderEventConsumer` | 제거(멱등 SETNX 포함) | P2b |
| `order/event/OrderEvent` | **유지**(아웃박스 payload 형식). 주석의 "Kafka 토픽 페이로드"만 갱신 | P2b |
| `global/config/KafkaConfig`, `global/kafka/KafkaHealthService`, `global/kafka/KafkaListenerStarter` | 제거 | P2b |
| `dlq/` 패키지 전체(`DlqConsumer`·`AdminDlqController`·`DlqMessage`·`DlqStatus`·dto·repository·service) | 제거. 엔티티를 지워도 테이블은 N+1까지 남긴다(§5.3) | P2b |
| `admin/service/AdminDashboardService`·`admin/dto/AdminDashboardResponse` | P2a: 아웃박스 필드(`outboxPending`·`outboxDead`) **추가**. P2b: `kafkaConnected`·`dlqPending` 제거 | P2a·P2b |
| `alert/*`(`AdminAlertService`·`AlertSettings`·`AlertSettingsResponse`·`UpdateAlertRequest`·`AdminAlertController`) | P2a: 기준을 아웃박스 DEAD로 바꾼 새 임계치(`outboxDeadThreshold`) 필드 **추가**. P2b: DLQ 필드 제거, 엔티티에서 `dlq_pending_threshold` 매핑 제거 | P2a·P2b |
| `global/metrics/OperationalMetrics` | `flowticket.dlq.messages` 게이지 제거. 아웃박스 게이지는 유지 | P2b |
| `global/sse/AfterCommit` | **유지**. 주석의 "Kafka 소비 등" 예시만 갱신 | P2b |
| `global/common/PageQuery` | 주석 "DLQ" 문구 | P2b |
| `outbox/domain/OutboxStatus`(`:5` "PUBLISHED=Kafka 발행 성공"), `outbox/domain/OutboxEvent`(`:17`, `:79` "반드시 Kafka send 성공을 확인한 뒤"), `outbox/dto/OutboxEventSummary`(`:11`), `outbox/service/AdminOutboxService`(`:44`) | 주석을 §1.2의 새 의미("Redis 발행 호출 성공")로 — **PUBLISHED 의미가 코드 주석에 남는 곳**이라 빠뜨리면 안 된다 | P2b |
| `build.gradle.kts` | `spring-kafka`·`spring-kafka-test`·`testcontainers:kafka` 제거 | P2b |
| `application.yml` | `spring.kafka`·`kafka.topic`·`dlq` 블록 제거. `outbox.*` 유지(주석 "Kafka" → "Redis"). readiness 그룹은 이미 Kafka 미포함(`readinessState, db, redis`) — 변경 없음 | P2b |
| (나) 경로였다면 N₀가 더한 설정 — `outbox.relay-target`(`application.yml`) | P2b에서 제거(아래 §5.6의 configmap 키·annotation과 **같은 PR** — 하네스 k8s 규칙 1) | P2b |

### 5.2 프론트·E2E
| 대상 | 처리 | PR |
|---|---|---|
| `features/order/hooks/useOrder.ts` + 테스트 | §4 보완 | P1 |
| `app/admin/page.tsx`(대시보드 Kafka 연결·DLQ 적체 칸, 경고 배너) | 아웃박스 PENDING·DEAD로 교체(새 필드 읽기) | P2a |
| `app/admin/alerts/page.tsx` | 임계치 대상을 아웃박스 DEAD로 | P2a |
| `app/admin/outbox/page.tsx`(신규) | 기존 `AdminOutboxController`(목록·requeue·discard)를 쓰는 화면. 알림 `FlowticketOutboxDeadRows`의 안내가 이미 `/admin/outbox`를 가리킨다 | P2a |
| `app/admin/dlq/page.tsx`, `layout.tsx` 메뉴, `features/admin/api/admin.ts`·`hooks/useAdmin.ts`·`queryKeys.ts`·`components/ui.tsx`(DLQ 상태 pill) | 제거 | P2b |
| `types/contracts.ts` `DlqStatus` | 제거(하네스 FE2 enum 미러 — `contracts/enums.yaml`과 같은 PR) | P2b |
| `tests/authCache.test.ts`·`tests/authBootstrap.test.ts` | DLQ 경로를 예시로 쓰는 부분을 다른 admin 경로로 | P2b |
| `e2e/tests/admin.spec.ts` | DLQ 시나리오 → 아웃박스 화면 시나리오 | P2a(추가)·P2b(DLQ 삭제) |

### 5.3 DB 마이그레이션 (Expand-Contract)
| 파일 | 처리 | PR |
|---|---|---|
| `V12__dlq_messages.sql`, `V13__alert_settings.sql`, `V14__outbox_events.sql`, `V18__outbox_dead.sql` | **수정·삭제 금지**(Flyway 체크섬) | — |
| `V20__alert_outbox_threshold.sql`(신규) | `ALTER TABLE alert_settings ADD COLUMN outbox_dead_threshold INTEGER NOT NULL DEFAULT 1` — 추가만이라 구·신 Pod 공존 가능(하네스 BE14 패턴에 걸리지 않음: `ADD COLUMN`은 목록 밖, 사실) | P2a |
| `V21__drop_dlq.sql`(신규) | `DROP TABLE dlq_messages`, `ALTER TABLE alert_settings DROP COLUMN dlq_pending_threshold` + `-- harness:allow-destructive-ddl: N(P2b)에서 엔티티·매핑 제거 완료, N-1 Pod 없음 확인 뒤` | P4 = N+1 |
| `docs/db/dlq_messages.md` | **유지**(하네스 백엔드 규칙 9: `CREATE TABLE` ↔ 문서 존재 — V12가 남으므로). "V21에서 삭제됨" 표기 | P4 |
| `docs/db/alert_settings.md` | 컬럼 추가·삭제 반영 | P2a·P4 |
| `docs/db/outbox_events.md`(`:9` "Kafka로 발행하고 성공 후 PUBLISHED", `:17` "Kafka 파티션 키 → 주문별 순서") | §1.2 의미·§2.2 순서 서술로 갱신(테이블 변경 없음) | P2b |
- 왜 두 릴리스인가: `ddl-auto: validate`라 엔티티가 매핑하는 테이블·컬럼이 없으면 Pod가 기동하지 못한다. N 배포 중에는 N-1 Pod가 `DlqMessage`·`dlq_pending_threshold`를 아직 매핑하므로 N에서 지우면 롤링 중 구버전이 깨진다([zero-downtime-deployment.md](zero-downtime-deployment.md) §8).
- **N+1 이후에는 N-1로 롤백할 수 없다**(테이블이 없어 기동 실패). P4는 N이 안정된 뒤에만 한다.

### 5.4 계약·하네스
| 대상 | 처리 | PR |
|---|---|---|
| `contracts/allowed-stack.yaml` | `org.springframework.kafka`·`org.testcontainers:kafka` 제거(BE1) | P2b |
| `contracts/api.yaml:58-60` | `/admin/dlq` 3개 제거(BE5·FE4·FE6 — 프론트 호출 제거와 같은 PR). 대시보드·알림 응답 필드는 `api.yaml` 범위 밖(경로만 관리) | P2b |
| `contracts/enums.yaml` | `DlqStatus` 제거. `OutboxStatus` 주석 "PUBLISHED=Kafka 발행 성공" → "Redis 발행 호출 성공(§1.2)" | P2b |
| `contracts/events.yaml`·`schema/events.schema.json` | `dlq_suffix` 제거. 스키마가 `additionalProperties:false`라 **스키마에서만 먼저 지우면 yaml이 실패**한다 — yaml을 먼저(또는 같은 PR에서 함께) 지운다. 머리 주석 "Kafka 이벤트 계약" → "SSE 이벤트 계약". 이벤트 목록은 그대로 | P2b |
| `contracts/README.md` | `events.yaml` 설명 갱신 | P2b |
| `harness/backend/check.mjs` 규칙 22 | 로직 유지. 주석 "소비자 멱등이 중복을 흡수" 갱신 | P2b |
| `harness/alerts/rule-tests.yml` | `FlowticketDlqBacklog`·`FlowticketConsumerLag` 테스트 제거(규칙과 같은 PR — promtool 짝 검사) | P2b |
| `harness/fixtures/violations/*`의 Kafka 문자열(`docs-absent-but-exists/kafka/kafka.yaml`·`README.md`, `fe-*/contracts.ts`의 `DlqStatus`, `fe-missing-required-event/events.yaml:29`의 `dlq_suffix`, `k8s-prescale-with-split/api-hpa.yaml` 주석) | 각 규칙이 여전히 그 fixture로 실패하는지 메타테스트로 확인. 규칙 판정과 무관한 문자열이면 **유지**, `DlqStatus` 미러를 쓰는 fixture는 다른 enum으로 교체 | P2b·P5 |
| 신규 규칙 후보 | "`OutboxRelay`는 `OrderSseRegistry`·`AfterCommit`·`SsePubSub.publish(`(삼키는 메서드)를 쓰지 않는다" — 추가하면 위반 fixture와 `meta-test.mjs` 등록을 함께 | P2b |

### 5.5 테스트(백엔드)
| 대상 | 처리 |
|---|---|
| `order/event/OrderEventKafkaIntegrationTest` | 제거 → `OutboxRedisDeliveryIntegrationTest`(§2.3)로 대체 |
| `outbox/OutboxDeliveryIntegrationTest`·`OutboxPoisonEventIntegrationTest`·`AdminOutboxIntegrationTest` | 각 테스트가 띄우는 Kafka 컨테이너·소비 검증 → Redis 구독 검증 |
| `support/IntegrationTestSupport` | 컨테이너는 없다. `spring.kafka.*` 속성(`:35-47` — 자동 생성 끄기·연결 불가 주소)과 "Kafka 없음" 주석 제거 |
| `support/TestContainers` | 컨테이너는 Postgres·Redis만. 주석(`:10` "비-Kafka 통합테스트가 공유", `:15` "Kafka 테스트는 공유하지 않는다")만 갱신 |
| `dlq/DlqIntegrationTest`, `global/kafka/*Test` 3개 | 제거 |
| `global/common/PaginationValidationTest` | DLQ 목록 → 아웃박스 목록으로 |
| `admin/AdminAuthIntegrationTest`·`alert/AdminAlertIntegrationTest`·`global/metrics/OperationalMetricsTest` | DLQ 의존 제거, 새 필드 검증 |
| `global/sse/SseAfterCommitIntegrationTest` | 변경 없음(직접 알림 경로 회귀 확인용) |
| `benchmarks/*.json`(Kafka 시기 측정 원자료) | **유지**(역사 기록) |

### 5.6 k8s·모니터링
| 대상 | 처리 | PR |
|---|---|---|
| `k8s/base/configmap.yaml` `KAFKA_*` 3개 | 제거 — **`application.yml`과 같은 PR**(하네스 k8s 규칙 1: env는 앱이 읽어야 한다) | P2b |
| (나) 경로였다면 configmap `OUTBOX_RELAY_TARGET`, Pod 템플릿 annotation `flowticket.io/relay-target`(api: `k8s/base`, 대기열: `k8s/components/queue-split`) | P2b에서 제거 | P2b |
| `k8s/monitoring/prometheusrule-flowticket.yaml` | `FlowticketDlqBacklog`·`FlowticketConsumerLag` 제거(앱 지표가 P2b에서 사라짐). `FlowticketOutboxBacklog` 설명 "브로커 장애·Kafka 상태" → "Redis 장애·릴레이 로그" | P2b |
| `k8s/monitoring/dashboard-flowticket.yaml` Kafka 행(소비자 lag·리스너·리밸런스) | 제거, 아웃박스 발행 지연 패널로 대체 여부는 구현 시 판단 | P2b |
| `k8s/kafka/`(`kafka.yaml`·`kafka-metrics-configmap.yaml`·`kustomization.yaml`) | 제거 | P3 |
| `k8s/monitoring/podmonitor-kafka.yaml`·`dashboard-kafka.yaml`·`kustomization.yaml` 항목 | 제거 | P3 |
| `k8s/monitoring/kube-prometheus-stack.values.yaml` 주석(DLQ 적체) | 문구 | P3 |
| `k8s/base/api-hpa.yaml`·`web-priorityclass.yaml`, `k8s/cluster-autoscaler/values.yaml` 주석 | 문구(Kafka 예시 삭제). 동작 변경 없음 | P3 |
| `k8s/components/queue-split/` | **유지**(IMP-033·ADR-027 실험 구성). A'/B 전환 스크립트(`ab-switch.sh` 등)는 저장소 밖 세션 기록(`artifacts/loadtest/20261014-ab/`, IMP-033 머리)이라 이 작업 범위 밖 | — |
| `k8s/README.md`(`:119` readiness·Kafka, `:142` Strimzi Kafka CR 행) | 문구 | P3 |
- 주의(사실): Strimzi·Kafka CR·모니터링은 ArgoCD 추적 밖이다. Strimzi 운영자는 `bring-up.sh` 4단계에서 helm으로 설치하고(`:163-166`), Kafka CR과 모니터링은 6단계에서 `kubectl apply -k k8s/kafka`·`k8s/monitoring`으로 올린다. 그래서 저장소에서 지워도 **이미 떠 있는 클러스터에서는 사라지지 않는다** — 실제 삭제는 측정 세션의 운영 삭제 게이트(§6.2)로 하고, 저장소 P3는 관찰 기간(§6.3) 뒤에 머지한다.

### 5.7 Terraform·스크립트·로컬
| 대상 | 처리 | PR |
|---|---|---|
| `scripts/bring-up.sh` | Strimzi 차트·`kafka` 네임스페이스·`k8s/kafka` 적용 단계 제거 | P3 |
| `scripts/tear-down.sh` | Kafka CR·NodePool 삭제 단계 제거. **PVC·EBS 잔존 확인 단계는 유지**(Prometheus PVC가 남는다) | P3 |
| `scripts/resilience-test.sh` | 브로커 장애 시나리오 제거(나머지 유지) | P3 |
| `scripts/node-autoscale-test.sh` | Kafka 언급 정리 | P3 |
| `infra/docker-compose.yml`·`docker-compose.prod.yml` | `kafka` 서비스·앱의 `KAFKA_*` env 제거 | P3 |
| `infra/k6/README.md`(`:10` compose kafka, `:22` 예정 `failure-dlq.js`), `AGENTS.md`(`:15` 스택, `:29`·`:78` compose kafka) | 문구 — 예정 시나리오는 삭제 | P3 |
| `infra/terraform/.../modules/eks/main.tf` | **EBS CSI 애드온 유지**(Prometheus PVC gp2 20Gi가 쓴다 — `kube-prometheus-stack.values.yaml:93-100`). 주석 `:224` "Kafka 브로커 PVC"와 `:3-5`(AZ별 노드 그룹의 이유 = Kafka 브로커 EBS)를 갱신 — **AZ별 노드 그룹은 유지**한다. EBS가 AZ에 묶이는 성질은 Prometheus PVC에도 그대로라 근거가 사라지지 않는다 | P3 |
| `infra/terraform/.../environments/demo/variables.tf`(3 AZ 설명), `modules/vpc/variables.tf` 주석, `demo/main.tf` destroy 순서 주석 | **3 AZ·NAT·RDS Multi-AZ 유지**. 근거 문구에서 Kafka를 빼고 "§7.4 재평가 전까지 변경 범위를 줄이려고 유지"로 적는다(G1은 노드 장애라 3 AZ의 근거가 되지 않는다 — §7.4) | P3 |
| `infra/terraform/platform/README.md` | Strimzi·Kafka CR 단계 정리 | P5 |

### 5.8 문서
| 대상 | 처리 |
|---|---|
| [[ADR-008]]·[[ADR-010]] | 상태에 "ADR-028로 대체됨(Kafka 부분)" — 본문 **보존**. ADR-010은 아웃박스 자체는 유효, 발행 대상만 바뀜 |
| [[IMP-016]]·[[TS-020]]·[[TS-023]]·[[TS-026]] 등 Kafka 시기 기록 | **보존**, 머리에 "Kafka 제거 전 기록" 한 줄 |
| `docs/deployment/_index.md` ① 다이어그램, `app-changes-for-k8s-kafka.md`, `aws-eks-deploy-plan.md`, `workload-responsibilities.md`(Kafka 리스너 행) | 현재 구조로 갱신, 과거 서술은 "제거 전" 표기 |
| README·`docs/HARNESS.md`·`docs/screens`(관리자 DLQ 화면) | 현재 구조 반영 |
| `assets/screens/운영자_DLQ 재처리 센터.png` 등 | **유지**(당시 화면 기록) |
| A'/B 실험 기록(IMP-033·ADR-027, 저장소 밖 세션 원자료 `artifacts/loadtest/20261014-ab/`) | **건드리지 않는다** |

## 6. 변경 순서 — PR별 CI·회귀·게이트·롤백
원칙: **새 경로가 CI와 운영 게이트로 확인되기 전에는 Kafka 인프라를 지우지 않는다.** 앱 전환(P2b)이 Kafka 인프라를 쓰지 않게 된 뒤에도, 롤백 경로(N-1)가 Kafka를 필요로 하므로 운영 삭제는 별도 게이트(§6.2)를 통과한 뒤에 하고, 저장소 P3는 관찰 기간(§6.3) 뒤에 머지한다.

전체 진행 순서(사용자 결정 2026-10-14). 각 단계는 CI·하네스 → 독립 검토(G1 리뷰) PASS를 거쳐 다음으로 간다. (이 문서에서 "G1 리뷰"는 PR 독립 검토, "가용성 G1"은 노드 1대 장애 목표 — [operating-requirements.md](operating-requirements.md) — 를 뜻한다.)
1. 랭킹 감쇠 중복 실행 재현·필요 시 수정(Kafka와 무관한 별도 PR — ShedLock `PT0S` 문제) — **완료**: CI 재현(0.64, 기대 0.8)·경과 시간 기반 감쇠로 수정([TS-051](../troubleshooting/TS-051-ranking-decay-repeated-per-pod.md))
2. P1 → P2a → P2b(클러스터 불필요) — P1 완료(#409), P2a 완료(#410), P2b-1 완료(#411), P2b-2 진행 중. P2b는 둘로 나눈다: **P2b-1**(동작 변경 — 릴레이를 Redis 엄격 발행으로, 회귀 테스트·하네스 규칙 23) → **P2b-2**(삭제 — Kafka·DLQ 코드·계약·화면·설정·모니터링, 런타임 동작 변화 없음). 나누는 이유: 동작이 바뀌는 부분을 작게 따로 검증하기 위해서다. 둘 다 측정 세션 전에 끝나므로 순서 원칙은 같다.
3. 자원 산정·측정 계획 문서(후보·계획만 — 최종 requests·Hikari 풀·HPA·노드 수는 B₁·B′ 반복·가용성 G1 시험을 거친 뒤 확정) + P3a + 저장소 P3 준비
4. 측정 세션: N 게이트(§6.1) → Kafka 운영 삭제 게이트(§6.2) → B₁ → B′(반복 조정 가능) → 가용성 G1 시험(최종 B′ 후보) — §7.5
5. 관찰 기간 뒤 저장소 P3 머지, 충분히 안정된 뒤 P4, 마지막 P5


| PR | 내용 | CI·검증 | 회귀 확인 | 롤백 | 다음으로 가는 조건 |
|---|---|---|---|---|---|
| **P0**(#406) | ADR-028 Accepted + 이 문서 | `npm run harness:check` — 백엔드 규칙 15는 Java·SQL 주석의 ADR/TS/IMP **번호**가 실제 문서를 가리키는지 검사한다. P2b 코드 주석이 [[ADR-028]]을 인용하려면 ADR-028이 먼저 있어야 하므로 P0가 앞선다 | — | revert | G1 리뷰 PASS·머지 |
| **P1** | `useOrder` 탭 복귀·한정 재조회(§4) — Kafka와 독립 | 프론트 typecheck·lint·build, vitest, 하네스 | 결제·완료 E2E | revert | 사용자 확인(수치), G1 리뷰 PASS |
| **P2a** | 아웃박스 기반 관리 필드 **추가**(대시보드·알림), V20, `/admin/outbox` 화면, 대시보드·알림 화면이 새 필드를 읽음. 기존 DLQ 필드·화면 유지 | 백엔드 CI(Testcontainers — 아직 Kafka 포함), 프론트 3종, 하네스(BE14·FE6) | `AdminAlertIntegrationTest`·`AdminOutboxIntegrationTest`, `e2e admin.spec.ts` | revert(V20은 추가 컬럼이라 남아도 무해) | G1 리뷰 PASS |
| **N₀**(떠 있는 클러스터에서 전환할 때만) | 릴레이 발행 대상 설정(`outbox.relay-target`, 기본 `kafka`) + Redis 엄격 발행 경로 추가. Kafka 소비자·DLQ·설정은 **그대로**. 새 env 키(`OUTBOX_RELAY_TARGET`)는 `application.yml`과 configmap에 **같은 PR**로 넣는다(하네스 k8s 규칙 1) | 백엔드 CI(Kafka 컨테이너 포함), 하네스 | 대상별 통합 테스트(`kafka`: 기존 `OrderEventKafkaIntegrationTest` 그대로 통과 = 기본값에서 동작 불변 / `redis`: §2.3 표) | PR revert(기본값이 `kafka`라 배포만으로는 동작이 바뀌지 않는다) | 아래 (나) 2~3단계 |
| **P2b = 릴리스 N**(P2b-1 + P2b-2) | 릴레이 → Redis 엄격 발행(떠 있는 클러스터에서 전환하면 앞에 N₀·설정 전환 단계 — 아래 (나)), 소비자·DLQ·Kafka 설정·의존성 제거, 계약·하네스·configmap·앱 지표 규칙 동반 제거, 테스트 교체(§5) | 백엔드 CI(Kafka 컨테이너 없이), 프론트 3종, 하네스 전체(+ 신규 규칙 메타테스트) | §2.3 표 전부, `SseAfterCommitIntegrationTest`, 결제 E2E(`order.paid` SSE 수신 → 완료 화면) | **P2b PR을 revert**해 이미지와 configmap(`KAFKA_*`)을 함께 되돌린다(ArgoCD 동기화). 이미지만 N-1로 바꾸면 안 된다 — api는 configmap을 `envFrom`으로 받으므로(`api-deployment.yaml:62-63`) `KAFKA_BOOTSTRAP_SERVERS` 없이 `localhost:9092` 기본값으로 떠서 Kafka 경로가 조용히 죽는다. **Kafka 인프라가 남아 있어야 가능**(그래서 운영 삭제(§6.2)를 N 게이트 뒤 별도 게이트로 둔다) | 아래 운영 게이트 |
| **P3a**(세션 전) | `bring-up.sh` 스위치 `SKIP_KAFKA=1` — 건너뛰는 것: `kafka` 네임스페이스 생성(`:163`), Strimzi `helm --wait`(`:164-166`, 운영자만 기다린다), `kubectl apply -k k8s/kafka`(`:185`, 대기 없음), 그리고 **Kafka 모니터링 두 리소스**(`podmonitor-kafka.yaml`·`dashboard-kafka.yaml` — 지금은 `k8s/monitoring/kustomization.yaml`에 들어 있어 `:186`이 함께 적용한다. 별도 kustomization으로 분리해 Kafka 단계에서만 적용). **기술적 가드**: 운영 삭제 때 표식(ConfigMap `kafka-removed`, `kube-system`)을 남기고, 스위치 없이 bring-up하면 표식을 보고 Kafka 단계 직전에 멈춘다(재설치 의도면 `FORCE_KAFKA=1`). **가드는 같은 클러스터에서 다시 실행할 때만 동작한다** — 표식이 클러스터 안에 있어, 철거 뒤 새 클러스터에서는 사라진다. 새 클러스터는 `SKIP_KAFKA=1`(사람의 절차, `session.log` 기록)에 의존한다. 클러스터 밖 표식(로컬 gitignore 파일 등)은 P3a에서 검토한다. 기본값은 지금처럼 설치(세션 시작 시 N 게이트를 Kafka가 있는 상태에서 보기 위해) | 하네스, `bash -n` | 스위치·표식 없을 때 적용 리소스가 이전과 같음(분리한 Kafka 모니터링 포함) | revert | G1 리뷰 PASS·머지 |
| 운영 게이트 ① N 게이트 | (세션) §6.1 | — | — | P2b revert(Kafka 인프라 있음) | §6.1 통과 |
| 운영 게이트 ② **Kafka 운영 삭제** | (세션) §6.2 — 통과하면 떠 있는 클러스터에서 Strimzi·Kafka CR·PVC·EBS를 지우고 절차·결과를 기록 | — | — | **즉시 롤백 불가** — Kafka 재설치(§6.2의 명령 순서 — bring-up에는 Kafka 단계만 돌리는 기능이 없다) + P2b revert가 필요 | §6.2 통과·삭제 기록 |
| **P3**(저장소) | `k8s/kafka`·Kafka 모니터링·bring-up/tear-down Strimzi 단계(P3a 스위치 포함)·compose·장애 시험·Terraform/k8s 주석 삭제. **세션 전에 준비하고, 관찰 기간 뒤 머지**한다 | 하네스(k8s 규칙), `terraform validate`(문법), `terraform plan`이 변경 없음(주석만 바뀌었는지 — 클러스터를 올릴 때 확인) | 다음 bring-up에서 Kafka 없이 전 단계 통과, 결제 E2E | revert(이후 bring-up이 다시 Kafka를 설치) | 관찰 기간(§6.3) 동안 N 안정 |
| **P4 = N+1**(충분히 안정된 뒤) | V21 `DROP TABLE dlq_messages`·`DROP COLUMN dlq_pending_threshold`(+ allow 주석), `docs/db` 표기 | 백엔드 CI(Flyway 적용·`validate` 통과), 하네스 BE14(사유 있는 allow) | 관리 화면·알림 API | **N-1로 롤백 불가**. N으로는 가능 | — |
| **P5** | 문서 정리(§5.8), Terraform README, 남은 fixture 문구 | 하네스 docs | — | revert | — |

**운영 게이트(P2b 배포 ↔ Kafka 운영 삭제 사이).** 지금은 클러스터가 내려가 있다(2026-10-14 철거 — IMP-033 머리, 잔여 리소스 0은 저장소 밖 세션 기록 `session.log`). 전환 방식은 두 가지인데, 기준선 (a)를 택했으므로 **(가)로 진행한다**(사용자 결정 2026-10-14). (나)는 떠 있는 클러스터에서 전환해야 할 경우를 위한 기록으로 남긴다.

**(가) 채택 — 클러스터가 없는 동안 전환한다.**
- P2b를 클러스터가 내려가 있을 때 머지하면, 다음 bring-up이 N을 처음부터 올린다. Kafka 경로와 Redis 경로가 **공존하는 구간이 없다**. 그래서 아래 (나)의 문제(이중 발행 경로·미소비 메시지)가 생기지 않는다.
- 세션 시작 시 bring-up은 Kafka를 계속 띄우지만(P3 머지 전) N은 쓰지 않는다(`KAFKA_*` env도 없음). 롤백(P2b revert)할 때 그 Kafka를 다시 쓴다.
- 세션 순서는 §6.1 → §6.2 → §7.5(B₁ → B′ → 가용성 G1 시험)다.

**(나) 떠 있는 클러스터에서 전환해야 할 때 — 발행 주체 전환을 단계로 나눈다.**
- **ShedLock은 발행 경로의 단일성을 보장하지 않는다.**
  - `outbox-relay` 락은 `lockAtLeastFor = "PT0S"`라, 틱이 빨리 끝나면 같은 주기 안에 다른 Pod가 락을 다시 잡을 수 있다(사실 — `OutboxRelay.java:99`).
  - 락이 막는 것은 "동시에 실행"이지 "주기당 한 번"이 아니다. 그래서 롤링 중에는 두 경로가 번갈아, 또는 임차가 끝나면 겹쳐 돈다.
    - N-1 Pod: 아웃박스 → Kafka → 소비자 → Redis
    - N Pod: 아웃박스 → Redis
  - 같은 행이 두 경로로 나갈 수도 있다. 예를 들어 락 해제가 트랜잭션 커밋보다 먼저 일어나면 다른 Pod가 아직 `PENDING`으로 보이는 행을 다시 집는다. 이 순서는 `@SchedulerLock`·`@Transactional` 프록시 순서에 달렸고 **확인하지 않았다**.
  - 중복 자체는 재조회 1회로 끝나지만, 전환 절차는 단일성을 전제로 짜지 않는다.
- **미소비 메시지:** "lag 0 확인"과 "마지막 N-1 Pod 종료" 사이에 N-1 릴레이가 Kafka로 더 보내면, 그 메시지는 소비자 없이 남아 유실된다. 그래서 **Kafka로 보내는 주체가 하나도 없는 상태**에서 lag 0을 확인해야 한다.
- 절차(이렇게 하면 릴리스가 하나 늘어난다).
  1. **N₀ — 경로를 고를 수 있는 코드.** 릴레이 발행 대상을 설정(`outbox.relay-target: kafka|redis`, 기본 `kafka`)으로 고르게 하고, Kafka 소비자·DLQ는 **그대로 둔다**. 배포가 끝나도 동작은 지금과 같다.
  2. **전환 — 설정을 `redis`로 + 재시작.** configmap `flowticket-api-config`는 `configMapGenerator`가 아닌 일반 리소스라(`k8s/base/kustomization.yaml:7`) 값만 바꿔서는 롤아웃이 일어나지 않고, 떠 있는 Pod는 `envFrom`으로 받은 예전 값을 계속 쓴다. 그래서 **같은 PR에서 api Deployment(`k8s/base`)와, `queue-split` component를 켠 경우 대기열 Deployment(component 쪽 패치)의 Pod 템플릿 annotation(예: `flowticket.io/relay-target: redis`)도 바꿔** 롤아웃을 일으킨다(Git 경유라 ArgoCD selfHeal과 충돌하지 않는다). 결제가 적은 시간에 머지한다. 롤링 중 Kafka로 나간 메시지는 **모든 Pod에 아직 있는 소비자**가 계속 읽는다. 그래서 마지막 Pod가 바뀌어도 소비자가 사라지지 않는다.
  3. **배출 게이트(명시적)** — 다음을 모두 확인한다.
     - 모든 Pod의 설정이 `redis`다. 릴레이 로그·지표의 발행 대상으로 확인한다.
     - Kafka `order-events`의 최신 오프셋(Exporter `kafka_topic_partition_current_offset{topic="order-events"}`)이 일정 시간 증가하지 않는다. 보내는 주체가 없다는 뜻이다.
     - 그 상태에서 소비자 lag이 0이다(`kafka_consumergroup_lag`, group `flowticket` — Exporter 지표).
     - `order-events.DLT` 신규 0, `dlq_messages` PENDING 0. 남은 것은 기존 `/admin/dlq`로 처리한다.
  4. **N — Kafka 코드 제거(P2b).** 이 시점에는 Kafka로 들어오는 메시지가 없으므로 소비자를 지워도 잃을 것이 없다.
- 대안(권장하지 않음): 단계 없이 N을 바로 롤링하고, 결제가 적은 시간 + 직전 lag 0으로 유실을 줄인다. lag 확인과 마지막 Pod 종료 사이의 유실을 **막지 못한다**.

### 6.1 운영 게이트 ① — N 게이트(세션, Kafka 인프라는 아직 있음)
- 모든 api·대기열 Pod 이미지가 N이고, 이전 ReplicaSet의 Pod가 0개다(`kubectl get rs`에서 이전 리비전 `READY 0`). (가)는 처음부터 N이라 해당 Pod가 없어야 정상이다.
- 결제 E2E에서 `order.paid` SSE를 수신하고, 그 아웃박스 행이 `PUBLISHED`가 된다.
- 아웃박스 PENDING 0·`oldest_pending.age` 정상·DEAD 증가 없음.
- 발행 실패가 계속되지 않는다 — 로그 `[outbox] 발행 실패`와 아웃박스 행의 `attempts`·`lastError`로 본다(`flowticket.outbox.publish.ack`는 성공·실패를 모두 기록해 이것만으로는 실패를 가를 수 없다 — `OutboxRelay.java:174·180·186`).
- **재시도 경로**: 실패 → PENDING 유지 → 다음 틱 재발행은 **CI에서 검증**한다(§2.3 — bean 릴레이 + 테스트용 `LockProvider` + 실패 목, 또는 `TransactionTemplate`으로 감싼 프록시 없는 릴레이. 대기 상한은 전용 팩토리를 별도 Redis 컨테이너에 연결). 클러스터에서 Redis 장애를 강제로 넣는 시험은 하지 않는다 — ShedLock 락 획득이 같은 Redis(기본 커넥션 팩토리, `SchedulerLockConfig`의 `lockProvider` 빈)에 의존해 대부분 발행 전에 틱이 건너뛰어진다(추론. 락을 잡은 뒤 끊기면 발행 실패 경로를 탈 수는 있다). readiness(`readinessState, db, redis`)는 트래픽을 끊을 뿐 릴레이를 멈추지 않는다. 그래서 클러스터에서는 **재시도 경로가 미검증**으로 남고, 관찰된 `attempts > 1` 행이 있으면 결국 `PUBLISHED`가 되었는지만 기록한다.
- 롤백 조건(N → 이전, **P2b PR revert**):
  - 아웃박스 PENDING이 줄지 않는다(`FlowticketOutboxBacklog`).
  - 릴레이 발행 실패 로그가 계속된다.
  - 결제 E2E에서 `order.paid`를 받지 못한다.
  - Redis 지연으로 틱 예산 경고가 반복된다.
  - (나)의 설정 전환 단계에서는 그 PR을 revert한다(configmap 값과 Pod 템플릿 annotation이 함께 돌아가 롤아웃이 일어난다 — 코드 롤백 불필요).

### 6.2 운영 게이트 ② — Kafka 운영 삭제(세션, 별도 게이트)
삭제 뒤에는 기존 코드로 **즉시 롤백할 수 없다**(N-1은 Kafka가 있어야 동작). 그래서 N 게이트와 따로 판정한다.
- **삭제 직전 확인**(모두 충족해야 진행):
  1. §6.1 N 게이트 통과(Redis 발행·아웃박스 상태·결제 E2E). 재시도는 CI 검증 결과를 인용한다.
  2. 구버전 Pod 0개(§6.1 첫 항목 재확인).
  3. Kafka 잔여 없음 — 통과 기준은 "토픽·소비자 그룹이 **없음**, 또는 있으면 lag 0·오프셋 증가 0"이다.
     - (가)에서는 토픽 `order-events`·`.DLT`(앱의 `NewTopic` bean — `KafkaConfig.java:55-61`)와 소비자 그룹 `flowticket`(`OrderEventConsumer.java:35`)을 만드는 코드가 N에 없다. 그래서 지표 시계열이 "0"이 아니라 **없을** 것으로 본다(추론). 빈 패널·빈 쿼리를 통과로 읽지 않도록 브로커에서 직접 확인한다:
       `kubectl exec -n kafka flowticket-dual-role-0 -- bin/kafka-topics.sh --bootstrap-server localhost:9092 --list`, `... bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group flowticket`  (Pod 이름은 Strimzi의 `<클러스터>-<노드 풀>-<ID>` 규칙에서 끌어낸 추론, `bin/` 상대 경로와 `localhost:9092`는 Strimzi 이미지 작업 디렉터리·리스너 전제 — 세션에서 `kubectl get pod -n kafka`로 이름을 확인하고 실행한다)
     - 토픽·그룹이 있으면: 소비자 lag 0(`kafka_consumergroup_lag` — 라벨 `consumergroup`, `dashboard-kafka.yaml:133`), 최신 오프셋 증가 0(Exporter의 오프셋 지표 — 이름 `kafka_topic_partition_current_offset`은 **미확인**, Strimzi Kafka Exporter 지표명 추론), `order-events.DLT` 메시지 0(Exporter `topicRegex: "order-events.*"`가 DLT도 포함 — `kafka.yaml:111`. 또는 위 CLI로 오프셋 확인).
     - `dlq_messages` PENDING 0(DB).
  4. 롤백 경로 확인: 저장소에 Kafka 매니페스트(`k8s/kafka`)와 Strimzi 차트 버전(`bring-up.sh`의 `STRIMZI_CHART_VERSION`)이 아직 있다(P3 미머지). 재설치는 아래 "삭제 뒤 롤백"의 명령 순서로 한다.
- **삭제 전 기록**(§7.1·§8의 미확인 항목을 여기서 해소):
  - kafka 네임스페이스 Pod별 실제 `resources`(`kubectl get pod -n kafka -o jsonpath='{..resources}'`)와 노드 배치(`-o wide`)
  - 노드별 requests 합(`kubectl describe node`의 Allocated resources)
  - PVC·PV·EBS 볼륨 ID — kafka 네임스페이스 것만(PV의 `spec.claimRef.namespace=kafka`로 걸러 `spec.csi.volumeHandle` 채집). `tear-down.sh:118-119`는 모든 PV를 채집해 Prometheus 볼륨도 들어가므로 그대로 쓰지 않는다
- **삭제 절차**(순서대로, 명령과 출력은 `session.log`):
  1. `kubectl delete kafka --all -n kafka`, `kubectl delete kafkanodepool --all -n kafka`(`tear-down.sh:110-111`과 같은 명령). `KafkaNodePool`이 `deleteClaim: true`(`kafka.yaml:29`)라 PVC도 함께 정리될 것으로 본다 — 아래 3에서 확인한다.
  2. `helm uninstall strimzi -n kafka`
  3. 남은 PVC가 있으면 `kubectl delete pvc --all -n kafka`(**`-A`를 쓰지 않는다** — `tear-down.sh:125`의 `-A`는 철거용이라 Prometheus PVC까지 지운다). EBS는 삭제 전 기록한 볼륨 ID로 상태를 확인하고 `available`로 남은 것만 지운다(`tear-down.sh:118-119` 볼륨 ID 채집, `:128-152` 소유 볼륨 확인·삭제와 같은 방식).
  4. Kafka PodMonitor·대시보드(`podmonitor-kafka.yaml`·`dashboard-kafka.yaml`)만 `kubectl delete -f`로 지운다. 나머지 모니터링은 유지한다.
  5. `kafka` 네임스페이스 삭제. Strimzi CRD를 남겼는지 지웠는지 기록한다. 마지막으로 P3a의 표식(ConfigMap `kafka-removed`)을 만든다.
- **삭제 후 기록:** 노드 수·노드별 requests 합(CA가 노드를 줄이는지 포함 — B₁은 CA가 안정된 뒤 시작), 결제 E2E 재확인, 아웃박스 상태.
- **삭제 뒤 롤백**(필요할 때 — bring-up에는 Kafka 단계만 따로 돌리는 기능이 없으므로 명령을 직접 실행): 표식 ConfigMap 삭제 → `kubectl create namespace kafka` → `helm upgrade --install strimzi strimzi/strimzi-kafka-operator --version 1.2.0 -n kafka --wait` → `kubectl apply -k k8s/kafka` → `kubectl wait kafka/flowticket -n kafka --for=condition=Ready --timeout=10m` → Kafka 모니터링 두 리소스 적용 → P2b revert. 토픽은 빈 상태로 시작한다. 소요 시간은 측정한 적이 없다.

### 6.3 운영 삭제와 Git이 다른 기간 — 관찰 기간
- 운영 삭제 뒤부터 저장소 P3 머지 전까지는 **클러스터에는 Kafka가 없고 Git(bring-up)에는 있다**.
  - Kafka는 ArgoCD 추적 밖이라 자동 동기화로 되살아나지는 않는다(사실 — §5.6 주의).
  - 그러나 이 기간에 bring-up을 다시 돌리면 Kafka가 다시 생긴다. 그래서 이 기간의 bring-up 재실행은 **반드시 `SKIP_KAFKA=1`**(P3a)로 하고, 실행 명령을 `session.log`에 남긴다. 표식 가드(P3a)는 같은 클러스터 재실행만 막고 **새 클러스터는 막지 못한다**.
- 관찰 기간에 확인할 것: B₁·B′ 측정·가용성 G1 시험 중 아웃박스 PENDING·DEAD·발행 지연, 결제 E2E의 `order.paid` 수신.
- 관찰 기간이 끝나는 조건: 측정 세션 동안 위 항목에 이상이 없고, 이후 bring-up을 한 번 `SKIP_KAFKA=1`로 새로 올려도 전 단계가 통과한다. 그다음 저장소 P3를 머지한다.
- P4(DB `DROP`)는 P3 머지 뒤, N-1로 돌아갈 일이 없다고 판단될 때 한다(파괴적 DDL — N-1 롤백이 영구히 불가능해진다).

## 7. 자원 — 줄어드는 것과 줄지 않는 것 (계산·사실 구분)
이 절은 **하한 계산**이다. 실제로 모든 Pod가 배치되는지를 보인 것이 아니고, 필요한 노드 수를 확정하지 않는다. 실제 필요 노드 수는 자원 산정에서 전체 자원과 배치 제약을 반영해 다시 낸다.

### 7.1 Kafka가 차지하던 몫
| 구성 요소 | requests(매니페스트·차트 기준) | 출처 |
|---|---|---|
| 브로커 3개(controller 겸용) | 매니페스트에 `resources` 없음 | `k8s/kafka/kafka.yaml` — `KafkaNodePool`·`spec.kafka`. **Strimzi가 실제로 만든 Pod의 requests는 확인하지 않았다** — 운영자가 넣는 기본값이나 LimitRange가 있으면 0이 아니다 |
| Kafka Exporter | 20m / 64Mi | `kafka.yaml:113-115` |
| Entity Operator(topic·user) | 매니페스트에 없음 → Strimzi 기본값(**미확인**) | `kafka.yaml:101-103` |
| Strimzi 운영자 | 200m / 384Mi | 차트 1.2.0 기본값(`helm show values strimzi/strimzi-kafka-operator --version 1.2.0`, 2026-10-14 확인), `bring-up.sh`가 덮어쓰지 않음 |
| PVC | 10Gi × 3, gp2 | `kafka.yaml` storage |
- 다음 세션에서 확인할 것: `kubectl get pod -n kafka -o jsonpath='{..resources}'`로 실제 Pod의 requests를 확인한다. 이 표의 "없음"은 매니페스트 기준이다.
- 브로커의 **실제** CPU·메모리 사용량은 측정한 적이 없다. 제거로 노드 안 경합이 줄어들 수는 있으나 **수치로 말할 근거가 없다**.

### 7.2 노드 수 하한 — CPU requests만 본 계산 (m6i.large allocatable 1,930m — [[ADR-025]])
전제(사실):
- api·대기열 Pod requests는 1,100m라 노드당 하나만 들어간다(2 × 1,100 > 1,930).
- web은 300m다.
- 노드 그룹은 AZ 3곳 × `node_min_size` 1이다.

| 배치 | 1,100m Pod 수 | CPU requests 하한 | Kafka 제거로 하한이 바뀌나 |
|---|---|---|---|
| 기존 A'(api HPA min 3, web 2) | 3 | 3대 | 아니오 |
| 현재 저장소의 B(`queue-split` — api min 3·max 3 + 대기열 min 2) | 5 | 5대 | 아니오 |
| B 2+2 후보(api 2 + 대기열 2 — [operating-requirements.md](operating-requirements.md) 출발 후보, api HPA 하한·PDB 변경이 필요) | 4 | 4대 | 아니오 |

- **결론(계산):** 현재 CPU requests만을 기준으로 하면 A'는 최소 3대, 현재 B는 최소 5대, B 2+2는 최소 4대가 필요하다. Kafka 제거만으로 이 하한은 낮아지지 않는다.
- **하한이 실제 대수는 아니다.** IMP-033 v2에서 A'는 시작 노드 4대, B는 5·6대였다(실측 — IMP-033 표 "시작 노드"). [[ADR-025]]의 노드별 기록도 A'가 4대가 될 수 있음을 보인다.
  - 기록: A' 평시에 노드별 flowticket 밖 CPU 요청이 410·600·750m(세션 20261007-e2e, 저장소 밖 실측)였다.
  - 그러면 api를 놓고 남는 자리는 420·230·80m이고, web 300m 두 개 중 하나가 4번째 노드로 가서 **4대가 될 수 있다**(계산).
  - 이 값에는 kafka 네임스페이스 Pod(운영자·exporter 등)가 들어 있었을 것으로 본다(추론 — 네임스페이스 기준상 flowticket 밖). 어느 노드에 있었는지는 기록에 없다.
  - Kafka가 빠지면 그 몫이 놓였던 노드에 따라 web이 들어갈 자리가 생겨 **4 → 3대가 될 수도, 그대로일 수도 있다**(0~1대, 미확정).
- **이 계산에 빠진 조건**(자원 산정에서 반영):
  - 노드별 메모리(api·대기열 1,536Mi, 브로커 실제 메모리)
  - DaemonSet 예약량
  - web·모니터링(Prometheus 등) 배치
  - AZ별 노드 그룹 상한
  - 롤링 배포 시 surge Pod(+1,100m — [[ADR-026]])
  - Strimzi가 만든 Pod의 실제 requests(§7.1)
- 하한이 내려갈 조건(계산, 조건부): api·대기열 requests를 낮춰 한 노드에 둘 이상 들어가게 되면, Kafka의 3 AZ 분산 강제(`DoNotSchedule`)가 없어진 것이 의미를 갖는다. 그때도 `node_min_size`(AZ당 1)가 남아 있으므로 함께 판단해야 한다.

### 7.3 확실히 줄어드는 것
- EBS gp2 30GiB(10Gi × 3): ap-northeast-2 gp2 단가 $0.114/GB-월(AWS Pricing API, 2026-10-14 조회)로 **월 $3.42 — 한 달 내내 띄웠을 때의 계산값**이다. 실제로는 측정 세션 동안만 떠 있어서(철거 때 PVC 삭제) 실제 절감은 이보다 훨씬 작다.
- 매니페스트·차트 기준 requests 약 220m·448Mi(운영자 + exporter. Entity Operator·브로커 실제 Pod 값은 미확인이라 뺐다).
- 운영 절차:
  - bring-up의 kafka 네임스페이스·Strimzi 설치(운영자 `helm --wait`)·Kafka CR 적용(브로커 대기는 없음)
  - tear-down의 CR·PVC 정리
  - 장애 시험 시나리오 1개
  - CI 백엔드 job의 Testcontainers Kafka 기동(시간 변화는 측정하지 않았다)

### 7.4 이번에 바꾸지 않지만 다시 판단할 것 — 3 AZ·NAT·RDS Multi-AZ·EBS CSI
- 이 작업에서는 **변경 범위를 줄이려고 유지한다**. Kafka 제거를 이유로 함께 지우지 않는다.
- 다만 Kafka가 빠지면 [[ADR-012]]가 3 AZ를 둔 주된 근거(브로커 3개를 서로 다른 장애 도메인에 두기)가 사라진다.
  - 결정된 가용성 목표 G1은 **노드 1대 장애**다. 3 AZ가 필수 조건은 아니다(2 AZ나 1 AZ의 여러 노드로도 G1은 충족할 수 있다 — 추론).
  - 3 AZ가 의미를 갖는 것은 후속 목표 G2(AZ 장애)다.
  - AZ별 NAT, RDS Multi-AZ, AZ별 노드 그룹(EBS가 AZ에 묶이는 문제 — 지금은 Prometheus PVC가 이유)도 같은 질문에 묶여 있다.
- 최종 운영 정책에서 **비용과 가용성(G1·G2)으로 다시 평가한다** — 자원 산정 문서의 과제로 넘긴다.

### 7.5 다음 성능 비교 — 기준선이 바뀐다
- IMP-033의 B 예매 p95는 **Kafka가 떠 있던 환경**의 실측이다. B는 v2에서 두 번 쟀다(① 폭증 전 → ② 폭증 중): v2-b1은 hold 212 → 256ms·주문 180 → 260ms·결제 195 → 224ms, v2-b2는 hold 194 → 272ms·주문 188 → 211ms·결제 178 → 184ms. 한 회 값이 B의 대표값이 아니다(두 회 관측, 통계적 반복 아님 — IMP-033).
- Kafka를 빼면 Pod 구성과 노드 안 CPU·메모리 경합이 달라질 수 있다. 이어서 requests·HPA·DB 풀까지 바꾼 뒤 예매가 빨라지면, 어느 변경 때문인지 가를 수 없다.
- 그래서 다음 측정은 단계를 나눈다.

| 단계 | 구성 | 목적 |
|---|---|---|
| B₀(기록) | IMP-033 B, Kafka 있음 | 역사적 기준선(다시 재지 않음) |
| B₁ | **Kafka 인프라를 운영 삭제한 뒤**(§6.2) 기존 B 설정 그대로(requests 1,100m·api 3·대기열 2~6·노드 프로파일 m6i.large·AZ당 상한 3·v2 워밍업 절차·같은 부하 시나리오·같은 사전 등록 판정식). 적용 방법(queue-split component를 켜는 Git 변경 또는 저장소 밖 전환 스크립트 — 무엇을 썼는지 기록)도 조건 차이에 넣는다. CA가 운영 삭제 뒤 노드를 줄이는 중이면 안정된 뒤 시작한다. 시작 노드 수는 기록한다(IMP-033에서도 B는 배치에 따라 5·6대로 통제되지 않았다 — 다르면 결과에 표기) | Kafka 없는 새 기준선 |
| B′ | B₁에서 requests·HPA·DB 풀만 변경 | 자원 최적화의 효과(B₁ 대비 — 원칙적으로 같은 세션. 세션이 나뉘면 아래 규칙) |

- **기준선 (a) — 세션 차이를 받아들인다**(사용자 결정 2026-10-14). 목적은 Kafka 제거의 성능 효과를 입증하는 것이 아니라, Kafka 없이 정합성·알림 전달이 유지되는지 확인하고 그 위에서 자원을 최적화하는 것이다.
  - B₀과 B₁의 차이를 **Kafka 제거만의 효과로 쓰지 않는다**. 세션이 달라 Kafka 외 조건도 달라진다.
  - B₁ 결과에는 B₀ 대비 달라진 조건을 함께 적는다: 세션 날짜·시간대, 노드 AMI와 노드별 Pod 배치, 시작 노드 수, 이미지 태그(앱 코드 변경 포함 — N), Kafka 유무, 부하 발생기 위치.
- **B′는 반복 조정을 허용한다.**
  - 목표(예매 p95·정합성 위반 0·노드 수)와 판정식은 B′ 전에 사전 등록한다.
  - 못 미치면 requests·HPA·DB 풀을 다시 조정하고 재측정한다. 매 조정은 PR → CI·하네스 → G1 리뷰 → 머지 → ArgoCD 동기화·이미지/설정 확인을 거친다. k8s만 바뀌는 조정은 이미지를 다시 빌드하지 않는다.
  - **시도한 B′ 후보를 모두 기록한다**(가장 잘 나온 회차만 고르지 않는다).
- **가용성 G1(노드 1대 비자발적 장애)은 최종 B′ 후보에서 검증한다.** 시험 전에 그 후보의 실제 Pod 배치(`-o wide`)와 노드별 남은 용량을 확인하고, 노드 하나를 잃었을 때 남은 노드에 api·대기열·web이 다시 들어갈 자리가 있는지 계산으로 먼저 본다. 자리가 없으면 시험 전에 후보를 고친다.
- **한 세션 안에 모두 끝난다고 전제하지 않는다.** 사전 준비(P3a, B′ 매니페스트·하네스 수정안, 사전 등록 초안)를 끝내고 들어가되, 결과에 따라 세션을 나눈다. 세션이 바뀌면 B′와 같은 세션 비교를 위해 B₁을 다시 잴지 사전 등록 때 정한다.
- 판정식과 반복 횟수는 측정 전에 사전 등록한다(IMP-033 방식).

## 8. 확인하지 못한 것 — 어디서 확인하나
클러스터를 띄워야만 풀 수 있는 항목을 줄이기 위해 확인 장소를 나눈다.

| 항목 | 확인 장소 | 시점 |
|---|---|---|
| `StringRedisTemplate.convertAndSend` 반환 타입 | **확인됨**: Spring Data Redis 3.3.5 API 문서 — `@Nullable Long convertAndSend(String destination, Object message)`, 받은 구독자 수(파이프라인·트랜잭션에서는 null) | P2b-1 |
| Lettuce 명령 타임아웃 기본값, 전용 팩토리 `commandTimeout` 시간 초과 뒤 연결 상태 | **기본값 확인됨**: Spring Boot 3.3.5 `LettuceConnectionConfiguration`은 `spring.data.redis.timeout`이 null이면 `commandTimeout`을 설정하지 않고, `LettuceClientConfiguration` 기본값은 `RedisURI.DEFAULT_TIMEOUT` = 60초(Lettuce 6.3 소스). 시간 초과 뒤 연결 상태는 CI 테스트(`RedisOutboxPublisherTimeoutTest` — 별도 컨테이너 `CLIENT PAUSE`, 풀린 뒤 같은 발행기로 재발행)로 확인 | P2b-1 |
| `@SchedulerLock`과 `@Transactional` 프록시 순서(락 해제와 커밋 중 무엇이 먼저인지) | **확인됨**: 락은 트랜잭션 바깥에서 잡고 푼다 — `OutboxRelayLockIntegrationTest`(락 획득·해제 시점의 트랜잭션 활성 여부 기록, 테스트용 `@Primary` LockProvider) CI 통과(PR #411, run 38583390823). `SchedulerLockConfig` Javadoc의 "락은 트랜잭션 바깥에서 획득된다"와 일치 | P2b-1 |
| Strimzi가 만든 브로커·Entity Operator Pod의 실제 requests | 클러스터(기존 기록 없음) | 세션 §6.2 삭제 전 기록 |
| t3.large(기본 프로파일) allocatable | 클러스터 또는 EKS 예약량 계산식 | 자원 산정 |
| Hikari active·idle·pending 실측 | 부하를 건 클러스터 | 세션 B₁·B′ |
| G1 노드 강제 장애 | 클러스터 | 세션, 최종 B′ 후보 |
| 구독 연결 순간 단절로 SSE가 연결된 채 이벤트를 놓치는 빈도 | 측정 계획 없음 — §4 권장의 근거는 "가능하다"는 구조이지 빈도가 아니다 | — |
