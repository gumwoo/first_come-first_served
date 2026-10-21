-- 0.2초마다 PostgreSQL 클라이언트 세션 상태를 한 줄(탭 구분)로 쓴다.
-- 열: 시각, 활성, 잠금 대기, idle in transaction, idle, 최장 트랜잭션(초), 활성 세션 대기 이벤트(공백 구분, 대기 없으면 CPU)
SELECT to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"') AS t,
       count(*) FILTER (WHERE state = 'active') AS active,
       count(*) FILTER (WHERE state = 'active' AND wait_event_type = 'Lock') AS lock_wait,
       count(*) FILTER (WHERE state = 'idle in transaction') AS idle_in_tx,
       count(*) FILTER (WHERE state = 'idle') AS idle,
       round(max(extract(epoch FROM clock_timestamp() - xact_start)) FILTER (WHERE state <> 'idle')::numeric, 3) AS max_xact_s,
       string_agg(CASE WHEN state = 'active' THEN coalesce(wait_event_type || ':' || wait_event, 'CPU') END, ' ' ORDER BY wait_event_type, wait_event) AS active_waits
FROM pg_stat_activity
WHERE datname = 'flowticket' AND backend_type = 'client backend' AND pid <> pg_backend_pid() \watch i=0.2 c=350
