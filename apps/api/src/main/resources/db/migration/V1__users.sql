CREATE TABLE users (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(60),
    name          VARCHAR(50)  NOT NULL,
    phone         VARCHAR(20)  NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'ROLE_USER',
    provider      VARCHAR(20)  NOT NULL DEFAULT 'local',
    created_at    TIMESTAMP    NOT NULL DEFAULT now()
);

ALTER TABLE users ADD CONSTRAINT uq_users_email UNIQUE (email);
ALTER TABLE users ADD CONSTRAINT uq_users_phone UNIQUE (phone);

COMMENT ON TABLE  users               IS '회원 계정(로컬/소셜)';
COMMENT ON COLUMN users.email         IS '로그인 아이디, 전역 유일';
COMMENT ON COLUMN users.password_hash IS 'BCrypt 해시. 소셜 계정은 NULL';
COMMENT ON COLUMN users.phone         IS '휴대폰 번호. 1폰1계정 정책상 UNIQUE';
COMMENT ON COLUMN users.role          IS 'ROLE_USER/ROLE_ADMIN/ROLE_DEV. 가입은 항상 ROLE_USER';
COMMENT ON COLUMN users.provider      IS 'local/kakao/naver';
