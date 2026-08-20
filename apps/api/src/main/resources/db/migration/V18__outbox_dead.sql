-- 아웃박스 릴레이의 결정적 실패 격리(DEAD). payload를 해석할 수 없어 재시도해도 결과가 같은 행을
-- PENDING에서 빼내, 그 행 하나가 뒤따르는 모든 이벤트를 영구 차단하던 문제를 없앤다.
--
-- 무중단 배포: 추가만 한다(파괴적 DDL 없음).
--  * status는 CHECK 없는 VARCHAR(20)이라 값 추가에 DDL이 필요 없다.
--  * 구버전 Pod는 DEAD를 모르지만, 릴레이는 status='PENDING'만, purge는 status='PUBLISHED'만
--    조회하므로 DEAD 행을 적재하지 않는다(모르는 enum 값으로 깨지지 않는다).
--  * last_error는 nullable이라 구버전 INSERT가 그대로 통과한다.
ALTER TABLE outbox_events ADD COLUMN last_error TEXT;

COMMENT ON COLUMN outbox_events.last_error IS '마지막 실패 원인(DEAD 판단 근거). 운영자가 폐기/복구를 판단하는 데 쓴다.';

-- 릴레이가 매 틱 "선행 DEAD가 있는 aggregate"를 조회한다. DEAD는 드물어 부분 인덱스가 작다.
CREATE INDEX ix_outbox_dead ON outbox_events (aggregate_type, aggregate_id) WHERE status = 'DEAD';
