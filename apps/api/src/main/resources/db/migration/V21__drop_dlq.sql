-- Kafka 제거의 Contract 단계(ADR-028, kafka-removal-design §5.3 — P4 = N+1).
-- N(#412)에서 DlqMessage 엔티티와 alert_settings.dlq_pending_threshold 매핑을 지웠고, 그 뒤 배포는 모두 N 이후 이미지다.
-- 이 마이그레이션이 적용된 뒤에는 N-1(#412 이전) 이미지로 롤백할 수 없다(validate가 없는 테이블·컬럼을 찾다 기동 실패).
-- harness:allow-destructive-ddl: N(#412)에서 엔티티·매핑 제거 완료, 클러스터 철거 상태라 N-1 Pod 없음 — 다음 기동은 N 이후 이미지만 뜬다
DROP TABLE dlq_messages;

ALTER TABLE alert_settings DROP COLUMN dlq_pending_threshold;

COMMENT ON TABLE alert_settings IS '운영 알림 임계치(S07). 단일 행(id=1). 아웃박스 격리(DEAD) 임계치.';
