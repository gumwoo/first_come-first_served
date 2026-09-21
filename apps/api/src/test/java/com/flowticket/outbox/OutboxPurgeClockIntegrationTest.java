package com.flowticket.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowticket.support.IntegrationTestSupport;

import com.flowticket.outbox.domain.OutboxEvent;
import com.flowticket.outbox.domain.OutboxStatus;
import com.flowticket.outbox.repository.OutboxEventRepository;
import com.flowticket.outbox.service.OutboxRelay;
import com.flowticket.support.MutableClock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * 아웃박스 보존기간 경과 판정을 시간을 옮겨서 검증한다(ADR-018).
 *
 * purge 기준(now − 보존기간)과 비교 대상(publishedAt)이 서로 다른 시계를 보면, 시계를 옮겨도
 * 경계가 움직이지 않는다. 둘 다 같은 Clock을 쓰는지 확인한다.
 *
 * 발행 자체는 브로커가 필요해 여기서 돌리지 않는다. 대신 릴레이와 같은 방식으로
 * 시계 값을 넘겨 PUBLISHED를 만든다(markPublished는 이제 시각을 인자로 받는다).
 */
@SpringBootTest
@Import(OutboxPurgeClockIntegrationTest.MutableClockConfig.class)
class OutboxPurgeClockIntegrationTest extends IntegrationTestSupport {

    @TestConfiguration
    static class MutableClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @Autowired MutableClock clock;
    @Autowired OutboxRelay relay;
    @Autowired OutboxEventRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void 발행완료_행은_보존기간이_지나야_지워진다() {
        UUID id = UUID.randomUUID();
        OutboxEvent row = repository.save(new OutboxEvent(
                id, "order", 1L, "order.paid", "{\"eventId\":\"" + id + "\"}"));
        row.markPublished(LocalDateTime.now(clock));
        repository.saveAndFlush(row);

        // 보존기간(기본 7일) 안에서는 남는다.
        relay.purgePublished();
        assertThat(repository.findById(row.getId())).isPresent();

        // 8일 뒤에는 지워진다. publishedAt도 같은 시계로 찍혔기 때문에 경계가 실제로 움직인다.
        clock.advance(Duration.ofDays(8));
        relay.purgePublished();

        assertThat(repository.findById(row.getId())).isEmpty();
    }

    /** 발행되지 않은 행은 아무리 오래돼도 지우지 않는다(유실 방지). */
    @Test
    void 미발행_행은_보존기간이_지나도_남는다() {
        UUID id = UUID.randomUUID();
        OutboxEvent pending = repository.saveAndFlush(new OutboxEvent(
                id, "order", 2L, "order.paid", "{\"eventId\":\"" + id + "\"}"));

        clock.advance(Duration.ofDays(30));
        relay.purgePublished();

        assertThat(repository.findById(pending.getId()))
                .isPresent()
                .get()
                .extracting(OutboxEvent::getStatus)
                .isEqualTo(OutboxStatus.PENDING);
    }
}
