# 앱 변경사항 — K8s(EKS) + 멀티브로커 Kafka 대응

- 상태: **C(운영 준비)·D(다중 Pod)는 코드 반영 완료 / A·B는 배포 시점 설정·실증 대기.**
  [EKS 배포 계획](aws-eks-deploy-plan.md) 참조.
- 목적: 인프라 yaml만이 아니라 **앱 코드/설정도 바뀌어야 멀티브로커·오토스케일이 "의미"를 가진다**. 안 하면 "구성만 하고 실증 못 함" = 오버엔지니어링.

## 왜 코드가 바뀌어야 하나 (한 줄)

지금 코드는 **단일 브로커·단일 파티션 전제**라, EKS·Strimzi를 붙여도 병렬성·HA가 **작동하지 않는다.** 아래를 바꿔야 멀티브로커가 실제로 산다.

## A. 필수 — 안 하면 멀티브로커·HPA 무의미

### A-1. 토픽 파티션 1 → N
- 현재: `KafkaConfig`의 `order-events`가 `partitions(1)`.
- 문제: **파티션 1이면 컨슈머 그룹에 컨슈머가 여러 개여도 1개만 소비** → api 파드를 늘려도 나머지는 유휴. 병렬/스케일 서사가 성립 안 함.
- **상태: 코드 준비 완료(S09-0)** — 값을 코드에서 빼 `KAFKA_TOPIC_PARTITIONS`로 주입한다(C-4). 배포 시
  운영만 6으로 올리면 되고 로컬·CI는 1 유지. 운영은 Strimzi `KafkaTopic` CR이 권위 — 값 일치 필요.
- 파일: `KafkaConfig.java`(설정 주입) + `application.yml` + Strimzi `KafkaTopic` CR

### A-2. 복제 팩터(RF) 1 → 3
- 현재: `NewTopic ... replicas(1)`.
- 문제: **복제 없음 → 브로커 죽으면 유실/중단 = Kafka HA 스토리 없음.**
- **상태: 코드 준비 완료(S09-0)** — `KAFKA_TOPIC_REPLICAS`로 주입(C-4). 단 **RF는 토픽 생성 후
  `NewTopic`으로 바뀌지 않는다** → 운영에서는 Strimzi `KafkaTopic` CR이 실제 권위.
- 함께: 브로커의 `__consumer_offsets`/`transaction-state` RF도 3, `min.insync.replicas`(예: 2) — Strimzi `Kafka` CR.
- 파일: `KafkaConfig.java` + `application.yml` + Strimzi `Kafka`/`KafkaTopic` CR

### A-3. 파티션 키 확인(이미 OK)
- 현재: 발행 시 key=`orderId` → 같은 주문 이벤트가 같은 파티션(주문별 순서 보장). **변경 불필요**, 문서로만 근거 남김.
- 파일: `OrderEventKafkaBridge.java`(현행 유지)

## B. 실증/관측 — 효과를 "보여주기" 위해

### B-1. Kafka·Consumer Lag 메트릭 → Prometheus
- 목적: HPA·파티션 분산·랙을 Grafana로 **증명**(구성만 아님).
- **레지스트리는 반영 완료(C-5)** — 남은 것은 Consumer Lag 수집(Kafka client 메트릭 또는 Strimzi/kafka-exporter)과 대시보드.
- 파일: `build.gradle.kts`, `application.yml`(management), (관측 스택은 Helm)

### B-2. 부하/페일오버 시나리오(문서·스크립트)
- k6 read/write 시나리오로 **api HPA 확장** 유도, 브로커 1대 종료로 **RF 3 페일오버** 확인.
- 파일: `infra/k6/*`, 데모 절차 문서

## C. K8s 운영 준비(12-factor)

