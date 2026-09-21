-- 환불 정산(ADR-011)의 후보 순회 표시. 마지막으로 PG에 물어본 시각.
--
-- 없으면 정산이 항상 "가장 오래된 PAID 50건"만 다시 집어, 후보가 배치보다 많을 때 뒤쪽 주문은
-- 소급 한계(24시간) 밖으로 밀려날 때까지 한 번도 조회되지 않는다(starvation).
-- 이 값으로 오래 안 본 순서로 정렬해 돌린다.
--
-- 추가만 한다(nullable). 롤링 배포 중 구버전 파드는 이 컬럼을 모르고, 그래도 동작한다.
ALTER TABLE orders ADD COLUMN refund_checked_at TIMESTAMP;

-- 후보 조회 전용. PAID만 보므로 부분 인덱스로 충분하다. 정렬과 같은 NULLS FIRST로 만든다.
CREATE INDEX ix_orders_refund_recheck ON orders (refund_checked_at ASC NULLS FIRST)
    WHERE status = 'PAID';

COMMENT ON COLUMN orders.refund_checked_at IS '환불 정산이 마지막으로 PG에 조회한 시각(ADR-011)';
