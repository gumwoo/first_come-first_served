# 워크로드 역할과 장애 책임 — api·대기열·백그라운드 작업은 어디서 돌고, 사라지면 무엇이 멈추나 (분석, 결정 전)

- 상태: **분석 — 역할 분리 결정 전**. 이 문서는 지금 코드의 사실과, 역할을 나눌 때 정해야 할 소유권·인계를 정리한다.
- 날짜: 2026-10-14
- 관련: [operating-requirements.md](operating-requirements.md)(운영 요구사항 후보), [[ADR-027]](대기열 전용 Deployment — 같은 이미지), [[TS-023]](outbox-relay 틱당 1회 실증·SSE 교차 파드 전달),
  [app-changes-for-k8s-kafka.md](app-changes-for-k8s-kafka.md)(멀티팟 D 섹션), `global/config/SchedulingConfig.java`·`SchedulerLockConfig.java`

## 1. 실행 모델 (코드 사실)
- api와 대기열 Deployment는 **같은 이미지**다. 스케줄러는 `flowticket.scheduling.enabled`(기본 true) **하나로 전부 켜고 끈다** — 작업 단위로 끄는 설정은 없다.
- ShedLock 저장소는 **Redis**(`RedisLockProvider(…, "flowticket")`). 락을 쓰는 작업은 모든 파드가 자기 틱마다 Redis에 락을 시도하고, 락을 잡은 파드가 본문을 실행한다. **모든 락이 `lockAtLeastFor = PT0S`**라 본문이 끝나면 락이 바로 풀린다 — 파드마다 기동 시각(틱 위상)이 다르면 **한 주기 안에 여러 파드가 차례로 본문을 실행할 수 있다**(ShedLock 동작에서 나온 추론, 측정하지 않음). "틱당 1회"는 `outbox-relay`(1초 주기, 본문 약 1초)에서만 실증됐다([[TS-023]] A4).
- ShedLock은 "정확히 한 번"을 보장하지 않는다(위의 반복 실행, 임차 `lockAtMostFor` 초과 시 동시 실행). 그래서 겹쳐도 되는지는 **작업마다 다르다** — 아래 표의 "겹쳐도 안전한가" 열. 아웃박스는 "틱이 임차보다 길 수 없다"를 하네스 규칙으로 검사해 겹침 자체를 막는다(`be-outbox-lease` fixture).
- 대기열 승격만 **락이 없다** — Redis Lua로 원자적이라 여러 파드가 동시에 돌아도 안전하고, 단일 리더로 묶으면 처리량이 줄어서다(코드 주석·배포 문서 D-3).
- DB 커넥션: 파드마다 Hikari 풀(최대 5, `minimum-idle` 미지정 → HikariCP 기본값은 `minimumIdle = maximumPoolSize`, 즉 고정 크기 풀로 채워서 쥔다 — 라이브러리 기본값, 측정 아님). 파드가 늘면 **쥐는 커넥션**이 는다. active 실측은 초기 구성에만 있다(50 VU 파드당 0~1 — [[TS-021]], 1,800 rps 파드당 5(상한)·pending 116 — [[TS-034]]); 현 구성(1100m·B)과 대기열 파드에서는 재지 않았다.

## 2. 작업 목록 (지금은 두 Deployment의 모든 파드에서 시도)
| 작업 | 주기 | 락(임차) | 하는 일 · 쓰는 것 | 겹쳐도 안전한가 | 아무 파드도 안 돌리면 |
|---|---|---|---|---|---|
| 대기열 승격·슬롯 회수 `QueueAdmissionService` | 1.5초 | **없음**(Lua 원자) | 틱마다 활성 공연 목록 조회(`SMEMBERS`) → 공연마다 만료 회수·정원만큼 승격·지표 스냅숏. Redis만 | 예(원자) | 대기자가 입장하지 못함, 만료 슬롯이 회수되지 않음 |
| 아웃박스 릴레이 `OutboxRelay` | 1초 | outbox-relay(1분) | PENDING 이벤트를 Kafka로 발행(배치 100, 행 잠금 없이 읽음). DB·Kafka | **겹치지 않게 막는다**(틱 예산 < 임차, 하네스 `be-outbox-lease`). 겹치면 같은 PENDING을 다른 파드가 집어 **중복 발행** — 소비자 eventId 멱등(SETNX)이 흡수, 같은 주문 안 순서는 보장 안 됨(ADR-022) | 주문·결제 이벤트 발행 지연(실시간 알림 지연) — 데이터는 DB에 남음 |
| 아웃박스 정리 | 매일 03:00 | outbox-purge(10분) | 오래된 발행 완료 행 삭제. DB | 예 | 테이블 증가(급하지 않음) |
| 좌석 선점 만료 `SeatHoldExpiryService` | 60초 | seat-hold-sweep(50초) | 만료 hold 회수(hold별 조건부 UPDATE, `status = HELD`) + 풀린 좌석을 `seat.hold.expired`로 SSE 브로드캐스트. DB·Redis | 예(조건부 UPDATE — 실제로 만료된 hold만) | 만료 좌석이 다시 팔리지 않음(재고 묶임) |
| 주문 만료 `OrderExpiryService` | 60초 | order-expiry-sweep(50초) | 결제 제한시각이 지난 미완료 주문을 EXPIRED로(상태 조건 bulk UPDATE). 좌석·hold 복구는 좌석 만료 sweep 몫. DB | 예 | 미결제 주문 만료 지연 |
| 결제 대사 `PaymentReconciliationService` | 10분 | payment-reconcile(5분) | 미아 승인 정산(승인됐는데 주문에 반영 안 된 결제를 외부 PG로 조회·취소). DB·외부 PG | 예(멱등) | 결제 불일치 복구 지연 |
| 환불 대사 `RefundReconciliationService` | 10분 | refund-reconcile(5분) | 환불 불일치 복구. DB·외부 | 예(멱등) | 환불 불일치 복구 지연 |
| 랭킹 감쇠 `RankingService` | 5분 | ranking-decay(4분) | 실시간 랭킹 ZSET 점수에 감쇠율을 곱함(`ZUNIONSTORE` weight). Redis | **아니오** — 곱셈이라 한 주기에 두 번 돌면 두 번 감쇠(과감쇠). 위 반복 실행이 실제로 일어나는지는 미측정 — 별도 확인 대상 | 순위 갱신 지연 |
| KOPIS 동기화 `KopisSyncScheduler` | 매일 04:00 | `kopis-sync`(10분) — `KopisSyncService.sync()`에 걸림(수동 admin API와 같은 문) | 외부 공연 데이터 동기화. DB·외부 API | 예(락을 못 잡으면 건너뜀) | 공연 데이터 갱신 지연 |

