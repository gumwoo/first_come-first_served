package com.flowticket.seat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.global.error.BusinessException;
import com.flowticket.queue.service.QueueAdmissionService;
import com.flowticket.queue.service.QueueService;
import com.flowticket.seat.domain.Seat;
import com.flowticket.seat.domain.SeatStatus;
import com.flowticket.seat.repository.EventSeatPriceRepository;
import com.flowticket.seat.repository.SeatHoldItemRepository;
import com.flowticket.seat.repository.SeatHoldRepository;
import com.flowticket.seat.repository.SeatRepository;
import com.flowticket.seat.service.SeatSeeder;
import com.flowticket.seat.service.SeatService;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 좌석·재고 핵심(Testcontainers). 초과판매 방지(조건부 UPDATE, IMP-003), 시딩 멱등/가격 티어,
 * 입장 게이트, 선점→SOLD_OUT. capacity 높게·워커 비활성으로 결정적.
 */
@SpringBootTest
@Testcontainers
class SeatInventoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");
    @Container
    static GenericContainer<?> redisContainer =
            new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.data.redis.host", redisContainer::getHost);
        r.add("spring.data.redis.port", () -> redisContainer.getMappedPort(6379));
        r.add("jwt.secret", () -> "integration-test-secret-0123456789-0123456789-0123456789");
        r.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        r.add("queue.capacity", () -> "100");
        r.add("queue.admit-interval-ms", () -> "3600000"); // 워커 자동실행 비활성
        r.add("seat.max-per-user", () -> "4");
    }

    @Autowired SeatService seatService;
    @Autowired SeatSeeder seatSeeder;
    @Autowired SeatRepository seatRepository;
    @Autowired EventSeatPriceRepository priceRepository;
    @Autowired SeatHoldRepository holdRepository;
    @Autowired SeatHoldItemRepository holdItemRepository;
    @Autowired EventRepository eventRepository;
    @Autowired QueueService queueService;
    @Autowired QueueAdmissionService admissionService;
    @Autowired JdbcTemplate jdbc;

    private Long eventId;
    private Long aSeatId;

    @BeforeEach
    void seed() {
        holdItemRepository.deleteAll();
        holdRepository.deleteAll();
        seatRepository.deleteAll();
        priceRepository.deleteAll();
        eventRepository.deleteAll();

        Event e = eventRepository.save(Event.builder()
                .kopisId("SEAT1").title("좌석 테스트").genre("연극").status(EventStatus.ON_SALE).build());
        eventId = e.getId();
        seatSeeder.seedForEvent(eventId); // 100석 + 가격
        aSeatId = availableSeatId();
    }

    @Test
    void 시딩은_멱등이고_가격티어와_base_price가_적용된다() {
        long before = seatRepository.count();
        seatSeeder.seedForEvent(eventId); // 재시드 → 중복 없음
        assertThat(seatRepository.count()).isEqualTo(before).isEqualTo(100L);
        assertThat(priceRepository.findByEventId(eventId)).hasSize(4);
        // 연극 → LOW 티어(A=30,000), base_price = A 최저가
        assertThat(eventRepository.findById(eventId).orElseThrow().getBasePrice()).isEqualTo(30000);
    }

    @Test
    void 조건부UPDATE_동시선점은_초과판매되지_않는다() throws Exception {
        AtomicInteger success = concurrent(20, () ->
                jdbc.update("update seats set status='HELD' where id=? and status='AVAILABLE'", aSeatId));
        assertThat(success.get()).isEqualTo(1); // 정확히 1명만 성공
    }

    @Test
    void 비교후행동_naive는_초과판매된다() throws Exception {
        // IMP-003 before: 읽고→비교→(지연)→무조건 UPDATE(비원자) → 여럿이 같은 좌석 선점
        AtomicInteger success = concurrent(20, () -> {
            String st = jdbc.queryForObject("select status from seats where id=?", String.class, aSeatId);
            if ("AVAILABLE".equals(st)) {
                sleep(20);
                jdbc.update("update seats set status='HELD' where id=?", aSeatId);
                return 1;
            }
            return 0;
        });
        assertThat(success.get()).isGreaterThan(1); // 초과판매 재현
    }

    @Test
    void 입장하지_않은_토큰은_선점이_거부된다() {
        assertThatThrownBy(() -> seatService.hold(1L, eventId, List.of(aSeatId), "not-admitted"))
                .isInstanceOf(BusinessException.class); // QUEUE_NOT_ADMITTED
    }

    @Test
    void 입장토큰_선점_성공하고_같은좌석_재선점은_SOLD_OUT() {
        String token = admittedToken(7L, eventId);
        assertThat(seatService.hold(7L, eventId, List.of(aSeatId), token).seatIds()).containsExactly(aSeatId);

        assertThatThrownBy(() -> seatService.hold(7L, eventId, List.of(aSeatId), token))
                .isInstanceOf(BusinessException.class); // 이미 HELD → SOLD_OUT
    }

    // --- helpers ---

    private Long availableSeatId() {
        return seatRepository.findByEventId(eventId).stream()
                .filter(s -> s.getStatus() == SeatStatus.AVAILABLE)
                .map(Seat::getId).findFirst().orElseThrow();
    }

    private String admittedToken(long userId, Long ev) {
        String token = queueService.issue(userId, ev).token();
        admissionService.admit(ev); // capacity 100 → 즉시 입장
        return token;
    }

    private AtomicInteger concurrent(int threads, java.util.concurrent.Callable<Integer> op) throws Exception {
        AtomicInteger success = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (op.call() == 1) {
                        success.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    // 낙관락 충돌 등은 실패로 간주
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        return success;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
