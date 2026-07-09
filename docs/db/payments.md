# Table · payments

- 슬라이스: `S05`
- 마이그레이션(단일 진실원): `V9__payments.sql` (예정)
- 도메인 규칙: [[order-payment]], 멱등/원자화 [[ADR-006]], 게이트웨이 [[ADR-005]]

## 목적
개별 **결제 시도** 기록. 한 주문(order)에 여러 시도가 있을 수 있다(FAILED 여러 개 + 최종 APPROVED 1개).
멱등키로 "같은 시도"의 중복 생성을 차단한다.

## 컬럼
| 컬럼 | 타입 | NULL | 기본값 | 제약 | 설명 |
|------|------|------|--------|------|------|
| id | BIGINT | N | identity | PK | |
| order_id | BIGINT | N | | FK→orders | 소속 주문 |
| method | VARCHAR(10) | N | | | PaymentMethod(card/easy/vbank) |
| provider | VARCHAR(20) | Y | | | PaymentProvider(easy 결제 시) |
| status | VARCHAR(20) | N | 'READY' | | PaymentStatus(READY/APPROVED/FAILED/CANCELLED) |
| amount | INTEGER | N | | | 시도 금액 |
| idempotency_key | VARCHAR(80) | N | | UNIQUE | 같은 결제 시도 중복 차단 |
| pg_tid | VARCHAR(100) | Y | | | PG 거래 ID(웹훅 멱등 대조) |
| vbank_account | VARCHAR(50) | Y | | | 가상계좌(무통장) |
| deposit_deadline | TIMESTAMP | Y | | | 입금 기한(무통장) |
| approved_at | TIMESTAMP | Y | | | 승인 시각 |
| created_at | TIMESTAMP | N | now() | | |

## 인덱스 / 제약
| 이름 | 종류 | 컬럼 | 이유 |
|------|------|------|------|
| uq_payments_idem | UNIQUE | idempotency_key | 더블클릭/웹훅 재전송 중복 승인 차단 |
| ix_payments_order | INDEX | order_id | 주문별 시도 조회 |
| ix_payments_pg_tid | INDEX | pg_tid | 웹훅 재전송 멱등 대조 |

## 도메인 규칙 연결
- 멱등: 같은 `idempotency_key`/`pg_tid` 재요청은 새로 처리하지 않고 기존 결과 반환([[ADR-006]]).
- 승인 성공 → order 상태 전이는 조건부 UPDATE(`WHERE status='PENDING'`)로 원자화.
- 승인 거절 → 이 레코드만 FAILED, order는 PENDING 유지(재시도 허용).
- vbank는 발급 시 READY(+가상계좌·기한), 입금 웹훅 시 APPROVED → order PAID.
