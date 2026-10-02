-- 정합성 사후 검증(loadtest-100k-plan §3.3). scripts/loadtest/check-correctness.sh가 psql로 실행한다.
--
--   psql -v since='2026-10-02 05:00:00' -At -F, -f correctness.sql
--
-- since 이후에 만들어진 주문만 본다(측정 run의 시작 시각, UTC 벽시계 — 앱 컨테이너가 TZ=UTC이고 DB에도 UTC 벽시계가
-- 쌓인다, api-deployment.yaml). 각 검사는 "<검사 이름>,<위반 수>" 한 줄을 낸다.
-- 위반 수가 0이 아니면 그 run은 정합성 실패다(§3.3). 어떤 행이 위반인지는 같은 조건으로 따로 조회한다.
--
-- 시각 주의: orders.paid_at은 DB 시계(CURRENT_TIMESTAMP), refunds.created_at은 앱 시계(LocalDateTime.now())다.
-- 두 시계가 어긋나면 일시적 이중 판매 검사가 경계에서 흔들릴 수 있다.

\set ON_ERROR_STOP on

-- 1) 초과판매: PAID 주문 둘 이상에 걸린 좌석(실시간 게이지와 같은 식을 run 범위로).
SELECT 'oversold_seats', count(*) FROM (
  SELECT oi.seat_id
    FROM orders o JOIN order_items oi ON oi.order_id = o.id
   WHERE o.status = 'PAID' AND o.created_at >= :'since'
   GROUP BY oi.seat_id HAVING count(*) > 1
) t;

-- 2) 일시적 이중 판매: 같은 좌석의 결제된 주문 a, b(a가 먼저 결제)에서, a의 환불이 완료되기 전에 b가 결제됐다.
--    a가 PG 결과 불명(CANCELLED인데 refund 행 없음)인 동안에도 좌석은 SOLD로 묶여 있어야 하므로 위반이다.
SELECT 'transient_double_sale', count(*) FROM (
  SELECT DISTINCT a.id AS earlier_order, b.id AS later_order
    FROM orders a
    JOIN order_items ai ON ai.order_id = a.id
    JOIN order_items bi ON bi.seat_id = ai.seat_id AND bi.order_id <> a.id
    JOIN orders b ON b.id = bi.order_id
   WHERE a.paid_at IS NOT NULL AND b.paid_at IS NOT NULL
     AND a.paid_at < b.paid_at
     AND b.created_at >= :'since'
     AND NOT EXISTS (
       SELECT 1 FROM refunds r WHERE r.order_id = a.id AND r.created_at <= b.paid_at
     )
) t;

-- 3a) PAID 주문의 좌석이 SOLD가 아니다.
SELECT 'paid_seat_not_sold', count(*) FROM (
  SELECT DISTINCT oi.seat_id
    FROM orders o JOIN order_items oi ON oi.order_id = o.id JOIN seats s ON s.id = oi.seat_id
   WHERE o.status = 'PAID' AND o.created_at >= :'since' AND s.status <> 'SOLD'
) t;

-- 3b) SOLD 좌석에 PAID·CANCELLED 주문이 모두 없다(CANCELLED + SOLD는 환불 진행 중의 정상 상태, ADR-021).
--     run 범위의 주문이 걸린 좌석만 본다(이전 run·시드의 좌석은 범위 밖).
SELECT 'sold_seat_without_order', count(*) FROM (
  SELECT s.id
    FROM seats s
   WHERE s.status = 'SOLD'
     AND EXISTS (SELECT 1 FROM order_items oi JOIN orders o ON o.id = oi.order_id
                  WHERE oi.seat_id = s.id AND o.created_at >= :'since')
     AND NOT EXISTS (SELECT 1 FROM order_items oi JOIN orders o ON o.id = oi.order_id
                      WHERE oi.seat_id = s.id AND o.status IN ('PAID', 'CANCELLED'))
) t;

-- 4a) 주문 하나에 승인된 결제가 둘 이상(이중 결제).
SELECT 'multiple_approved_payments', count(*) FROM (
  SELECT p.order_id
    FROM payments p JOIN orders o ON o.id = p.order_id
   WHERE p.status = 'APPROVED' AND o.created_at >= :'since'
   GROUP BY p.order_id HAVING count(*) > 1
) t;

-- 4b) 주문 하나에 환불 행이 둘 이상(이중 환불).
SELECT 'multiple_refunds', count(*) FROM (
  SELECT r.order_id
    FROM refunds r JOIN orders o ON o.id = r.order_id
   WHERE o.created_at >= :'since'
   GROUP BY r.order_id HAVING count(*) > 1
) t;

-- 4c) PAID 주문인데 승인된 결제가 없다.
SELECT 'paid_without_approved_payment', count(*) FROM orders o
 WHERE o.status = 'PAID' AND o.created_at >= :'since'
   AND NOT EXISTS (SELECT 1 FROM payments p WHERE p.order_id = o.id AND p.status = 'APPROVED');

-- 5) 아직 발행되지 않은 아웃박스 행(PENDING·DEAD). 0이 아니면 이벤트 유실 후보다.
--    PUBLISHED 행이 실제로 소비됐는지는 check-correctness.sh가 Redis 멱등 키로 대조한다.
SELECT 'outbox_not_published', count(*) FROM outbox_events
 WHERE status <> 'PUBLISHED' AND created_at >= :'since';
