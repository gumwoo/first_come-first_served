# 앱 변경사항 — K8s(EKS) + 멀티브로커 Kafka 대응

- 상태: 계획(미착수). [EKS 배포 계획](aws-eks-deploy-plan.md)의 Phase 5에서 반영.
- 목적: 인프라 yaml만이 아니라 **앱 코드/설정도 바뀌어야 멀티브로커·오토스케일이 "의미"를 가진다**. 안 하면 "구성만 하고 실증 못 함" = 오버엔지니어링.

## 왜 코드가 바뀌어야 하나 (한 줄)

지금 코드는 **단일 브로커·단일 파티션 전제**라, EKS·Strimzi를 붙여도 병렬성·HA가 **작동하지 않는다.** 아래를 바꿔야 멀티브로커가 실제로 산다.

## A. 필수 — 안 하면 멀티브로커·HPA 무의미

### A-1. 토픽 파티션 1 → N
- 현재: `KafkaConfig`의 `order-events`가 `partitions(1)`.
- 문제: **파티션 1이면 컨슈머 그룹에 컨슈머가 여러 개여도 1개만 소비** → api 파드를 늘려도 나머지는 유휴. 병렬/스케일 서사가 성립 안 함.
- 변경: `order-events` 파티션 N(예: 6), `order-events.DLT`도 동일 고려. (운영은 Strimzi `KafkaTopic` CR로도 관리 가능 — 소스 `NewTopic`과 값 일치.)
- 파일: `apps/api/.../global/config/KafkaConfig.java`

### A-2. 복제 팩터(RF) 1 → 3
- 현재: `NewTopic ... replicas(1)`.
- 문제: **복제 없음 → 브로커 죽으면 유실/중단 = Kafka HA 스토리 없음.**
- 변경: `order-events`·`.DLT` RF 3, 그리고 브로커 설정의 `__consumer_offsets`/`transaction-state` RF도 3(Strimzi Kafka CR에서). `min.insync.replicas`도 함께 고려(예: 2).
- 파일: `KafkaConfig.java`(RF) + Strimzi `Kafka` CR

### A-3. 파티션 키 확인(이미 OK)
- 현재: 발행 시 key=`orderId` → 같은 주문 이벤트가 같은 파티션(주문별 순서 보장). **변경 불필요**, 문서로만 근거 남김.
- 파일: `OrderEventKafkaBridge.java`(현행 유지)

## B. 실증/관측 — 효과를 "보여주기" 위해

### B-1. Kafka·Consumer Lag 메트릭 → Prometheus
- 목적: HPA·파티션 분산·랙을 Grafana로 **증명**(구성만 아님).
- 변경: `micrometer-registry-prometheus` + spring-kafka 메트릭 노출, `management.endpoints...prometheus` 이미 노출 확인. Consumer Lag은 Kafka client 메트릭 또는 Strimzi/kafka-exporter로.
- 파일: `build.gradle.kts`, `application.yml`(management), (관측 스택은 Helm)

### B-2. 부하/페일오버 시나리오(문서·스크립트)
- k6 read/write 시나리오로 **api HPA 확장** 유도, 브로커 1대 종료로 **RF 3 페일오버** 확인.
- 파일: `infra/k6/*`, 데모 절차 문서

## C. K8s 운영 준비(12-factor)

### C-1. liveness/readiness probe 정합
- 변경: `/actuator/health`가 DB/Redis 준비를 반영(readiness). 롤링 배포 중 트래픽을 준비 안 된 파드로 안 보냄.
- 파일: `application.yml`(health group/probes), Helm(probe 경로)

### C-2. graceful shutdown
- 목적: 롤링/스케일인 시 컨슈머가 in-flight 처리·오프셋 커밋 후 종료(이벤트 유실·중복 최소화).
- 변경: `server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase`, 컨테이너 `terminationGracePeriodSeconds`.
- 파일: `application.yml`, Helm

### C-3. 설정 전량 env 외부화(대부분 완료)
- 확인: datasource·redis·kafka·jwt·admin·toss 전부 env/Secret. 하드코딩 0(하네스가 가드). 부족분 정리.
- 파일: `application.yml`(이미 `${...}` 형태)

## D. 다중 Pod에서 드러나는 문제 & 대응 (전부 예정 — 배포 후 구현·검증)

> 동시성 제어는 **이미 다중 인스턴스 안전**(Redis Lua·DB 조건부 UPDATE). 아래는 다중 Pod로 확장할 때 **단일 인스턴스 전제가 깨지는 두 종류**의 문제다 — 종류가 다르다: **SSE=인스턴스-로컬 "상태" / 스케줄러=인스턴스별 "실행".**

