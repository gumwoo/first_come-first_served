# Table · dlq_messages

- 슬라이스: `S07` (Phase 4c)
- 마이그레이션(단일 진실원): `V12__dlq_messages.sql`
- 도메인 규칙: Kafka DLQ, [[ADR-006]] 계열(비동기 이벤트 백본)

## 목적
컨슈머 재시도가 소진되어 Dead Letter Topic(`order-events.DLT`)으로 넘어온 실패 메시지를
운영자가 조회·재시도·폐기할 수 있도록 DB에 적재/보존한다.

## 컬럼
| 컬럼 | 타입 | NULL | 기본값 | 제약 | 설명 |
|------|------|------|--------|------|------|
| id | BIGINT | N | identity | PK | dlqMessageId |
| topic | VARCHAR(200) | N | | | 원본 토픽(재발행 대상) |
| payload | TEXT | N | | | 실패 메시지 페이로드(JSON) |
| error_message | TEXT | Y | | | 소비 실패 원인(DLT 예외 헤더) |
| status | VARCHAR(20) | N | 'PENDING' | | DlqStatus(PENDING/RETRYING/RETRIED/DISCARDED) |
| created_at | TIMESTAMP | N | now() | | 적재 시각 |
| retried_at | TIMESTAMP | Y | | | 재시도/폐기 처리 시각 |

## 인덱스 / 제약
| 이름 | 종류 | 컬럼 | 이유 |
|------|------|------|------|
| ix_dlq_status_id | INDEX | status, id DESC | 상태 필터 + 최신순 목록 가속 |

## 도메인 규칙 연결
- 컨슈머 예외 → `DefaultErrorHandler`(2회 재시도) 소진 → `DeadLetterPublishingRecoverer`가 `.DLT` 발행.
- `DlqConsumer`가 `.DLT`를 소비해 이 테이블에 PENDING으로 적재(원본 토픽·예외 메시지 헤더 기록).
- 운영: `GET /admin/dlq`(조회), `POST /admin/dlq/{id}/retry`(원본 토픽 재발행 → RETRIED),
  `POST /admin/dlq/{id}/discard`(→ DISCARDED). 대시보드는 PENDING 적체 수를 노출.
