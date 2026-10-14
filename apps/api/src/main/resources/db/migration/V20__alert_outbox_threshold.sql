-- 운영 알림의 기준을 DLQ 적체에서 아웃박스 격리(DEAD)로 옮기는 Expand 단계(ADR-028, kafka-removal-design §5.3).
-- 추가만 한다: 이 컬럼을 모르는 구버전 Pod는 그대로 동작하고(validate는 엔티티 → 테이블 방향만 본다),
-- dlq_pending_threshold는 Kafka 제거 릴리스(N) 뒤 N+1에서 지운다.
ALTER TABLE alert_settings ADD COLUMN outbox_dead_threshold INTEGER NOT NULL DEFAULT 1;

COMMENT ON COLUMN alert_settings.outbox_dead_threshold IS '아웃박스 격리(DEAD) 행 경고 임계치. 이 값 이상이면 경고';
