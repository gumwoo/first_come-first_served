ALTER TABLE users ADD COLUMN marketing_opt_in BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN users.marketing_opt_in IS '이벤트/혜택 알림 수신 동의(선택 약관)';
