# Table · alert_settings

- 슬라이스: `S07`
- 마이그레이션(단일 진실원): `V13__alert_settings.sql`
- 도메인 규칙: 운영 알림 임계치

## 목적
운영 알림 임계치를 담는 **단일 행(id=1)** 설정 테이블. 현재는 DLQ 적체(PENDING) 임계치 하나.
현재 적체가 임계치 이상이면 대시보드/응답에서 `breached=true`로 경고를 띄운다.

## 컬럼
| 컬럼 | 타입 | NULL | 기본값 | 제약 | 설명 |
|------|------|------|--------|------|------|
| id | BIGINT | N | | PK | 단일 행 식별자(항상 1) |
| dlq_pending_threshold | INTEGER | N | 1 | | DLQ 적체 경고 임계치 |
| updated_at | TIMESTAMP | N | now() | | 마지막 수정 시각 |

## 인덱스 / 제약
| 이름 | 종류 | 컬럼 | 이유 |
|------|------|------|------|
| (pk) | PK | id | 단일 행 |

## 도메인 규칙 연결
- 마이그레이션이 id=1 행을 시드(멱등). 서비스는 항상 id=1을 읽고 수정한다.
- `GET /admin/alerts`: 임계치 + 현재 DLQ 적체 + breached. `PUT /admin/alerts`: 임계치 수정(0 이상).
- Consumer Lag 등 추가 지표는 범위 밖(후속) — 현재는 DLQ 적체 임계치만.
