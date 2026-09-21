# Table · refund_attempts

- 슬라이스: `S06`
- 마이그레이션(단일 진실원): `V19__refund_attempts.sql`
- 도메인 규칙: 정산 [[ADR-011]], 동시 환불 멱등 [[TS-030]]

## 목적
**환불 시도 기록.** 환불 정산([[ADR-011]])이 확인해야 할 작업 목록이다.

`refunds`와 역할이 다르다. `refunds`는 성공한 환불의 장부이고, 이 테이블은 실패·롤백된 시도까지
남긴다. 환불은 PG 취소와 DB 쓰기를 한 트랜잭션에 묶으므로, PG 취소가 성공한 뒤 쓰기가 실패하면
전체가 롤백돼 "환불을 시도했다"는 사실 자체가 사라진다. 그 상태(PG는 취소, DB는 PAID)를 찾으려면
시도 기록이 그 트랜잭션 **밖에서** 먼저 커밋돼 있어야 한다.

## 컬럼
| 컬럼 | 타입 | NULL | 기본값 | 제약 | 설명 |
|------|------|------|--------|------|------|
| id | BIGINT | N | identity | PK | |
| order_id | BIGINT | N | | FK→orders | 환불 대상 주문 |
| idempotency_key | VARCHAR(80) | N | | UNIQUE | 환불 요청의 멱등키(`refunds`와 같은 값) |
| resolved | BOOLEAN | N | false | | PG와 DB가 어긋나지 않음이 확인됨 |
| checked_at | TIMESTAMP | Y | | | 정산이 마지막으로 PG에 조회한 시각(후보 순회용) |
| created_at | TIMESTAMP | N | now() | | 시도 시각 — 정산 후보 창의 기준. **앱이 넣는다**(DB 기본값은 폴백) |

## 인덱스 / 제약
| 이름 | 종류 | 컬럼 | 이유 |
|------|------|------|------|
| uq_refund_attempts_idem | UNIQUE | idempotency_key | 같은 요청이 후보로 두 번 올라가지 않게 |
| ix_refund_attempts_pending | INDEX(부분, resolved=false) | checked_at ASC NULLS FIRST | 정산 후보 순회(오래 안 본 순서) |

## 도메인 규칙 연결
- 기록은 `RefundService.refund()`가 **환불 트랜잭션을 열기 전에** 남긴다. 같은 멱등키가 이미 있으면
  그대로 둔다 — 여기서 예외를 올리면 [[TS-030]]이 보장한 동시 환불 멱등이 깨진다.
- 그 "그대로 둔다"는 `on conflict (idempotency_key) do nothing`으로 **DB에 명시**한다. 애플리케이션에서
  `DataIntegrityViolationException`을 잡아 무시하면 길이 초과·FK 위반까지 함께 삼켜, 기록 없이 PG 취소가
  나가고 정산 안전망이 비어 버린다.
- 닫을 때는 **주문까지 지목한다**(`order_id` + `idempotency_key`). 키는 클라이언트가 만들고 UNIQUE는
  전역이라, 키만으로 닫으면 같은 키를 재사용한 다른 주문의 성공이 남의 미해결 시도를 닫는다.
  키가 이미 다른 주문에 묶여 있는 요청은 PG를 부르기 전에 `VALIDATION_ERROR`로 거절한다.
- `resolved = true`가 되는 경우는 셋이다: 환불 정상 완료, 정산이 PG에 물어 취소가 없음을 확인
  (`DONE`/`NOT_FOUND`), 정산이 미아 취소를 수렴 완료.
- 조회 실패(`UNKNOWN`)는 **닫지 않는다.** 모르는 것을 "어긋나지 않았다"로 기록하면 진짜 미아 취소를
  영영 놓친다.
- 기준 시각이 `created_at`인 것이 핵심이다. 환불 가능 여부는 공연일까지 남은 날로 정해지므로
  한 달 전에 결제한 주문도 오늘 환불될 수 있다. 결제 시각으로 후보를 자르면 그런 건을 놓친다.