### C-1. liveness/readiness probe 정합 — **완료(S09-0)**
- 반영: `management.endpoint.health.probes.enabled=true`로 `/actuator/health/{liveness,readiness}` 노출.
- **readiness = `readinessState, db, redis`** — 없으면 요청을 처리할 수 없는 의존성만 포함.
  **Kafka는 의도적으로 제외**: 브로커 장애는 알림 지연일 뿐이고 아웃박스가 유실을 막는다(ADR-008/010).
  readiness에 넣으면 브로커 하나 죽었다고 API 전체가 트래픽에서 빠지는 과잉 차단이 된다.
- **liveness = `livenessState`만** — 외부 의존성을 넣으면 DB 장애에 Pod가 무한 재시작한다.
- 남은 것: Helm/매니페스트의 probe 경로 설정.

### C-2. graceful shutdown — **완료(S09-0)**
- 반영: `server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase=${SHUTDOWN_TIMEOUT:30s}`.
- 남은 것: 컨테이너 `terminationGracePeriodSeconds`(매니페스트) — 이 값보다 커야 유예가 실효.

### C-3. 설정 전량 env 외부화 — **완료(S09-0)**
- 정정: 이전 문서는 "대부분 완료"라고 적었지만 실제로는 **DB 호스트·유저와 Redis 호스트/포트가
  하드코딩**(`localhost`)이었다. Pod에서 localhost는 그 Pod 자신이라 그대로 올리면 부팅부터 실패한다.
- 반영: `DB_URL`(통째 대체) 또는 `DB_HOST`/`DB_PORT`/`DB_NAME`, `DB_USERNAME`/`DB_PASSWORD`,
  `REDIS_HOST`/`REDIS_PORT`, `KAFKA_BOOTSTRAP_SERVERS` 전부 env. 기본값은 로컬 주소라 개발 흐름 불변.
- **프로필 전략(결정)**: `application-prod.yml`을 따로 두지 **않는다.** 파일이 둘이면 설정이 갈라져
  드리프트가 생기고, 어느 쪽이 적용됐는지 추적이 어려워진다. **단일 `application.yml` + `${ENV:로컬기본값}`**
  으로 두고 운영은 ConfigMap/Secret이 env를 덮는다.

### C-4. Kafka 토픽 파라미터 설정화 — **완료(S09-0)**
- 반영: `kafka.topic.partitions`/`kafka.topic.replicas`를 env로(`KAFKA_TOPIC_PARTITIONS`/`_REPLICAS`).
  로컬·CI는 단일 브로커라 **1/1이어야 하고**(RF > 브로커 수면 토픽 생성 실패), 운영 Strimzi(브로커 3)에서
  **6/3**으로 올린다. 코드 수정 없이 환경만 바꾸면 되므로 A-1·A-2가 배포 시점의 설정 작업이 된다.
- 주의: **파티션은 늘릴 수 있으나 RF는 `NewTopic`으로 사후 변경되지 않는다** — 운영에서는 Strimzi
  `KafkaTopic` CR이 권위를 갖고 이 값과 일치시킨다.

### C-5. 관측 레지스트리 — **완료(S09-0)**
- 정정: `management.endpoints.web.exposure.include`에 `prometheus`가 있었지만
  **`micrometer-registry-prometheus` 의존성이 없어 실제로는 엔드포인트가 뜨지 않았다.**
- 반영: `runtimeOnly("io.micrometer:micrometer-registry-prometheus")` + `allowed-stack.yaml` 등재.

### C-6. 프론트 API 오리진 — **완료(S09-0)**
- 정정(배포 블로커): `next.config.mjs`의 rewrites 목적지가 `http://localhost:8080` **하드코딩**이었다.
  브라우저는 상대경로(`/api/...`)로 호출하고 Next 서버가 프록시하는 구조라, 컨테이너에서는
  **모든 API 호출이 web Pod 자신을 향해 실패**한다.
