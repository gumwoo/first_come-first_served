-- 위반 fixture: 예외 주석은 달았지만 사유가 비어 있다.
-- 사유 자리가 줄 끝에서 끝나므로, 다음 줄의 DDL을 사유로 읽으면 안 된다.
-- harness:allow-destructive-ddl:
ALTER TABLE orders DROP COLUMN legacy_code;
