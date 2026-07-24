# DB Schema Index

각 테이블은 `docs/db/<table>.md` 문서를 가진다.
**Flyway 마이그레이션에서 `CREATE TABLE`을 추가하면 대응 MD가 반드시 있어야 한다**
(없으면 하네스 `harness:backend` 실패 — drift 방지).

## 단일 진실원 원칙
- 실제 실행 DDL의 단일 진실원은 **Flyway 마이그레이션 파일**
  (`apps/api/src/main/resources/db/migration/V*__*.sql`).
- `docs/db/<table>.md`의 DDL 블록은 **읽기용 스냅샷**이며, 마이그레이션 파일을 링크한다.
- 하네스는 "MD 존재 여부"만 강제한다(내용 일치는 리뷰 영역).

## 테이블 목록
| 테이블 | 슬라이스 | 문서 | 상태 |
|--------|----------|------|------|
| users | S01 | [users.md](users.md) | 구현(V1~V3) |
| events | S02 | [events.md](events.md) | 구현(V4__events.sql) |
| seats | S04 | [seats.md](seats.md) | 설계(마이그레이션 후속 V6) |
| event_seat_prices | S04 | [event_seat_prices.md](event_seat_prices.md) | 설계(V6) |
| seat_holds | S04 | [seat_holds.md](seat_holds.md) | 설계(V7) |
| orders | S05 | [orders.md](orders.md) | 설계(V8) |
| order_items | S05 | [order_items.md](order_items.md) | 설계(V8) |
| payments | S05 | [payments.md](payments.md) | 설계(V9) |
| dlq_messages | S07 | [dlq_messages.md](dlq_messages.md) | 구현(V12__dlq_messages.sql) |