- 반영: `API_ORIGIN`으로 주입(미설정 시 로컬 기본값).
- **정정(S09-1 실측)**: 처음엔 "런타임 주입이라 같은 이미지를 재사용할 수 있다"고 적었으나 **틀렸다.**
  `output: "standalone"`에서는 rewrites 목적지가 **번들에 구워져** 런타임 env로 바뀌지 않는다 —
  컨테이너에서 런타임 주입을 시도했더니 빌드 때 값(`localhost:8080`)으로 프록시해 `ECONNREFUSED`가 났다.
  → **build-arg(`API_ORIGIN`)로 전환.** K8s에서는 브라우저의 `/api/*`를 **Ingress가 API Service로 직접
  라우팅**하므로 이 값이 요청 경로에 끼지 않는다(빌드 값 고정이 문제되지 않는 이유).
- `output: "standalone"`은 **이미지 빌드에서만** 켠다(`NEXT_OUTPUT_STANDALONE=true`) — standalone은
  심볼릭 링크를 만들어 **Windows 로컬 빌드가 EPERM으로 실패**하기 때문. 로컬 검증 흐름을 깨지 않는다.

### C-7. Redis TLS — **앱 설정 반영 완료 · 매니페스트 남음** 🟠

`platform` 스택이 ElastiCache를 `transit_encryption_enabled = true`로 만든다. **TLS를 켠 캐시는
클라이언트도 TLS로 붙어야 하고, 아니면 연결이 전부 실패한다.** 앱 설정이 따라가지 않으면
Pod가 뜨자마자 readiness에서 걸려 영원히 Ready가 안 된다(C-1의 readiness에 `redis`가 있다).

**왜 켰나**: 프라이빗 서브넷 + SG는 "네트워크에 못 들어온다"는 방어이고 TLS는 "들어와도 못
읽는다"는 방어다. 계층이 다르므로 하나로 갈음하지 않는다. 특히 **대기열 토큰이 Redis를 지나므로**
평문 구간이 있으면 토큰이 그대로 노출된다. (`auth_token`은 시크릿 관리가 따라와 범위 밖 — 접근
주체 제한은 SG가 담당한다.)

**체크리스트 — 하나라도 빠지면 배포가 안 뜬다**

| # | 대상 | 할 일 | 상태 |
|---|---|---|---|
| 1 | 앱 설정 | `spring.data.redis.ssl.enabled`를 **env로** 노출 | ✅ `REDIS_SSL_ENABLED` |
| 2 | k8s 매니페스트 | `SPRING_DATA_REDIS_SSL_ENABLED=true` 주입 | ⬜ `k8s/` 미작성 |
| 3 | 로컬·CI | **false 유지** — Testcontainers Redis는 평문이다 | ✅ 기본값 + 테스트로 고정 |
| 4 | readiness probe | TLS 불일치 시 Ready가 안 되는 것이 **정상**임을 인지 | ✅ 문서·주석 |
| 5 | 운영 CLI | `redis-cli --tls -h <endpoint>` | ⬜ apply 후 |
| 6 | 부하 테스트(S10) | 도구가 TLS를 말하는지 확인 | ⬜ S10 |

> **2번이 남아 있는 한 apply해도 Pod는 뜨지 않는다.** 앱은 스위치를 갖췄을 뿐,
> 켜는 것은 매니페스트의 몫이다. `infra/docker-compose.prod.yml`은 평문 Redis 컨테이너를
> 쓰므로 기본값(false) 그대로 두면 된다 — **손대지 않았다.**

**1·3이 핵심이다.** 기본값을 `true`로 박으면 로컬 개발과 CI가 전부 깨진다:

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      ssl:
        enabled: ${REDIS_SSL_ENABLED:false}   # 운영(k8s)에서만 true
