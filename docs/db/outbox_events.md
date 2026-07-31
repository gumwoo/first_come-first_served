# Table · outbox_events

- 슬라이스: `S08` (1단계 아웃박스)
- 마이그레이션(단일 진실원): `V14__outbox_events.sql`
- 도메인 규칙: [[ADR-010]](트랜잭셔널 아웃박스), [[ADR-008]](Kafka 이벤트 백본·DLQ)

## 목적
비즈니스 트랜잭션(결제 확정)과 **같은 트랜잭션**에서 도메인 이벤트를 적재한다. 폴링 릴레이가 이 행을
Kafka로 발행하고 성공 후 `PUBLISHED`로 마킹한다(publish-then-mark). `AFTER_COMMIT` 직접 발행이 갖던
**"커밋 직후·발행 전 크래시 → 이벤트 영구 유실"** 구멍을 닫는 것이 목적.

## 컬럼
| 컬럼 | 타입 | NULL | 기본값 | 제약 | 설명 |
|------|------|------|--------|------|------|
| id | UUID | N | | PK | 이벤트 식별자 = 소비자 멱등 키(`dedup:order-event:{id}`) |
| aggregate_type | VARCHAR(40) | N | | | 애그리거트 종류(현재 `order`) |
| aggregate_id | BIGINT | N | | | orderId. Kafka 파티션 키 → 주문별 순서 보장 |
| type | VARCHAR(60) | N | | | 이벤트 타입(현재 `order.paid`) |
| payload | TEXT | N | | | 직렬화된 이벤트(JSON) |
| status | VARCHAR(20) | N | 'PENDING' | | OutboxStatus(PENDING/PUBLISHED) |
| attempts | INT | N | 0 | | 발행 시도 횟수(운영 가시성·장애 진단) |
| created_at | TIMESTAMP | N | now() | | 적재 시각(= 비즈니스 커밋 시각) |
| published_at | TIMESTAMP | Y | | | 발행 성공 시각. purge 기준 |

## 인덱스 / 제약
| 이름 | 종류 | 컬럼 | 이유 |
|------|------|------|------|
| ix_outbox_pending | 부분 INDEX (WHERE status='PENDING') | created_at | 릴레이의 "PENDING 오래된 순" 조회. PUBLISHED가 쌓여도 스캔 저렴 |
| ix_outbox_published_at | 부분 INDEX (WHERE status='PUBLISHED') | published_at | purge 스윕(보존 7일 경과분 삭제) |

## 도메인 규칙 연결
- **적재**: `PaymentService.finalizePaid`가 같은 tx에서 INSERT(PENDING). 결제가 롤백되면 이 행도 사라짐 → 유령 이벤트 0.
- **발행**: `OutboxRelay`(`@Scheduled` + `@SchedulerLock("outbox-relay")`)가 PENDING을 발행 → 성공 시 `PUBLISHED`+`published_at`, 실패 시 `attempts++` 후 다음 틱 재시도(**at-least-once**).
- **소비 멱등**: 메시지의 `id`로 소비자가 `SETNX dedup:order-event:{id}` TTL 24h — 중복 흡수. Redis 장애 시 fail-open(경고 로그 후 전달 진행, SSE는 중복 무해).
- **보존/청소**: `PUBLISHED`는 운영 가시성을 위해 7일 보존 후 `@SchedulerLock("outbox-purge")` 스윕이 삭제.
  **`PENDING`·미발행 행은 절대 삭제하지 않는다.**
- 관련 실증: IMP-011(브로커 다운 중 결제 → 복구 후 유실 0).
