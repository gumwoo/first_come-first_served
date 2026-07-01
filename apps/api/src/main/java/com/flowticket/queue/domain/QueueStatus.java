package com.flowticket.queue.domain;

/** 대기열 토큰 상태. contracts/enums.yaml QueueStatus와 일치(하네스 검사). */
public enum QueueStatus {
    // 대기 중 / 입장 허용(좌석선택 가능) / 토큰·입장창 만료
    WAITING,
    ADMITTED,
    EXPIRED,
}
