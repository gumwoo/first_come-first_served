# Table · refunds

- 슬라이스: `S06`
- 마이그레이션(단일 진실원): `V11__refunds.sql`
- 도메인 규칙: [[refund]], 멱등/원자화 [[ADR-006]]

## 목적
예매 **취소·환불 기록**. 취소 1건당 1행(감사추적). 환불액·수수료·사유·PG 환불 거래ID를 남긴다.
멱등키로 이중 환불(더블클릭/재시도)을 DB가 원천 차단한다.

## 컬럼
| 컬럼 | 타입 | NULL | 기본값 | 제약 | 설명 |
|------|------|------|--------|------|------|
| id | BIGINT | N | identity | PK | |
| order_id | BIGINT | N | | FK→orders | 취소 대상 주문 |
| payment_id | BIGINT | Y | | FK→payments | 원 결제(환불 대상) |
| amount | INTEGER | N | | | 환불 예정액(결제액 − 수수료) |
| fee | INTEGER | N | | | 취소 수수료 |
| reason | VARCHAR(100) | Y | | | 취소 사유 |
| pg_refund_tid | VARCHAR(100) | Y | | | PG 환불 거래 ID |
| idempotency_key | VARCHAR(80) | N | | UNIQUE | 이중 환불 차단 |
| created_at | TIMESTAMP | N | now() | | |

## 인덱스 / 제약
| 이름 | 종류 | 컬럼 | 이유 |
|------|------|------|------|
| uq_refunds_idem | UNIQUE | idempotency_key | 더블클릭/재시도 이중 환불 차단 |
| ix_refunds_order | INDEX | order_id | 주문별 환불 조회 |

## 도메인 규칙 연결
- 취소는 `PAID`에서만 + 시점 게이트(당일·이후 불가) → 그 외 `REFUND_NOT_ALLOWED`([[refund]]).
- 환불 확정: 주문 조건부 전이 `PAID→CANCELLED→REFUNDED`(ADR-006), 좌석 `SOLD→AVAILABLE` 복구.
- 좌석 복구(벌크 UPDATE) 전에 refund를 `saveAndFlush`로 확정 — 컨텍스트 클리어 유실 방지([[TS-007]]·[[TS-010]]).
- 환불액 = 결제액 − 기간별 수수료(`RefundPolicy`, 설정 외부화).
