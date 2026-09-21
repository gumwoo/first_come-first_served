-- 환불 시도 기록(ADR-011). 환불 정산의 후보 축.
--
-- 환불은 PG 취소와 DB 쓰기를 한 트랜잭션에 묶는다. PG 취소가 성공한 뒤 뒤쪽 쓰기가 실패하면
-- 전체가 롤백돼 주문이 PAID로 돌아가고, refunds에도 아무것도 남지 않는다. 그 상태를 찾으려면
-- "환불을 시도했다"는 사실이 그 트랜잭션 밖에 남아 있어야 한다.
--
-- 이 행은 refunds와 역할이 다르다. refunds는 성공한 환불의 장부이고, 이쪽은 정산이 확인해야 할
-- 작업 목록이다. 그래서 실패·롤백된 시도도 남는다.
CREATE TABLE refund_attempts (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id        BIGINT      NOT NULL REFERENCES orders(id),
    idempotency_key VARCHAR(80) NOT NULL,  -- 환불 요청의 멱등키(refunds와 같은 값)
    resolved        BOOLEAN     NOT NULL DEFAULT false,
    checked_at      TIMESTAMP,             -- 정산이 마지막으로 PG에 조회한 시각(후보 순회용)
    created_at      TIMESTAMP   NOT NULL DEFAULT now()
);
ALTER TABLE refund_attempts ADD CONSTRAINT uq_refund_attempts_idem UNIQUE (idempotency_key);

-- 정산 후보 조회 전용. 미해결 행만 보므로 부분 인덱스로 충분하다. 정렬과 같은 NULLS FIRST.
CREATE INDEX ix_refund_attempts_pending ON refund_attempts (checked_at ASC NULLS FIRST)
    WHERE resolved = false;

COMMENT ON TABLE refund_attempts IS '환불 시도 기록. 롤백돼도 남아 환불 정산의 후보가 된다(ADR-011)';
