-- 실험실 시드: 판매중 공연 :events개(공연마다 100석, e2e/ci/seed.sql과 같은 등급 구성)와 시드 사용자 :users명.
-- run마다 새 공연을 쓰므로(좌석이 이전 run의 결과에 영향받지 않게) 공연을 넉넉히 만든다. 멱등(ON CONFLICT).
--   psql -v events=60 -v users=1200 -f seed.sql

INSERT INTO events (kopis_id, title, genre, status, base_price, start_date)
SELECT 'TRIAGE-LAB-' || g, '실험실 공연 ' || g, '연극', 'ON_SALE', 30000, CURRENT_DATE + 30
FROM generate_series(1, :events) AS g
ON CONFLICT (kopis_id) DO NOTHING;

INSERT INTO event_seat_prices (event_id, grade, price)
SELECT e.id, v.grade, v.price
FROM events e
CROSS JOIN (VALUES ('VIP', 60000), ('R', 48000), ('S', 39000), ('A', 30000)) AS v(grade, price)
WHERE e.kopis_id LIKE 'TRIAGE-LAB-%'
ON CONFLICT (event_id, grade) DO NOTHING;

INSERT INTO seats (event_id, grade, zone, seat_row, seat_col, status)
SELECT e.id, g.grade, g.grade, g.grade, s.col, 'AVAILABLE'
FROM events e
CROSS JOIN (VALUES ('VIP', 10), ('R', 20), ('S', 30), ('A', 40)) AS g(grade, cnt)
CROSS JOIN LATERAL generate_series(1, g.cnt) AS s(col)
WHERE e.kopis_id LIKE 'TRIAGE-LAB-%'
ON CONFLICT (event_id, zone, seat_row, seat_col) DO NOTHING;

-- 비밀번호 없는 계정(scripts/loadtest/seed-users.sh와 같은 형식). 토큰은 mint-tokens.mjs가 만든다.
INSERT INTO users (email, password_hash, name, phone, role, provider)
SELECT 'loadseed+' || g || '@example.com', NULL, 'loadseed' || g, '099' || lpad(g::text, 8, '0'), 'ROLE_USER', 'local'
FROM generate_series(1, :users) AS g
ON CONFLICT DO NOTHING;
