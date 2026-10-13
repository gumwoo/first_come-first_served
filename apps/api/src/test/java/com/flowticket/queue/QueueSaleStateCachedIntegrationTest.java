package com.flowticket.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import com.flowticket.queue.service.QueueAdmissionService;
import com.flowticket.queue.service.QueueService;
import com.flowticket.seat.service.SeatSeeder;
import com.flowticket.seat.service.SeatService;
import com.flowticket.support.IntegrationTestSupport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 운영 설정(판매 상태 캐시 TTL 1초 — ConfigMap QUEUE_BOOKABLE_CACHE_TTL_MS=1000, IMP-026)에서의 판매 상태 게이트.
 *
 * QueueSaleStateIntegrationTest는 앱 기본값(TTL 0 — 매번 DB)으로 돈다. 운영은 진입 경로가 파드 메모리 캐시를 거치므로
 * 판매를 내린 직후 최대 TTL 동안 진입이 받아질 수 있다(설계상 허용). 그 대가가 좌석까지 번지지 않는지를 운영 값 그대로 확인한다.
 * 실제 시간에 기대는 단정(“1초 안에는 받아진다”)은 CI 속도에 따라 흔들리므로 하지 않는다 — 그 동작은 가짜 시계를 쓰는
 * BookableEventCacheTest가 맡고, 여기서는 시간과 무관하게 성립하는 상한만 본다.
 */
@TestPropertySource(properties = {"queue.capacity=10", "queue.admit-ttl=60", "queue.bookable-cache-ttl-ms=1000"})
@SpringBootTest
class QueueSaleStateCachedIntegrationTest extends IntegrationTestSupport {

    private static final long TTL_MS = 1000;

    @Autowired QueueService queueService;
    @Autowired QueueAdmissionService admissionService;
    @Autowired SeatService seatService;
    @Autowired SeatSeeder seatSeeder;
    @SpyBean EventRepository eventRepository;
    @Autowired JdbcTemplate jdbc;

    private Long saveOnSaleEvent(String key) {
        return eventRepository.save(Event.builder()
                .kopisId("CACHE-" + key + "-" + System.nanoTime()).title("캐시").genre("연극")
                .status(EventStatus.ON_SALE).build()).getId();
    }

    @Test
    void 진입이_몰려도_공연_조회는_TTL당_한_번이다() {
        Long eventId = saveOnSaleEvent("burst");
        clearInvocations(eventRepository);

        long start = System.nanoTime();
        for (long userId = 1; userId <= 50; userId++) {
            assertThat(queueService.issue(userId, eventId).token()).isNotBlank();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // 처음 한 번 읽고, 그 뒤로는 TTL이 지날 때마다만 다시 읽는다 → 1 + (경과 ÷ TTL)회가 상한이다.
        // 캐시가 꺼져 있었다면 50회다.
        int bound = (int) (1 + elapsedMs / TTL_MS);
        verify(eventRepository, atMost(bound)).findById(eventId);
        assertThat(bound).as("상한이 50에 가까우면 이 테스트는 의미가 없다(경과 %d ms)", elapsedMs).isLessThan(10);
    }

    @Test
    void 판매를_내리면_진입_캐시와_무관하게_좌석은_즉시_막힌다() {
        Long eventId = saveOnSaleEvent("hold");
        seatSeeder.seedForEvent(eventId);
        Long seatId = jdbc.queryForObject(
                "select id from seats where event_id=? and status='AVAILABLE' order by id limit 1",
                Long.class, eventId);

        String token = queueService.issue(1L, eventId).token(); // 진입 캐시에 ON_SALE이 들어간다
        admissionService.admit(eventId);
        assertThat(queueService.isAdmitted(token, eventId, 1L)).isTrue();

        jdbc.update("update events set status='PAUSED' where id=?", eventId);

        // 좌석 선점은 캐시를 쓰지 않고 DB에서 판매 상태를 다시 본다(SeatService.hold).
        assertThatThrownBy(() -> seatService.hold(1L, eventId, List.of(seatId), token))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EVENT_NOT_ON_SALE);
    }

    @Test
    void 판매를_내리면_TTL이_지난_뒤에는_진입도_막힌다() throws InterruptedException {
        Long eventId = saveOnSaleEvent("entry");
        queueService.issue(1L, eventId); // 캐시에 ON_SALE

        jdbc.update("update events set status='PAUSED' where id=?", eventId);
        Thread.sleep(TTL_MS + 200); // 반영 지연의 상한(TTL)을 넘긴다

        assertThatThrownBy(() -> queueService.issue(2L, eventId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EVENT_NOT_ON_SALE);
    }
}
