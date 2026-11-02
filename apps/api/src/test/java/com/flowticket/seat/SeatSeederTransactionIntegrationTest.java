package com.flowticket.seat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.seat.domain.Seat;
import com.flowticket.seat.repository.EventSeatPriceRepository;
import com.flowticket.seat.repository.SeatRepository;
import com.flowticket.seat.service.SeatSeeder;
import com.flowticket.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 자동 시딩이 공연 하나를 트랜잭션 하나로 처리하는지 검증한다.
 *
 * 가격·좌석·최저가 쓰기가 따로 커밋되면 중간 실패가 부분 저장을 남긴다. 좌석이 없으니 다음 시딩이 다시
 * 시도하지만 남은 가격이 unique(event_id, grade)에 걸려, 그 공연은 동기화가 돌 때마다 실패한다.
 */
@SpringBootTest
class SeatSeederTransactionIntegrationTest extends IntegrationTestSupport {

    @Autowired SeatSeeder seatSeeder;
    @Autowired EventRepository eventRepository;
    @Autowired EventSeatPriceRepository priceRepository;
    @Autowired JdbcTemplate jdbc;
    @SpyBean SeatRepository seatRepository;

    @Test
    void 좌석_저장이_실패하면_그_공연에_아무것도_남지_않는다() {
        Long eventId = event("TX1");
        failSeatInsert(eventId);

        int seeded = seatSeeder.seedSellable();

        assertThat(seeded).isZero();
        assertThat(priceRepository.findByEventId(eventId)).isEmpty();
        assertThat(seatCount(eventId)).isZero();
        assertThat(eventRepository.findById(eventId).orElseThrow().getBasePrice()).isNull();
    }

    @Test
    void 좌석_저장이_실패한_공연은_다음_시딩에서_완성된다() {
        Long eventId = event("TX2");
        failSeatInsert(eventId);
        seatSeeder.seedSellable();
        reset(seatRepository);

        int seeded = seatSeeder.seedSellable();

        assertThat(seeded).isEqualTo(1);
        assertSeeded(eventId);
    }

    @Test
    void 한_공연이_실패해도_다른_공연은_시딩된다() {
        Long failing = event("TX3");
        Long healthy = event("TX4");
        failSeatInsert(failing);

        int seeded = seatSeeder.seedSellable();

        assertThat(seeded).isEqualTo(1);
        assertSeeded(healthy);
        assertThat(seatCount(failing)).isZero();
    }

    /**
     * 이전 코드의 부분 저장이 이미 DB에 있는 경우. 티어는 장르로 정해져 장르가 바뀌면 남은 값과 달라진다.
     * 남은 값을 살리면 등급 가격이 뒤집히거나 최저가 표시가 어긋나므로 현재 티어로 다시 만든다.
     */
    @Test
    void 가격만_남은_공연은_현재_티어로_다시_시딩된다() {
        Long eventId = event("TX5");
        jdbc.update("insert into event_seat_prices (event_id, grade, price) values (?, 'VIP', 77777)", eventId);

        int seeded = seatSeeder.seedSellable();

        assertThat(seeded).isEqualTo(1);
        assertSeeded(eventId);
        int vip = price(eventId, "VIP");
        int a = price(eventId, "A");
        assertThat(vip).isNotEqualTo(77777).isGreaterThan(a);
        assertThat(eventRepository.findById(eventId).orElseThrow().getBasePrice()).isEqualTo(a);
    }

    // --- helpers ---

    private Long event(String kopisId) {
        return eventRepository.save(Event.builder()
                .kopisId(kopisId).title("시딩 테스트").genre("연극").status(EventStatus.ON_SALE).build()).getId();
    }

    /** 이 공연의 좌석 저장만 실패시킨다. 실패 뒤의 처리가 이 테스트의 대상이다. */
    private void failSeatInsert(Long eventId) {
        doThrow(new IllegalStateException("좌석 저장 실패")).when(seatRepository).saveAll(
                ArgumentMatchers.<Iterable<Seat>>argThat(seats -> seats.iterator().next().getEventId().equals(eventId)));
    }

    private int price(Long eventId, String grade) {
        return jdbc.queryForObject("select price from event_seat_prices where event_id=? and grade=?",
                Integer.class, eventId, grade);
    }

    private void assertSeeded(Long eventId) {
        assertThat(seatCount(eventId)).isEqualTo(100);
        assertThat(priceRepository.findByEventId(eventId)).hasSize(4);
        assertThat(eventRepository.findById(eventId).orElseThrow().getBasePrice()).isNotNull();
    }

    private Integer seatCount(Long eventId) {
        return jdbc.queryForObject("select count(*) from seats where event_id=?", Integer.class, eventId);
    }
}
