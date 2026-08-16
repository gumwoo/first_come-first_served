package com.flowticket.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 판매상태 게이트 회귀 — <b>대기열은 Redis만 보고 있었다.</b>
 *
 * <p>{@code queue:wait:{eventId}}는 eventId를 문자열로만 다뤘기 때문에, 실재하지 않는 이벤트나
 * 이미 CLOSED된 공연에도 줄을 세우고 토큰을 내줬다. 사용자는 대기가 끝난 뒤 좌석 단계에서야
 * 거절당했고, 그동안 정원(capacity) 한 자리를 실제로 점유했다.
 *
 * <p>게이트는 <b>두 군데</b>에 있고 둘 다 필요하다.
 * <ul>
 *   <li>{@code QueueService.issue()} — 애초에 줄을 세우지 않는다.</li>
 *   <li>{@code SeatService.hold()} — 발급 이후 상태가 바뀌는 창을 막는다. admit-ttl이 300초라
 *       운영자가 PAUSED로 내려도 이미 발급된 토큰은 그만큼 살아 있다.</li>
 * </ul>
 */
@TestPropertySource(properties = {"queue.capacity=10", "queue.admit-ttl=60"})
@SpringBootTest
class QueueSaleStateIntegrationTest extends IntegrationTestSupport {

    @Autowired QueueService queueService;
    @Autowired QueueAdmissionService admissionService;
    @Autowired SeatService seatService;
    @Autowired SeatSeeder seatSeeder;
    @Autowired EventRepository eventRepository;
    @Autowired JdbcTemplate jdbc;

    private Long saveEvent(EventStatus status) {
        return eventRepository.save(Event.builder()
                .kopisId("GATE-" + status).title("게이트").genre("연극").status(status).build()).getId();
    }

    @Test
    void 판매중이면_발급된다() {
        // 게이트가 "전부 막는" 것으로 통과하지 않도록 양성 경로를 함께 고정한다.
        assertThat(queueService.issue(1L, saveEvent(EventStatus.ON_SALE)).token()).isNotBlank();
    }

    /**
     * ON_SALE 외 5개 상태는 전부 막힌다. SCHEDULED(오픈 전)도 포함인데, 프론트가 이미
     * {@code beforeOpen}으로 예매 버튼을 잠그고 있어 서버 규칙을 거기에 맞춘 것이다.
     */
    @Test
    void 판매중이_아니면_발급이_거절된다() {
        // values()를 도는 것이 핵심이다 — 나중에 상태가 추가돼도 이 테스트가 자동으로 커버한다.
        // 목록을 손으로 나열하면 새 상태가 조용히 게이트를 빠져나간다.
        for (EventStatus status : EventStatus.values()) {
            if (status == EventStatus.ON_SALE) {
                continue;
            }
            Long eventId = saveEvent(status);

            assertThatThrownBy(() -> queueService.issue(1L, eventId))
                    .as("%s 상태에서는 대기열 발급이 막혀야 한다", status)
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.EVENT_NOT_ON_SALE);
        }
    }

    @Test
    void 없는_이벤트에는_줄을_세우지_않는다() {
        // 예전에는 Redis 키만 만들어져 통과했다 — 그 뒤 queue:active-events에 남아
        // 승격 워커가 영원히 스캔하는 유령 이벤트가 됐다.
        assertThatThrownBy(() -> queueService.issue(1L, 999_999L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void 발급후_판매가_중단되면_좌석선점이_거절된다() {
        Long eventId = saveEvent(EventStatus.ON_SALE);
        seatSeeder.seedForEvent(eventId);
        Long seatId = jdbc.queryForObject(
                "select id from seats where event_id=? and status='AVAILABLE' order by id limit 1",
                Long.class, eventId);

        String token = queueService.issue(1L, eventId).token();
        admissionService.admit(eventId);
        assertThat(queueService.isAdmitted(token, eventId)).isTrue();

        // 운영자가 판매를 내린다. 토큰은 admit-ttl 동안 여전히 유효하므로
        // 발급 시점 게이트만으로는 이 요청을 막을 수 없다.
        jdbc.update("update events set status='PAUSED' where id=?", eventId);

        assertThatThrownBy(() -> seatService.hold(1L, eventId, List.of(seatId), token))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EVENT_NOT_ON_SALE);
    }
}
