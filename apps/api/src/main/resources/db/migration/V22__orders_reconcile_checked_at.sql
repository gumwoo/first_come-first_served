-- 결제 정산 후보 순회용(ADR-011). 정산이 이 주문을 마지막으로 PG에 조회한 시각.
--
-- 만료된 주문은 조회해도 상태가 그대로라 후보에 남는다. 표시 없이 돌면 매 틱 같은 앞쪽 묶음만 보고,
-- 뒤쪽의 미아 승인은 소급 한계 밖으로 밀려날 때까지 조회되지 않는다.
--
-- 인덱스는 두지 않는다. 후보 조회는 ix_orders_status_exp로 소급 한계(기본 24시간) 안으로 좁힌 뒤 정렬할 것으로
-- 본다(추론, 실행 계획을 재지는 않았다).
-- nullable 추가라 구버전 Pod에 영향이 없다(구버전은 이 컬럼을 읽지도 쓰지도 않는다).
ALTER TABLE orders ADD COLUMN reconcile_checked_at TIMESTAMP;
COMMENT ON COLUMN orders.reconcile_checked_at IS '결제 정산이 마지막으로 PG에 조회한 시각(후보 순회용)';
