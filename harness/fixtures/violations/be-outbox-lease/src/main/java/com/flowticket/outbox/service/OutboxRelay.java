package com.flowticket.outbox.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 위반 fixture: 한 틱의 상한(예산 + 발행 대기)이 ShedLock 임차보다 길다. */
@Service
public class OutboxRelay {

    @Scheduled(fixedRateString = "${outbox.relay-interval-ms:1000}")
    @SchedulerLock(name = "outbox-relay", lockAtMostFor = "PT1M", lockAtLeastFor = "PT0S")
    public void publishPending() {
    }
}
