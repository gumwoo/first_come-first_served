# Table · alert_settings

- 슬라이스: `S07`
- 마이그레이션(단일 진실원): `V13__alert_settings.sql`, `V20__alert_outbox_threshold.sql`(아웃박스 격리 임계치 추가)
- 도메인 규칙: 운영 알림 임계치

## 목적
운영 알림 임계치를 담는 **단일 행(id=1)** 설정 테이블. DLQ 적체(PENDING) 임계치와 아웃박스 격리(DEAD) 임계치를 둔다.
현재 값이 임계치 이상이면 응답의 `breached`(DLQ)·`outboxBreached`(아웃박스)가 true가 된다. 화면은 아웃박스 쪽을 쓴다.
Kafka 제거([[ADR-028]]) 뒤 DLQ 컬럼은 N+1 릴리스에서 지운다(Expand-Contract — `docs/deployment/kafka-removal-design.md` §5.3).

## 컬럼
| 컬럼 | 타입 | NULL | 기본값 | 제약 | 설명 |
|------|------|------|--------|------|------|
| id | BIGINT | N | | PK | 단일 행 식별자(항상 1) |
| dlq_pending_threshold | INTEGER | N | 1 | | DLQ 적체 경고 임계치(Kafka 제거 뒤 N+1에서 삭제 예정) |
| outbox_dead_threshold | INTEGER | N | 1 | | 아웃박스 격리(DEAD) 경고 임계치(V20) |
| updated_at | TIMESTAMP | N | now() | | 마지막 수정 시각 |

## 인덱스 / 제약
| 이름 | 종류 | 컬럼 | 이유 |
|------|------|------|------|
| (pk) | PK | id | 단일 행 |

## 도메인 규칙 연결
- 마이그레이션이 id=1 행을 시드(멱등). 서비스는 항상 id=1을 읽고 수정한다.
- `GET /admin/alerts`: 두 임계치 + 현재 DLQ 적체·아웃박스 격리 수 + 각각의 초과 여부.
- `PUT /admin/alerts`: 임계치 수정(0 이상). 두 값 모두 선택이고 적어도 하나는 있어야 한다. 보내지 않은 값은 바꾸지 않는다 — 롤링 배포 중 구·신 화면이 한쪽만 보내도 다른 쪽이 0으로 덮이지 않게 하기 위해서다.
- 테스트 초기화(TRUNCATE)에서 이 테이블은 빠지므로, 임계치를 바꾸는 통합 테스트는 직접 기본값으로 되돌린다(`AdminAlertIntegrationTest`).
- Consumer Lag 등 추가 지표는 범위 밖 — 임계치는 DLQ 적체·아웃박스 격리 두 개다.
