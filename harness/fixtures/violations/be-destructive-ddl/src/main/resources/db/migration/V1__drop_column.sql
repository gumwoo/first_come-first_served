-- 위반 fixture: 예외 주석 없이 컬럼을 삭제한다.
-- 롤링 배포 중 아직 살아 있는 구버전 Pod가 이 컬럼을 참조하면 즉시 터진다.
ALTER TABLE orders DROP COLUMN legacy_code;
