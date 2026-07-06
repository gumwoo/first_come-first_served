# Table · seat_holds

- 슬라이스: `S04`
- 마이그레이션(단일 진실원): `V7__seat_holds.sql` (예정)
- 도메인 규칙: [[seat]] 선점/상태전이

## 목적
좌석 선점(HOLD) 기록. TTL 만료·해제·결제확정으로 상태 전이. 좌석 재고 복구의 근거.

## 컬럼
| 컬럼 | 타입 | NULL | 기본값 | 제약 | 설명 |
|------|------|------|--------|------|------|
| id | BIGINT | N | identity | PK | holdId |
| event_id | BIGINT | N | | FK→events | 공연 |
| user_id | BIGINT | N | | FK→users | 선점자 |
| status | VARCHAR(20) | N | 'HELD' | | SeatHoldStatus(HELD/RELEASED/EXPIRED/CONVERTED) |
| expires_at | TIMESTAMP | N | | | 선점 만료 시각(~5분) |
| created_at | TIMESTAMP | N | now() | | |

## seat_hold_items (홀드↔좌석 N:M)
| 컬럼 | 타입 | 제약 | 설명 |
|------|------|------|------|
| hold_id | BIGINT | FK→seat_holds | |
| seat_id | BIGINT | FK→seats, UNIQUE(활성) | 한 좌석은 활성 홀드 1개 |

## 인덱스 / 제약
| 이름 | 종류 | 컬럼 | 이유 |
|------|------|------|------|
| ix_holds_status_exp | INDEX | status, expires_at | 만료 sweep 가속 |
| ix_holds_user_event | INDEX | user_id, event_id | 1인 구매 한도 검사 |

## 도메인 규칙 연결
- 만료 sweep: `status=HELD AND expires_at<now` → EXPIRED + 좌석 AVAILABLE 복구 + `seat.hold.expired`.
- 상태전이: HELD→RELEASED/EXPIRED/CONVERTED. 위반 시 `INVALID_STATE_TRANSITION`.
- 선점은 큐 토큰 ADMITTED 검증(`QUEUE_NOT_ADMITTED`) + 1인 한도(`MAX_PER_USER_EXCEEDED`).
