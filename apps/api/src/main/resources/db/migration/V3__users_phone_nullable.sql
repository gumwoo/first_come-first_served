-- 소셜 계정(kakao/naver)은 가입 시 휴대폰 정보가 없으므로 phone을 nullable로.
-- UNIQUE 제약은 유지(Postgres는 NULL 다중 허용) → 1폰1계정은 phone이 있는 계정에만 적용.
ALTER TABLE users ALTER COLUMN phone DROP NOT NULL;

COMMENT ON COLUMN users.phone IS '휴대폰 번호. 로컬 가입은 필수(1폰1계정), 소셜 가입은 NULL 가능';