Kafka 리스너(같은 이미지라 모든 파드에서 시작):
| 리스너 | consumer group | 하는 일 | 메모 |
|---|---|---|---|
| `OrderEventConsumer`(order-events) | `flowticket` | 주문·결제 이벤트 → 실시간 알림(SSE) 전달, eventId Redis SETNX 멱등 | 파티션 6개를 그룹 안 파드가 나눠 받는다 — 파드가 6개를 넘으면 일부는 놀고 있다. SSE 연결 파드와 소비 파드가 달라도 전달됨([[TS-023]] A3, 4회 — Redis 경유는 TS가 "강한 추론"으로 표기) |
| `DlqConsumer`(order-events.DLT) | `flowticket-dlq` | 실패 메시지를 `dlq_messages`에 기록(해석 안 함) | 판단은 사람이 admin API로 |

파드 수에 비례해 느는 것: 승격 틱의 Redis 호출(틱마다 `SMEMBERS` 1회 + 활성 공연이 있으면 공연마다 Lua), ShedLock 락 시도(Redis), 쥐는 DB 커넥션, Kafka 그룹 구성원(6개 초과분은 유휴). 락 작업의 본문 실행 횟수는 위 추론대로 주기당 1회보다 많을 수 있다 — 미측정.

## 3. 역할이 통째로 사라지면 (B 구조, 지금 코드 그대로)
요청 경로는 Ingress가 나눈다 — 대기열 두 경로(`/api/queue/status`, `/api/events/*/queue/token`)만 대기열 Service, 나머지는 web → api.
| 사라진 것 | 멈추는 것 | 계속 되는 것 |
|---|---|---|
| 대기열 Deployment 전체 | **새 진입·상태 조회**(대기자는 입장 사실을 알 수 없다) | 이미 입장한 사용자의 선점·주문·결제(입장 확인은 api가 Redis로 한다), **승격**(api 파드에서도 돈다), 대기열 이탈(`DELETE` — web → api 경로), 백그라운드 작업 전부 |
| api Deployment 전체 | 좌석·주문·결제·조회 전부(web이 api를 부른다) | 대기열 진입·상태 조회·승격·백그라운드 작업(대기열 파드에서도 돈다) |
→ 지금은 같은 이미지가 모든 작업을 양쪽에서 돌리는 것이 **서로의 안전망** 노릇을 한다. 역할을 나누면 이 안전망이 사라지므로, 나눌 때는 인계를 같이 정해야 한다.

## 4. 역할 분리 후보와 정해야 할 것
| 작업 | 후보 담당 | 그 역할이 전부 사라질 때 | 인계 방안 후보 |
|---|---|---|---|
| 대기열 승격·회수 | 대기열 | 승격 중단 | 대기열 최소 복제본 2 이상(G1), 또는 api에도 남겨 안전망 유지(지금처럼) |
| 좌석·주문 만료 | api(또는 별도 worker) | 재고 회수 지연 | api 최소 2 이상 |
| 결제·환불 대사 | api(또는 별도 worker) | 불일치 복구 지연 | 주기가 길어(10분) 짧은 중단은 영향 작음 |
| 아웃박스 릴레이 | api(또는 별도 worker) | 이벤트 발행 지연 | 데이터는 DB에 남아 복구 뒤 발행 |
| Kafka 주문 이벤트·DLQ | api | 실시간 알림 지연 | 그룹 재조정으로 남은 소비자가 받음 |
| KOPIS·랭킹 | 별도 결정 | 데이터 갱신 지연 | 급하지 않음 |
- 별도 worker Deployment는 **검토 후보**일 뿐 지금 만들자는 것이 아니다.
- 필요한 코드 변경: 작업 단위 활성화 조건(예: 작업별 `@ConditionalOnProperty` 또는 역할 프로필) — 지금은 없다. 스케줄을 꺼도 HTTP 엔드포인트는 그대로 있다(요청 경로 분리는 Ingress·Service 몫).
- 기존 A'(기본값)와 기존 B 컴포넌트(실험 기준선)는 바꾸지 않고, 새 운영 후보는 별도 구성으로 둔다.

## 5. 아직 모르는 것 (다음 측정)
- 파드별 Hikari active·idle·total — 대기열 파드가 실제로 몇 개를 쓰는가(풀 차등화 근거).
- 승격 틱이 파드 수에 따라 Redis에 주는 부하(활성 공연 수 × 파드 수).
- Kafka 그룹 재조정 시간(파드가 늘고 줄 때 이벤트 지연).
