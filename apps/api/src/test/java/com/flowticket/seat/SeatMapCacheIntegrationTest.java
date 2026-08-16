package com.flowticket.seat;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.seat.dto.SeatMapResponse;
import com.flowticket.seat.service.SeatSeeder;
import com.flowticket.seat.service.SeatService;
import com.flowticket.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 좌석맵 캐시(실험 스위치)의 동작과 <b>대가</b>를 함께 고정한다.
 *
 * <p>이 캐시는 성능 상한을 재기 위한 것이지 운영 최종 설계가 아니다
 * ({@code SeatService.getSeats} 주석). 그 판단의 근거가 되는 사실 —
 * <b>TTL 동안 좌석 상태 변경이 보이지 않는다</b> — 을 테스트로 박아둔다.
 * 나중에 이벤트 기반 무효화를 붙이면 이 테스트가 바뀌어야 하고, 그때 이 대가가
 * 해소됐다는 것이 드러난다.
 */
@TestPropertySource(properties = "seat.map-cache-ttl-ms=3000")
@SpringBootTest
class SeatMapCacheIntegrationTest extends IntegrationTestSupport {

    @Autowired SeatService seatService;
    @Autowired SeatSeeder seatSeeder;
    @Autowired EventRepository eventRepository;
    @Autowired JdbcTemplate jdbc;

    private Long eventId;

    @BeforeEach
    void seed() {
        eventId = eventRepository.save(Event.builder()
                .kopisId("CACHE1").title("캐시").genre("연극").status(EventStatus.ON_SALE).build()).getId();
        seatSeeder.seedForEvent(eventId);
    }

    @Test
    void 두번째_조회는_같은_결과를_돌려준다() {
        SeatMapResponse first = seatService.getSeats(eventId);
        SeatMapResponse second = seatService.getSeats(eventId);

        assertThat(second.seats()).hasSameSizeAs(first.seats());
        assertThat(second.eventId()).isEqualTo(first.eventId());
        assertThat(second.grades()).hasSameSizeAs(first.grades());
    }

    @Test
    void TTL_동안_좌석_상태_변경이_보이지_않는다() {
        long availableBefore = seatService.getSeats(eventId).seats().stream()
                .filter(s -> "AVAILABLE".equals(s.status())).count();
        assertThat(availableBefore).isEqualTo(100);

        // DB에서 직접 바꾼다 — 캐시를 거치지 않는 변경이라 무효화가 없으면 보이지 않는다.
        jdbc.update("update seats set status='HELD' where event_id=?", eventId);

        long availableAfter = seatService.getSeats(eventId).seats().stream()
                .filter(s -> "AVAILABLE".equals(s.status())).count();

        // ⚠️ 이것이 이 캐시의 **대가**다. 실패가 아니라 의도된 동작이고,
        // 그래서 운영 설계에는 이벤트 기반 무효화가 필요하다.
        assertThat(availableAfter)
                .as("TTL 안에서는 낡은 좌석맵이 그대로 보인다 — 무효화가 필요한 이유")
                .isEqualTo(100);
    }
}
