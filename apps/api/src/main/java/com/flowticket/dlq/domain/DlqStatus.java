package com.flowticket.dlq.domain;

/**
 * DLQ 메시지 처리 상태. contracts/enums.yaml DlqStatus 와 일치.
 * PENDING=적재됨/미처리, RETRYING=재시도 진행 중(예약), RETRIED=원본 토픽 재발행됨, DISCARDED=폐기.
 */
public enum DlqStatus {
    PENDING,
    RETRYING,
    RETRIED,
    DISCARDED
}
