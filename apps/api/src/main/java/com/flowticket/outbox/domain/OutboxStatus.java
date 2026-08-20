package com.flowticket.outbox.domain;

/**
 * 아웃박스 발행 상태(S08, ADR-010). contracts/enums.yaml OutboxStatus 와 일치.
 * PENDING=적재됨/미발행(릴레이 대상), PUBLISHED=Kafka 발행 성공(7일 보존 후 purge),
 * DEAD=재시도로 성공할 수 없는 결정적 실패(격리, purge 대상 아님),
 * DISCARDED=운영자가 발행 포기를 판단한 행(격리 해제 — 같은 aggregate의 후속이 다시 흐른다).
 *
 * <p><b>DEAD는 "발행 실패"가 아니라 "재시도가 무의미함"을 뜻한다.</b> 브로커 장애처럼 나중에
 * 성공할 수 있는 실패는 PENDING을 유지한다 — 그쪽을 DEAD로 보내면 아웃박스의 "언젠가 복구되면
 * 발행한다"는 성질이 깨진다.
 */
public enum OutboxStatus {
    PENDING,
    PUBLISHED,
    DEAD,
    DISCARDED
}
