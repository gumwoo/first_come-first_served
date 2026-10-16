package com.flowticket.global.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.queue.service.QueueAdmissionService;
import com.flowticket.queue.service.QueueService;
import com.flowticket.seat.domain.Seat;
import com.flowticket.seat.domain.SeatStatus;
import com.flowticket.seat.repository.SeatRepository;
import com.flowticket.seat.service.SeatSeeder;
import com.flowticket.seat.service.SeatService;
import com.flowticket.support.IntegrationTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 실제 Hibernate 세션이 BookingDbSessionListener를 부르는지(hibernate.session.events.auto 배선) 확인한다.
 * 리스너는 스프링 빈이 아니라 전역 레지스트리에 남기므로, 스프링 부트 레지스트리가 전역 레지스트리에 붙어 있는지도 함께 본다 —
 * 붙어 있지 않으면 지표가 프로메테우스로 나가지 않는다.
 */
@SpringBootTest
class BookingDbMetricsIntegrationTest extends IntegrationTestSupport {

    @Autowired SeatService seatService;
    @Autowired SeatSeeder seatSeeder;
    @Autowired SeatRepository seatRepository;
    @Autowired EventRepository eventRepository;
    @Autowired QueueService queueService;
    @Autowired QueueAdmissionService admissionService;
    @Autowired MeterRegistry meterRegistry;

    private final SimpleMeterRegistry probe = new SimpleMeterRegistry();
    private Long eventId;

    @BeforeEach
    void seed() {
        Metrics.globalRegistry.add(probe);
        Event e = eventRepository.save(Event.builder()
                .kopisId("BDM1").title("예매 DB 계측").genre("연극").status(EventStatus.ON_SALE).build());
        eventId = e.getId();
        seatSeeder.seedForEvent(eventId);
    }

    @AfterEach
    void cleanup() {
        BookingDbContext.clear();
        Metrics.globalRegistry.remove(probe);
        probe.close();
    }

    @Test
    void 스프링_부트_레지스트리는_전역_레지스트리에_붙어_있다() {
        assertThat(Metrics.globalRegistry.getRegistries()).contains(meterRegistry);
    }

    @Test
    void 예매_동작으로_표시된_선점은_세션_DB_사용을_남긴다() {
        long user = 31L;
        String token = queueService.issue(user, eventId).token();
        admissionService.admit(eventId);
        List<Long> ids = seatRepository.findByEventId(eventId).stream()
                .filter(s -> s.getStatus() == SeatStatus.AVAILABLE)
                .map(Seat::getId).limit(1).toList();

        BookingDbContext.set("hold");
        try {
            seatService.hold(user, eventId, ids, token);
        } finally {
            BookingDbContext.clear();
        }

        Timer held = probe.find(BookingDbSessionListener.HELD).tag("op", "hold").timer();
        assertThat(held).as("선점 트랜잭션의 세션이 리스너를 거쳐야 한다").isNotNull();
        assertThat(held.count()).isPositive();
        assertThat(probe.get(BookingDbSessionListener.STATEMENTS).tag("op", "hold").summary().totalAmount())
                .as("선점은 SQL을 최소 한 번 실행한다").isPositive();
    }

    @Test
    void 표시가_없는_세션은_남기지_않는다() {
        seatRepository.findByEventId(eventId);

        // 전역 레지스트리를 공유해 다른 테스트가 만든 미터가 보일 수 있다(count 0) — 기록 건수로 본다.
        assertThat(probe.find(BookingDbSessionListener.HELD).timers().stream().mapToLong(Timer::count).sum())
                .isZero();
    }
}
