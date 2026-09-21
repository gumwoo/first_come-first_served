# Table · orders

- 슬라이스: `S05`
- 마이그레이션(단일 진실원): `V8__orders.sql` (예정)
- 도메인 규칙: [[order-payment]], 상태전이 [[ADR-006]]

## 목적
주문 헤더. 좌석 선점(hold)을 결제 대상으로 승격한 단위. 상태기계(PENDING…PAID/EXPIRED)의 주체.

## 컬럼
| 컬럼 | 타입 | NULL | 기본값 | 제약 | 설명 |
|------|------|------|--------|------|------|
| id | BIGINT | N | identity | PK | orderId |
| event_id | BIGINT | N | | FK→events | 공연 |
| user_id | BIGINT | N | | (FK 미설정) | 주문자 — 인증 JWT userId 신뢰 |
| hold_id | BIGINT | N | | (FK 미설정) | 근거 좌석 선점(S04) |
| status | VARCHAR(20) | N | 'PENDING' | | OrderStatus(PENDING/VBANK_WAITING/PAID/FAILED/EXPIRED/CANCELLED/REFUNDED) |
| amount | INTEGER | N | | | 총 결제 금액(스냅샷) |
| payment_method | VARCHAR(10) | Y | | | PaymentMethod(card/easy/vbank) |
| expires_at | TIMESTAMP | N | | | 결제 제한시각(= hold 잔여 TTL) |
| paid_at | TIMESTAMP | Y | | | 결제 확정 시각 |
| created_at | TIMESTAMP | N | now() | | |

## 인덱스 / 제약
| 이름 | 종류 | 컬럼 | 이유 |
|------|------|------|------|
| ix_orders_status_exp | INDEX | status, expires_at | 만료 sweep 가속 |
| ix_orders_user | INDEX | user_id | 마이/중복예매 검사 |

## 도메인 규칙 연결
- 상태 전이는 **조건부 UPDATE**(`WHERE status='PENDING'`)로 원자화([[ADR-006]]).
- PAID 시 seats HELD→SOLD, seat_holds HELD→CONVERTED, QR 발급, `order.paid`.
- 만료 sweep: PENDING/VBANK_WAITING + expires_at<now → EXPIRED + 좌석/hold 회수.
- 승인 거절은 order를 FAILED로 두지 않음(재시도 위해 PENDING 유지) — `payments`에만 FAILED 기록.
