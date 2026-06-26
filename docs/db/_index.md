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
| users | S01 | [users.md](users.md) | 예정(S01 구현 시 작성) |
| (events) | S02 | (예정) | |
| (seats) | S04 | (예정) | |
| (orders) | S05 | (예정) | |
