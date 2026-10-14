package com.flowticket.alert.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;

/**
 * 알림 임계치 수정. 둘 다 0 이상, 적어도 하나는 있어야 한다. 보내지 않은 값은 바꾸지 않는다.
 *
 * 둘 다 선택인 이유: 롤링 배포 중 구 화면은 dlqPendingThreshold만, 새 화면은 outboxDeadThreshold를 보낸다.
 * 예전처럼 원시 int로 받으면 빠진 값이 0으로 들어와 다른 쪽 임계치를 덮어쓴다.
 */
public record UpdateAlertRequest(
        @Min(0) Integer dlqPendingThreshold,
        @Min(0) Integer outboxDeadThreshold) {

    @AssertTrue(message = "dlqPendingThreshold 또는 outboxDeadThreshold 중 하나는 있어야 합니다.")
    public boolean isAnyPresent() {
        return dlqPendingThreshold != null || outboxDeadThreshold != null;
    }
}
