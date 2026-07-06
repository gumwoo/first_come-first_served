# Table · event_seat_prices

- 슬라이스: `S04`
- 마이그레이션(단일 진실원): `V6__seats.sql` (예정, seats와 함께)
- 도메인 규칙: [[seat]] 가격 정책, [ADR-004]

## 목적
이벤트×등급 **절대 가격**. 결제 가격의 **유일 진실원**. KOPIS priceText 미사용(자유텍스트).

## 컬럼
| 컬럼 | 타입 | NULL | 기본값 | 제약 | 설명 |
|------|------|------|--------|------|------|
| id | BIGINT | N | identity | PK | |
| event_id | BIGINT | N | | FK→events | 공연 |
| grade | VARCHAR(10) | N | | | SeatGrade(VIP/R/S/A) |
| price | INTEGER | N | | | 등급 절대 가격(원) |
| created_at | TIMESTAMP | N | now() | | |

## 인덱스 / 제약
| 이름 | 종류 | 컬럼 | 이유 |
|------|------|------|------|
| (pk) | PRIMARY | id | |
| uq_event_grade_price | UNIQUE | event_id, grade | 가격 시딩 멱등(등급당 1행) |

## 도메인 규칙 연결
- 시딩 시 **장르 티어 + eventId fallback**으로 절대값 계산·저장(멱등). [ADR-004]
- `events.base_price`는 이 표의 등급 최저가(A)로 시딩 단계에서 write.
- 목록/상세/좌석선택/결제 가격 모두 이 표 기준.
