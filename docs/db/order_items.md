# Table · order_items

- 슬라이스: `S05`
- 마이그레이션(단일 진실원): `V8__orders.sql` (예정, orders와 동일 마이그레이션)
- 도메인 규칙: [[order-payment]], 가격 스냅샷 [[ADR-004]]

## 목적
주문에 포함된 좌석 라인. **주문 시점의 등급·가격 스냅샷**을 보관해, 이후 가격표가 바뀌어도
주문 금액이 불변이도록 한다.

## 컬럼
| 컬럼 | 타입 | NULL | 기본값 | 제약 | 설명 |
|------|------|------|--------|------|------|
| id | BIGINT | N | identity | PK | |
| order_id | BIGINT | N | | FK→orders | 소속 주문 |
| seat_id | BIGINT | N | | FK→seats | 좌석 |
| grade | VARCHAR(10) | N | | | SeatGrade 스냅샷 |
| price | INTEGER | N | | | 주문 시점 가격 스냅샷(원) |

## 인덱스 / 제약
| 이름 | 종류 | 컬럼 | 이유 |
|------|------|------|------|
| ix_order_items_order | INDEX | order_id | 주문 상세 조회 |
| uq_order_items_seat | UNIQUE | order_id, seat_id | 한 주문 내 좌석 중복 방지 |

## 도메인 규칙 연결
- `price`는 `event_seat_prices` 현재값을 **복사(스냅샷)** — 진실원은 주문 시점 값([[ADR-004]] 연장).
- `orders.amount = SUM(order_items.price)`.
