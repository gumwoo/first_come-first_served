-- 가상계좌 입금 웹훅(DEPOSIT_CALLBACK) 위조 검증용 secret.
-- Toss는 가상계좌 결제 승인 응답에 secret을 주고, 입금 웹훅 body의 secret과 대조해 정상 요청을 판별한다
-- (HMAC 서명은 지급대행 웹훅 전용 — 입금 콜백은 secret 비교가 공식 검증법). 저장 후 웹훅에서 equals 비교.
ALTER TABLE payments ADD COLUMN vbank_secret VARCHAR(64);
COMMENT ON COLUMN payments.vbank_secret IS '가상계좌 입금 웹훅 검증용 secret(발급 시 저장 → DEPOSIT_CALLBACK secret과 대조)';
