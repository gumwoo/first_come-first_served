-- E2E CI 테스트 데이터 시드.
-- CI의 빈 DB엔 KOPIS 이벤트/좌석이 없으므로(키·네트워크 의존 불가),
-- 판매중(ON_SALE) 이벤트 1개 + 등급 가격 + 좌석 100석(VIP10/R20/S30/A40)을 직접 심는다.
-- 실제 시더(SeatSeeder)와 동일한 구조: zone=seat_row=grade, seat_col=1..n. 멱등(ON CONFLICT).

INSERT INTO events (kopis_id, title, genre, status, base_price, start_date)
VALUES ('E2E-SEED-1', 'E2E 테스트 공연', '연극', 'ON_SALE', 30000, CURRENT_DATE + 30)
ON CONFLICT (kopis_id) DO NOTHING;

INSERT INTO event_seat_prices (event_id, grade, price)
SELECT e.id, v.grade, v.price
FROM events e
CROSS JOIN (VALUES ('VIP', 60000), ('R', 48000), ('S', 39000), ('A', 30000)) AS v(grade, price)
WHERE e.kopis_id = 'E2E-SEED-1'
ON CONFLICT (event_id, grade) DO NOTHING;

INSERT INTO seats (event_id, grade, zone, seat_row, seat_col, status)
SELECT e.id, g.grade, g.grade, g.grade, s.col, 'AVAILABLE'
FROM events e
CROSS JOIN (VALUES ('VIP', 10), ('R', 20), ('S', 30), ('A', 40)) AS g(grade, cnt)
CROSS JOIN LATERAL generate_series(1, g.cnt) AS s(col)
WHERE e.kopis_id = 'E2E-SEED-1'
ON CONFLICT (event_id, zone, seat_row, seat_col) DO NOTHING;
