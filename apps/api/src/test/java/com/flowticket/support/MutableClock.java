package com.flowticket.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 테스트가 앞으로 돌릴 수 있는 시계. 운영 빈(ClockConfig)을 @Primary로 대신한다.
 *
 * 시작점은 실제 현재 시각이다. 고정 시각으로 시작하면 DB의 now()·Redis TTL처럼 이 시계를 따르지
 * 않는 것들과 크게 어긋난다(ADR-018 §한계). 여기서 필요한 것은 절대 시각이 아니라 "앞으로 감기"다.
 */
public class MutableClock extends Clock {

    private Instant now = Instant.now();

    public void advance(Duration d) {
        now = now.plus(d);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneId.systemDefault();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