```

> Spring Boot 3.3.5 + Lettuce 기준. **이 설정 하나로 앱의 모든 Redis 경로가 함께 바뀐다** —
> 대기열 Lua, SSE Pub/Sub(D-1), ShedLock(D-2)이 같은 커넥션 팩토리를 쓰기 때문이다.
> 반대로 말하면 **빠뜨리면 전부 동시에 죽는다.**

**회귀 가드**: `RedisTlsConfigIntegrationTest`가 **두 방향을 모두** 잡는다 —
(1) 기본값이 평문인지(켜지면 로컬·CI가 전부 깨진다), (2) 프로퍼티로 실제로 켜지는지
(고장 나면 운영에서 Pod가 Ready가 안 된다). 하나만 두면 나머지 방향의 회귀를 놓친다.
켜는 쪽 검증은 `ApplicationContextRunner`로 격리했다 — `@SpringBootTest`에 프로퍼티를 덧붙이면
컨텍스트 캐시 키가 갈려 컨텍스트가 하나 더 뜬다(IMP-013 §7-2).


## D. 다중 Pod에서 드러나는 문제 & 대응 (D-1·D-2 코드 선반영 완료, 검증은 다중 Pod 배포 시)

> 동시성 제어는 **이미 다중 인스턴스 안전**(Redis Lua·DB 조건부 UPDATE). 아래는 다중 Pod로 확장할 때 **단일 인스턴스 전제가 깨지는 두 종류**의 문제다 — 종류가 다르다: **SSE=인스턴스-로컬 "상태" / 스케줄러=인스턴스별 "실행".**

### D-1. SSE 실시간 알림 — 인스턴스-로컬 상태 → Redis Pub/Sub 팬아웃
- **문제**: `OrderSseRegistry`·`QueueSseRegistry`·`SeatSseRegistry`가 인스턴스-로컬 `ConcurrentHashMap<SseEmitter>`. 다중 Pod면 Kafka 이벤트를 **소비한 Pod**와 사용자 **SSE 연결 Pod**가 달라 알림이 누락된다.
- **대응**: Kafka 소비 Pod가 **Redis Pub/Sub으로 publish → 전 Pod가 subscribe → 각 Pod가 자기 Registry 확인 → 연결을 가진 Pod만 전송.**
- **Sticky Session**: 팬아웃이 있으면 사용자가 **어느 Pod에 연결됐는지 알 필요가 없어** SSE 전달을 위해 sticky에 의존할 필요가 없다. (단 "모든 의미에서 완전 불필요"는 과일반화 — 전달 관점에서 불필요라는 뜻.)
- **best-effort 성격**: Redis Pub/Sub은 메시지를 저장하지 않아 순간(구독 끊김·Pod 다운) 유실 가능. **우리 정책(SSE 보조 알림·DB 진실원, ADR-008)과 일치** — 놓친 사용자는 마이페이지/상태 조회로 최종 상태 확인. 만약 "반드시 한 번 이상 전달" 요구가 생기면 pub/sub만으론 부족 → Kafka 알림 전용/Redis Streams/Outbox 고려(현재는 불필요).
- **상태: 코드 선반영 완료.** 세 레지스트리의 `broadcast/send`가 이제 `SsePubSub`로 Redis 채널에 발행하고, 각 레지스트리가 `MessageListener`로 자기 채널을 구독해 `deliverLocal`로 전달(단일 전달 경로, 자기 자신 포함). pub/sub 미배선(유닛)이면 로컬 폴백. 파일: `global/sse/SsePubSub`, `global/config/SseRedisConfig`, `{seat,order,queue}/sse/*`. 교차-인스턴스 팬아웃 검증: `SseFanoutIntegrationTest`. **cross-Pod 실측**만 다중 Pod 배포 시.

### D-2. 스케줄러 — 인스턴스별 중복 실행 → ShedLock (정합성은 조건부 연산이 계속 보장)
- **문제**: `@Scheduled` 만료 스윕(주문/좌석)이 **모든 Pod에서 중복 실행**. 정합성은 조건부 UPDATE로 안전(첫 Pod만 영향행 1, 나머지 0)하나 **중복 조회·실행 비용**.
- **대응**: **ShedLock**으로 한 Pod만 실행(중복 억제).
- **중요 — ShedLock은 정합성 보장 수단이 아니다**: 락 만료·작업 중 Pod 장애·네트워크 지연·만료 후 타 Pod 재진입 가능. 따라서 **기존 조건부 UPDATE/Lua 멱등을 그대로 유지**해 실제 정합성을 보장한다. → 원칙: **락=효율(중복 억제), 원자 조건부 연산=정합성**(재고 경합과 동일, ADR-002/006).
- **상태: 코드 선반영 완료.** `SchedulerLockConfig`(@EnableSchedulerLock + Redis `LockProvider`), 정리성 스윕 4개(`seat-hold-sweep`·`order-expiry-sweep`·`ranking-decay`·`kopis-sync`)에 `@SchedulerLock`. `lockAtLeastFor=0`(직접호출 테스트/틱 간 재획득 허용), 락은 트랜잭션 바깥. 상호배제 검증: `SchedulerLockIntegrationTest`. 파일: `global/config/SchedulerLockConfig`, 각 스윕 서비스.

### D-3. 큐 승격 워커 — 병렬 안전 → ShedLock 강제하지 않음
- 대기열 승격은 **Redis Lua 원자**라 다중 Pod 병렬 호출도 정합성 안전(정원 초과 없음). 만료 스윕과 달리 **무조건 단일 실행으로 제한할 필요 없음.**
- ShedLock 강제 시 오히려 **처리량 손해** 가능 → **단일 리더 vs 다중 워커 처리량을 비교해 선택.**
- 파일(예정): 결정 후 반영. 기본은 현행(다중 워커, Lua 원자) 유지.

### D-4. 상태(정직)
- **D-1·D-2는 코드 선반영 완료**(단일 Pod에서도 동작·통합테스트 통과 — pub/sub 자기구독 전달, Redis 락 상호배제). 다만 **"cross-Pod에서 실제로 문제를 해결한다"는 실증**은 다중 Pod 부하 + 결제 이벤트 반복 + 브로커 페일오버로 배포 후 확인해야 포트폴리오 성과로 확정된다("작성·단위검증 완료 / 다중 Pod 실측 예정").
- **D-3(승격 워커)**은 의도적으로 ShedLock 미적용 유지(Lua 원자성으로 다중 워커 안전, 처리량 우선).

## E. 정직한 경계

- **컨슈머 작업이 가벼움**(SSE 브로드캐스트) → **컨슈머-랙 오토스케일은 억지로 만들지 않는다.** 스케일 데모는 **API 티어 HPA(HTTP 부하)**, Kafka는 **HA(페일오버)·파티션 병렬·랙 관측**으로 나눠 실증.
- 위 변경은 **동시성/정합성 원칙(ADR-002/003/006, 분산락 금지)과 무관** — 파티션·RF는 인프라 축, 상태 전이 원자화는 그대로.
- 파티션 증가 시 **재시도 재발행·리밸런스로 인한 중복 소비** 가능 → 브로드캐스트는 멱등(재알림)이라 안전, DLQ 적재는 payload로 식별(문서화).

## F. 반영 순서(요약)

0. **C-1~C-6(S09-0 배포 블로커 제거) — 완료.** probe·graceful shutdown·env 외부화·토픽 파라미터 설정화·
   Prometheus 레지스트리·프론트 API 오리진. AWS 이전에 로컬에서 끝낼 수 있는 것부터 닫았다.
1. **C-7(Redis TLS) — `platform` apply와 `k8s/` 매니페스트 사이의 블로커.** 순서상 가장 먼저다:
   인프라는 TLS로 만들어지는데 앱이 평문이면 Pod가 아예 Ready가 되지 않아 그 뒤 항목을 검증할 수 없다.
2. A-1·A-2(파티션·RF) — 이제 **코드가 아니라 환경/CR 값 합의**(Strimzi 붙일 때)
3. **D-1·D-2(SSE 팬아웃·ShedLock)** — 다중 Pod 배포와 함께(단일→다중 전환의 핵심), D-3은 비교 후 결정
4. B-1(메트릭) — 관측 스택과 함께
5. B-2(부하·페일오버) + **다중 Pod 알림/중복실행 재검증** — 마지막 실증(S10)

> 모든 변경은 CI(Testcontainers)로 회귀 검증. 로컬 gradle 없음 → 컴파일/통합은 CI에서.
