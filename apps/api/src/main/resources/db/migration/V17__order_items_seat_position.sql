-- 주문 좌석 라인에 좌석 위치를 스냅샷으로 보관한다.
--
-- 왜: order_items 는 seat_id·grade·price 만 갖고 있어 **어느 자리인지**를 사용자에게 보여줄 수
-- 없었다. 예매 내역도 모바일 티켓(QR 화면)도 "A석"까지만 표시됐다. 실물 티켓팅에서 좌석 번호는
-- 입장에 쓰는 핵심 정보다. (2026-08-11 전체 플로우 실증 중 발견)
--
-- 왜 조인이 아니라 스냅샷인가: grade·price 를 이미 주문 시점 스냅샷으로 복제해 두었다(ADR-004).
-- 좌석 배치가 나중에 바뀌어도 과거 예매의 좌석 표기는 그대로여야 한다 — 가격이 바뀌어도 과거
-- 결제 금액이 그대로여야 하는 것과 같은 이유다. seats 를 조인하면 그 성질이 깨진다.
--
-- 무중단: nullable 컬럼 추가라 구버전 Pod가 깨지지 않는다(Expand). 구버전은 이 컬럼을 모르고,
-- 신버전은 NULL 을 허용한다. 백필은 아래에서 한 번에 처리하므로 별도 릴리스가 필요 없다.
ALTER TABLE order_items ADD COLUMN seat_row VARCHAR(10);
ALTER TABLE order_items ADD COLUMN seat_col INTEGER;

COMMENT ON COLUMN order_items.seat_row IS '주문 시점 좌석 열 스냅샷(ADR-004). seats 변경과 무관하게 고정';
COMMENT ON COLUMN order_items.seat_col IS '주문 시점 좌석 번호 스냅샷(ADR-004). seats 변경과 무관하게 고정';

-- 기존 주문 백필. seats 가 아직 그대로이므로 지금 시점의 값이 주문 시점 값과 같다.
-- (좌석 배치를 바꾼 적이 없다 — 배치 변경 이력이 있었다면 이 백필은 근사치가 된다)
UPDATE order_items oi
   SET seat_row = s.seat_row,
       seat_col = s.seat_col
  FROM seats s
 WHERE s.id = oi.seat_id
   AND oi.seat_row IS NULL;
