# Table · seats

- 슬라이스: `S04`
- 마이그레이션(단일 진실원): `V6__seats.sql` (예정)
- 도메인 규칙: [[seat]] (재고 원자성, [ADR-003])

## 목적
공연별 개별 좌석. 재고·상태의 진실원. KOPIS엔 없어 시딩으로 생성(기본 템플릿).

## 컬럼
| 컬럼 | 타입 | NULL | 기본값 | 제약 | 설명 |
|------|------|------|--------|------|------|
| id | BIGINT | N | identity | PK | 좌석 식별자 |
| event_id | BIGINT | N | | FK→events | 공연 |
| grade | VARCHAR(10) | N | | | SeatGrade(VIP/R/S/A) |
| zone | VARCHAR(20) | Y | | | 구역(좌석맵) |
| seat_row | VARCHAR(10) | Y | | | 열 |
| seat_col | INTEGER | Y | | | 번호 |
| status | VARCHAR(20) | N | 'AVAILABLE' | | AVAILABLE/HELD/SOLD |
| version | BIGINT | N | 0 | | 낙관락(@Version) |
| created_at | TIMESTAMP | N | now() | | |
| updated_at | TIMESTAMP | N | now() | | |

## 인덱스 / 제약
| 이름 | 종류 | 컬럼 | 이유 |
|------|------|------|------|
| (pk) | PRIMARY | id | |
| ix_seats_event_status | INDEX | event_id, status | 잔여/조건부 UPDATE 가속 |
| uq_seats_pos | UNIQUE | event_id, zone, seat_row, seat_col | 시딩 멱등(중복 좌석 차단) |

## 도메인 규칙 연결
- 선점은 `WHERE status='AVAILABLE'` 조건부 UPDATE로 원자화(초과판매 0). [ADR-003]
- status는 `contracts/enums.yaml`엔 없음(좌석 status는 내부값). grade는 SeatGrade와 일치.
