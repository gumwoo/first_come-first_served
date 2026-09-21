package com.flowticket.seat;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowticket.support.IntegrationTestSupport;

import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.queue.service.QueueAdmissionService;
import com.flowticket.queue.service.QueueService;
import com.flowticket.seat.domain.Seat;
import com.flowticket.seat.domain.SeatHoldStatus;
import com.flowticket.seat.domain.SeatStatus;
import com.flowticket.seat.repository.EventSeatPriceRepository;
import com.flowticket.seat.repository.SeatHoldItemRepository;
import com.flowticket.seat.repository.SeatHoldRepository;
import com.flowticket.seat.repository.SeatRepository;
import com.flowticket.seat.service.SeatHoldExpiryService;
import com.flowticket.seat.service.SeatSeeder;
import com.flowticket.seat.service.SeatService;
import com.flowticket.support.MutableClock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * 선점 만료를 시간을 옮겨서 검증한다(Clock 주입의 값).
 *
 * 이 경계는 지금까지 테스트하기 어려웠다. 홀드 TTL은 기본 5분이라 실제로 기다릴 수 없고,
 * DB의 expires_at을 손으로 과거로 미는 방법은 "만료 판정"이 아니라 "이미 만료된 행을 쓸어담는지"만
 * 확인한다. 시계를 옮기면 애플리케이션이 계산한 만료 시각을 그대로 두고 판정만 미래로 보낼 수 있다.
 */
@SpringBootTest
@Import(SeatHoldExpiryClockIntegrationTest.MutableClockConfig.class)
class SeatHoldExpiryClockIntegrationTest extends IntegrationTestSupport {

    @TestConfiguration
    static class MutableClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @Autowired MutableClock clock;
    @Autowired SeatService seatService;
    @Autowired SeatHoldExpiryService expiryService;
    @Autowired SeatSeeder seatSeeder;
    @Autowired QueueService queueService;
    @Autowired QueueAdmissionService admissionService;
    @Autowired SeatRepository seatRepository;
    @Autowired SeatHoldRepository holdRepository;
    @Autowired SeatHoldItemRepository holdItemRepository;
    @Autowired EventSeatPriceRepository priceRepository;
    @Autowired EventRepository eventRepository;

    @BeforeEach
    void clean() {
        holdItemRepository.deleteAll();
        holdRepository.deleteAll();
        seatRepository.deleteAll();
        priceRepository.deleteAll();
        eventRepository.deleteAll();
    }

    @Test
    void 홀드는_TTL이_지나야_만료된다() {
        long userId = 300L;
        Event e = eventRepository.save(Event.builder()
                .kopisId("CLK1").title("시계 테스트").genre("연극").status(EventStatus.ON_SALE).build());
        seatSeeder.seedForEvent(e.getId());
        String token = queueService.issue(userId, e.getId()).token();
        admissionService.admit(e.getId());
        List<Long> seatIds = seatRepository.findByEventId(e.getId()).stream()
                .filter(s -> s.getStatus() == SeatStatus.AVAILABLE)
                .map(Seat::getId).limit(1).toList();
        Long holdId = seatService.hold(userId, e.getId(), seatIds, token).holdId();

        // TTL(기본 300초) 안에서는 쓸어도 살아 있다.
        expiryService.sweepExpired();
        assertThat(holdRepository.findById(holdId).orElseThrow().getStatus())
                .isEqualTo(SeatHoldStatus.HELD);
        assertThat(seatRepository.findById(seatIds.get(0)).orElseThrow().getStatus())
                .isEqualTo(SeatStatus.HELD);

        // 시간을 넘기면 같은 홀드가 만료되고 좌석이 재고로 돌아온다.
        clock.advance(Duration.ofMinutes(6));
        expiryService.sweepExpired();

        assertThat(holdRepository.findById(holdId).orElseThrow().getStatus())
                .isEqualTo(SeatHoldStatus.EXPIRED);
        assertThat(seatRepository.findById(seatIds.get(0)).orElseThrow().getStatus())
                .isEqualTo(SeatStatus.AVAILABLE);
    }
}
