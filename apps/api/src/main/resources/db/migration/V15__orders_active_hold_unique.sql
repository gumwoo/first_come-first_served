-- 같은 hold로 활성 주문이 둘 이상 생기지 않게 한다(TS-014).
--
-- OrderService.create는 "이 hold의 활성 주문을 찾고, 없으면 만든다"로 더블 POST를 방어했다.
-- 순차 호출에는 충분하지만 **동시에 오면 둘 다 "없음"을 보고 각자 INSERT**한다.
-- 같은 좌석에 활성 주문이 둘이면 둘 다 결제를 시도할 수 있고, 두 번째는 좌석 조건부
-- UPDATE(HELD→SOLD)에서 0행으로 롤백되지만 **그 전에 PG 승인이 나갔다면 미아 승인이 남는다**
-- (ADR-011 정산 대상). 앱의 검사는 UX용이고 **최종 방어선은 DB 제약**이어야 한다.
--
-- 부분 인덱스인 이유: 만료·취소된 주문까지 유일하게 묶으면 재주문이 막힌다.
-- 활성(PENDING/VBANK_WAITING)일 때만 hold당 하나로 강제하고, EXPIRED/CANCELLED/REFUNDED로
-- 빠진 뒤에는 같은 hold로 다시 만들 수 있다.
--
-- 롤링 배포 주의: 이 인덱스가 먼저 반영되고 구버전 Pod가 아직 살아 있으면, 구버전이 중복을
-- 만들려 할 때 제약 위반이 그대로 500으로 나간다(신버전은 잡아서 기존 주문을 반환한다).
-- 중복 데이터가 생기는 것보다 낫고 창이 짧아 감수한다 — 파괴적 DDL은 아니므로 Expand-Contract
-- 대상은 아니다.
--
-- 전제: 적용 시점에 같은 hold의 활성 주문이 둘 이상이면 인덱스 생성이 실패한다.
--   select hold_id, count(*) from orders
--    where status in ('PENDING','VBANK_WAITING') group by hold_id having count(*) > 1;
-- 로 먼저 확인한다(현재는 운영 데이터가 없어 해당 없음).

CREATE UNIQUE INDEX uq_orders_active_hold
    ON orders (hold_id)
 WHERE status IN ('PENDING', 'VBANK_WAITING');

COMMENT ON INDEX uq_orders_active_hold IS
    'hold당 활성 주문 1건 강제. 앱의 멱등 검사(찾고→없으면 생성)가 동시 요청에서 뚫리는 것을 DB에서 막는다(TS-014)';
