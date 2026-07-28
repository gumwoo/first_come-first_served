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

## D. 정직한 경계

- **컨슈머 작업이 가벼움**(SSE 브로드캐스트) → **컨슈머-랙 오토스케일은 억지로 만들지 않는다.** 스케일 데모는 **API 티어 HPA(HTTP 부하)**, Kafka는 **HA(페일오버)·파티션 병렬·랙 관측**으로 나눠 실증.
- 위 변경은 **동시성/정합성 원칙(ADR-002/003/006, 분산락 금지)과 무관** — 파티션·RF는 인프라 축, 상태 전이 원자화는 그대로.
- 파티션 증가 시 **재시도 재발행·리밸런스로 인한 중복 소비** 가능 → 브로드캐스트는 멱등(재알림)이라 안전, DLQ 적재는 payload로 식별(문서화).

## E. 반영 순서(요약)

1. A-1·A-2(파티션·RF) — Strimzi 붙이기 전 소스/CR 값 합의
2. C-1·C-2·C-3(probe·graceful·env) — Helm 배포 전
3. B-1(메트릭) — 관측 스택과 함께
4. B-2(부하·페일오버) — 마지막 실증(S08)

> 모든 변경은 CI(Testcontainers)로 회귀 검증. 로컬 gradle 없음 → 컴파일/통합은 CI에서.
