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
import com.flowticket.seat.domain.SeatHoldStatus;
import com.flowticket.seat.domain.SeatStatus;
import com.flowticket.seat.dto.HoldResponse;
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
import org.springframework.data.redis.core.StringRedisTemplate;
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
        r.add("seat.hold-ttl", () -> "1");                 // 만료 테스트용(1초)
        r.add("seat.sweep-interval-ms", () -> "3600000");  // 만료 워커 자동실행 비활성
    }

    @Autowired SeatService seatService;
    @Autowired SeatSeeder seatSeeder;
    @Autowired com.flowticket.seat.service.SeatHoldExpiryService expiryService;
    @Autowired SeatRepository seatRepository;
    @Autowired EventSeatPriceRepository priceRepository;
    @Autowired SeatHoldRepository holdRepository;
    @Autowired SeatHoldItemRepository holdItemRepository;
    @Autowired EventRepository eventRepository;
    @Autowired QueueService queueService;
    @Autowired QueueAdmissionService admissionService;
    @Autowired StringRedisTemplate redisTemplate;
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
    void 자동시딩은_판매가능_미시딩_이벤트만_생성한다() {
        Event onSale = eventRepository.save(Event.builder()
                .kopisId("SEAT2").title("판매중").genre("연극").status(EventStatus.ON_SALE).build());
        Event closed = eventRepository.save(Event.builder()
                .kopisId("SEAT3").title("종료").genre("연극").status(EventStatus.CLOSED).build());

        int seeded = seatSeeder.seedSellable();

        assertThat(seatRepository.existsByEventId(onSale.getId())).isTrue();   // 판매가능 → 시딩
        assertThat(seatRepository.existsByEventId(closed.getId())).isFalse();  // 종료 → 미시딩
        assertThat(seeded).isEqualTo(1); // @BeforeEach 이벤트는 이미 시딩 → 건너뜀
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

    @Test
    void 만료된_선점은_sweep으로_좌석이_복구된다() throws Exception {
        String token = admittedToken(8L, eventId);
        seatService.hold(8L, eventId, List.of(aSeatId), token);
        assertThat(seatStatus(aSeatId)).isEqualTo("HELD");

        Thread.sleep(1500); // hold-ttl(1s) 경과
        expiryService.sweepExpired();

        assertThat(seatStatus(aSeatId)).isEqualTo("AVAILABLE"); // 재고 복구
    }

    @Test
    void 선점_해제는_좌석과_홀드_상태를_함께_되돌린다() {
        // 회귀 방지: releaseSeats(@Modifying clearAutomatically)가 컨텍스트를 비워
        // hold.release()가 detached로 유실되던 버그 — 좌석은 풀리는데 홀드가 HELD로 남음.
        String token = admittedToken(20L, eventId);
        HoldResponse held = seatService.hold(20L, eventId, List.of(aSeatId), token);
        assertThat(seatStatus(aSeatId)).isEqualTo("HELD");

        seatService.release(held.holdId(), 20L);

        assertThat(seatStatus(aSeatId)).isEqualTo("AVAILABLE"); // 좌석 복구
        assertThat(holdRepository.findById(held.holdId()).orElseThrow().getStatus())
                .isEqualTo(SeatHoldStatus.RELEASED);            // 홀드도 함께 RELEASED
        // 한도가 회복돼 같은 유저가 다시 선점 가능(스테일 HELD 홀드가 카운트에 남지 않음)
        assertThat(seatService.hold(20L, eventId, List.of(aSeatId), token).seatIds())
                .containsExactly(aSeatId);
    }

    @Test
    void 만료_복구된_좌석은_다른_유저가_다시_선점할_수_있다() throws Exception {
        // 상태기계 분기: sweep은 status만 되돌리는 게 아니라 실제 재선점까지 가능해야 함.
        String t8 = admittedToken(8L, eventId);
        seatService.hold(8L, eventId, List.of(aSeatId), t8);

        Thread.sleep(1500); // hold-ttl(1s) 경과
        expiryService.sweepExpired();

        String t9 = admittedToken(9L, eventId);
        assertThat(seatService.hold(9L, eventId, List.of(aSeatId), t9).seatIds())
                .containsExactly(aSeatId); // 복구된 좌석을 다른 유저가 선점 성공
    }

    @Test
    void 누적_선점이_1인_최대매수를_초과하면_거부된다() {
        // 상태기계 분기: 이미 보유한 HELD 수 + 요청 수 > max 면 거부(seat.max-per-user=4).
        String token = admittedToken(10L, eventId);
        List<Long> ids = availableSeatIds(5);

        seatService.hold(10L, eventId, ids.subList(0, 4), token); // 4매 → OK

        assertThatThrownBy(() -> seatService.hold(10L, eventId, ids.subList(4, 5), token))
                .isInstanceOf(BusinessException.class); // 4 + 1 > 4 → MAX_PER_USER_EXCEEDED
    }

    @Test
    void 만료된_hold를_해제하려_하면_거부된다() throws Exception {
        // 상태기계 분기: sweep으로 EXPIRED된 hold는 더 이상 해제 대상이 아님(중복 상태전이 방지).
        String token = admittedToken(11L, eventId);
        HoldResponse held = seatService.hold(11L, eventId, List.of(aSeatId), token);

        Thread.sleep(1500); // hold-ttl(1s) 경과
        expiryService.sweepExpired(); // hold → EXPIRED

        assertThatThrownBy(() -> seatService.release(held.holdId(), 11L))
                .isInstanceOf(BusinessException.class); // INVALID_STATE_TRANSITION
    }

    @Test
    void 입장창이_만료된_토큰으로_선점하면_거부된다() {
        // 상태기계 분기: 입장(ADMITTED) 후 입장창이 만료되면 좌석 선점 게이트가 막아야 함.
        String token = admittedToken(12L, eventId);
        redisTemplate.delete("queue:admit:" + token); // 입장창 만료 시뮬레이션(admit 키 소멸)

        assertThatThrownBy(() -> seatService.hold(12L, eventId, List.of(aSeatId), token))
                .isInstanceOf(BusinessException.class); // QUEUE_NOT_ADMITTED
    }

    // --- helpers ---

    private String seatStatus(Long seatId) {
        return jdbc.queryForObject("select status from seats where id=?", String.class, seatId);
    }

    private Long availableSeatId() {
        return seatRepository.findByEventId(eventId).stream()
                .filter(s -> s.getStatus() == SeatStatus.AVAILABLE)
                .map(Seat::getId).findFirst().orElseThrow();
    }

    private List<Long> availableSeatIds(int n) {
        return seatRepository.findByEventId(eventId).stream()
                .filter(s -> s.getStatus() == SeatStatus.AVAILABLE)
                .map(Seat::getId).limit(n).toList();
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