### D-1. SSE 실시간 알림 — 인스턴스-로컬 상태 → Redis Pub/Sub 팬아웃
- **문제**: `OrderSseRegistry`·`QueueSseRegistry`·`SeatSseRegistry`가 인스턴스-로컬 `ConcurrentHashMap<SseEmitter>`. 다중 Pod면 Kafka 이벤트를 **소비한 Pod**와 사용자 **SSE 연결 Pod**가 달라 알림이 누락된다.
- **대응**: Kafka 소비 Pod가 **Redis Pub/Sub으로 publish → 전 Pod가 subscribe → 각 Pod가 자기 Registry 확인 → 연결을 가진 Pod만 전송.**
- **Sticky Session**: 팬아웃이 있으면 사용자가 **어느 Pod에 연결됐는지 알 필요가 없어** SSE 전달을 위해 sticky에 의존할 필요가 없다. (단 "모든 의미에서 완전 불필요"는 과일반화 — 전달 관점에서 불필요라는 뜻.)
- **best-effort 성격**: Redis Pub/Sub은 메시지를 저장하지 않아 순간(구독 끊김·Pod 다운) 유실 가능. **우리 정책(SSE 보조 알림·DB 진실원, ADR-008)과 일치** — 놓친 사용자는 마이페이지/상태 조회로 최종 상태 확인. 만약 "반드시 한 번 이상 전달" 요구가 생기면 pub/sub만으론 부족 → Kafka 알림 전용/Redis Streams/Outbox 고려(현재는 불필요).
- 파일(예정): `order/sse/*`(발행/구독), `application.yml`(Redis pub/sub 채널)

### D-2. 스케줄러 — 인스턴스별 중복 실행 → ShedLock (정합성은 조건부 연산이 계속 보장)
- **문제**: `@Scheduled` 만료 스윕(주문/좌석)이 **모든 Pod에서 중복 실행**. 정합성은 조건부 UPDATE로 안전(첫 Pod만 영향행 1, 나머지 0)하나 **중복 조회·실행 비용**.
- **대응**: **ShedLock**으로 한 Pod만 실행(중복 억제).
- **중요 — ShedLock은 정합성 보장 수단이 아니다**: 락 만료·작업 중 Pod 장애·네트워크 지연·만료 후 타 Pod 재진입 가능. 따라서 **기존 조건부 UPDATE/Lua 멱등을 그대로 유지**해 실제 정합성을 보장한다. → 원칙: **락=효율(중복 억제), 원자 조건부 연산=정합성**(재고 경합과 동일, ADR-002/006).
- 파일(예정): 스윕 서비스에 ShedLock 애노테이션, 락 저장소(DB/Redis)

### D-3. 큐 승격 워커 — 병렬 안전 → ShedLock 강제하지 않음
- 대기열 승격은 **Redis Lua 원자**라 다중 Pod 병렬 호출도 정합성 안전(정원 초과 없음). 만료 스윕과 달리 **무조건 단일 실행으로 제한할 필요 없음.**
- ShedLock 강제 시 오히려 **처리량 손해** 가능 → **단일 리더 vs 다중 워커 처리량을 비교해 선택.**
- 파일(예정): 결정 후 반영. 기본은 현행(다중 워커, Lua 원자) 유지.

### D-4. 상태(정직)
- 위 D-1~D-3은 **전부 "예정"**(다중 Pod 배포 전이라 아직 미구현). "했다"가 아니라 "이렇게 갈 것". 배포 후 **다중 Pod 부하 + 결제 이벤트 반복 발생 + 브로커 페일오버**로 검증해야 포트폴리오 성과로 사용 가능.

## E. 정직한 경계

- **컨슈머 작업이 가벼움**(SSE 브로드캐스트) → **컨슈머-랙 오토스케일은 억지로 만들지 않는다.** 스케일 데모는 **API 티어 HPA(HTTP 부하)**, Kafka는 **HA(페일오버)·파티션 병렬·랙 관측**으로 나눠 실증.
- 위 변경은 **동시성/정합성 원칙(ADR-002/003/006, 분산락 금지)과 무관** — 파티션·RF는 인프라 축, 상태 전이 원자화는 그대로.
- 파티션 증가 시 **재시도 재발행·리밸런스로 인한 중복 소비** 가능 → 브로드캐스트는 멱등(재알림)이라 안전, DLQ 적재는 payload로 식별(문서화).

## F. 반영 순서(요약)

1. A-1·A-2(파티션·RF) — Strimzi 붙이기 전 소스/CR 값 합의
2. C-1·C-2·C-3(probe·graceful·env) — Helm 배포 전
3. **D-1·D-2(SSE 팬아웃·ShedLock)** — 다중 Pod 배포와 함께(단일→다중 전환의 핵심), D-3은 비교 후 결정
4. B-1(메트릭) — 관측 스택과 함께
5. B-2(부하·페일오버) + **다중 Pod 알림/중복실행 재검증** — 마지막 실증(S08)

> 모든 변경은 CI(Testcontainers)로 회귀 검증. 로컬 gradle 없음 → 컴파일/통합은 CI에서.
