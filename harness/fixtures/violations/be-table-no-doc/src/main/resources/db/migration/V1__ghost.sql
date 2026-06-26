-- VIOLATION: ghost_table을 만들지만 docs/db/ghost_table.md가 없음
-- → 하네스 스키마 문서 누락 검사가 실패해야 함
CREATE TABLE ghost_table (
    id BIGINT PRIMARY KEY,
    name VARCHAR(100) NOT NULL
);
