# Table · seat_hold_items

- 슬라이스: `S04`
- 마이그레이션(단일 진실원): `V7__seat_holds.sql`
- 도메인 규칙: [[seat]] 선점

## 목적
선점(seat_holds)과 좌석(seats)의 N:M 연결. 한 홀드가 여러 좌석을 가질 수 있다.

## 컬럼
| 컬럼 | 타입 | NULL | 제약 | 설명 |
|------|------|------|------|------|
| id | BIGINT | N | PK | |
| hold_id | BIGINT | N | FK→seat_holds | 소속 홀드 |
| seat_id | BIGINT | N | FK→seats | 선점 좌석 |

## 인덱스
| 이름 | 컬럼 | 이유 |
|------|------|------|
| ix_hold_items_hold | hold_id | 홀드별 좌석 조회 / 1인 한도 카운트 |
