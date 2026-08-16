package com.flowticket.seat;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.seat.dto.SeatMapResponse;
import com.flowticket.seat.service.SeatSeeder;
import com.flowticket.seat.service.SeatService;
import com.flowticket.support.IntegrationTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
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
    @Autowired MeterRegistry meterRegistry;

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

    /**
     * <b>캐시 hit은 DB 커넥션을 빌리지 않아야 한다.</b>
     *
     * <p>처음에는 클래스 레벨 {@code @Transactional(readOnly = true)} 때문에 캐시 hit이어도
     * 트랜잭션이 열리고 커넥션을 빌렸다 — 2026-08-16 실측에서 요청 36,002건에 커넥션 획득
     * 36,173회로 <b>요청당 1회</b>가 그대로 나왔다. 캐시가 쿼리는 없앴는데 트랜잭션 비용은
     * 남은 것이다. {@code getSeats}를 {@code NOT_SUPPORTED}로 빼서 고쳤고, 이 테스트가
     * 그 수정을 고정한다.
     */
    @Test
    void 캐시_hit은_DB_커넥션을_빌리지_않는다() {
        seatService.getSeats(eventId); // 첫 호출 = miss → 여기서만 커넥션을 쓴다

        double before = acquireCount();
        for (int i = 0; i < 5; i++) {
            seatService.getSeats(eventId);
        }
        double after = acquireCount();

        assertThat(after - before)
                .as("캐시 hit 5회가 커넥션을 추가로 빌리면 안 된다")
                .isZero();
    }

    /** Hikari 커넥션 획득 누적 횟수. 없으면 0(다른 이유로 지표가 빠진 경우 테스트가 오탐하지 않게). */
    private double acquireCount() {
        var t = meterRegistry.find("hikaricp.connections.acquire").timer();
        return t == null ? 0 : t.count();
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
