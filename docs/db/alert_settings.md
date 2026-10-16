# Table · alert_settings

- 슬라이스: `S07`
- 마이그레이션(단일 진실원): `V13__alert_settings.sql`, `V20__alert_outbox_threshold.sql`(아웃박스 격리 임계치 추가), `V21__drop_dlq.sql`(`dlq_pending_threshold` 삭제)
- 도메인 규칙: 운영 알림 임계치

## 목적
운영 알림 임계치를 담는 **단일 행(id=1)** 설정 테이블. 앱이 쓰는 임계치는 아웃박스 격리(DEAD) 하나(`outbox_dead_threshold`)다.
현재 격리 수가 임계치 이상이면 응답의 `outboxBreached`가 true가 된다.
예전 `dlq_pending_threshold`는 Kafka 제거([[ADR-028]]) 릴리스 N(#412)부터 앱이 매핑하지 않았고, N+1(P4)의 V21에서 지웠다(Expand-Contract — `docs/deployment/kafka-removal-design.md` §5.3).

## 컬럼
| 컬럼 | 타입 | NULL | 기본값 | 제약 | 설명 |
|------|------|------|--------|------|------|
| id | BIGINT | N | | PK | 단일 행 식별자(항상 1) |
| outbox_dead_threshold | INTEGER | N | 1 | | 아웃박스 격리(DEAD) 경고 임계치(V20) |
| updated_at | TIMESTAMP | N | now() | | 마지막 수정 시각 |

## 인덱스 / 제약
| 이름 | 종류 | 컬럼 | 이유 |
|------|------|------|------|
| (pk) | PK | id | 단일 행 |

## 도메인 규칙 연결
- 마이그레이션이 id=1 행을 시드(멱등). 서비스는 항상 id=1을 읽고 수정한다.
- `GET /admin/alerts`: 아웃박스 격리(DEAD) 임계치 + 현재 격리 수 + `outboxBreached`. DLQ 필드는 Kafka 제거(P2b-2)로 없앴다.
- `PUT /admin/alerts`: `outboxDeadThreshold`(필수, 0 이상). 이전 화면이 함께 보내던 `dlqPendingThreshold`는 알 수 없는 필드로 무시된다.
- 테스트 초기화(TRUNCATE)에서 이 테이블은 빠지므로, 임계치를 바꾸는 통합 테스트는 직접 기본값으로 되돌린다(`AdminAlertIntegrationTest`).
- 다른 지표의 임계치는 범위 밖이다 — 앱이 쓰는 임계치는 아웃박스 격리 하나다.
