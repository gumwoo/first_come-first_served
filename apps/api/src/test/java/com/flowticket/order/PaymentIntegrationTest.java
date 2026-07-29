package com.flowticket.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.global.error.BusinessException;
import com.flowticket.order.domain.OrderStatus;
import com.flowticket.order.dto.PaymentResponse;
import com.flowticket.order.repository.OrderItemRepository;
import com.flowticket.order.repository.OrderRepository;
import com.flowticket.order.repository.PaymentRepository;
import com.flowticket.order.service.OrderService;
import com.flowticket.order.service.PaymentService;
import com.flowticket.queue.service.QueueAdmissionService;
import com.flowticket.queue.service.QueueService;
import com.flowticket.seat.domain.Seat;
import com.flowticket.seat.domain.SeatHoldStatus;
import com.flowticket.seat.domain.SeatStatus;
import com.flowticket.seat.repository.EventSeatPriceRepository;
import com.flowticket.seat.repository.SeatHoldItemRepository;
import com.flowticket.seat.repository.SeatHoldRepository;
import com.flowticket.seat.repository.SeatRepository;
import com.flowticket.seat.service.SeatSeeder;
import com.flowticket.seat.service.SeatService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 결제 승인(BE-2): Mock 승인 → PENDING→PAID(조건부) + 좌석 SOLD + hold CONVERTED,
 * 거절 시 payment FAILED/order PENDING 유지, 멱등, 이미 PAID 재결제 거부.
 */
@SpringBootTest
@Testcontainers
class PaymentIntegrationTest {

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
        r.add("queue.admit-interval-ms", () -> "3600000");
        r.add("seat.max-per-user", () -> "4");
        r.add("seat.hold-ttl", () -> "300");
        r.add("seat.sweep-interval-ms", () -> "3600000");
        r.add("order.sweep-interval-ms", () -> "3600000");
    }

    @Autowired OrderService orderService;
    @Autowired PaymentService paymentService;
    @Autowired SeatService seatService;
    @Autowired SeatSeeder seatSeeder;
    @Autowired SeatRepository seatRepository;
    @Autowired EventRepository eventRepository;
    @Autowired QueueService queueService;
    @Autowired QueueAdmissionService admissionService;
    @Autowired OrderRepository orderRepository;
    @Autowired OrderItemRepository orderItemRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired SeatHoldRepository holdRepository;
    @Autowired SeatHoldItemRepository holdItemRepository;
    @Autowired EventSeatPriceRepository priceRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;

    private Long eventId;

    @BeforeEach
    void seed() {
        paymentRepository.deleteAll();
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        holdItemRepository.deleteAll();
        holdRepository.deleteAll();
        seatRepository.deleteAll();
        priceRepository.deleteAll();
        eventRepository.deleteAll();

        Event e = eventRepository.save(Event.builder()
                .kopisId("PAY1").title("결제 테스트").genre("연극").status(EventStatus.ON_SALE).build());
        eventId = e.getId();
        seatSeeder.seedForEvent(eventId);
    }

    @Test
    void 승인_성공은_주문PAID_좌석SOLD_홀드CONVERTED() {
        long user = 20L;
        Ctx c = order(user, 1);

        PaymentResponse res = paymentService.pay(user, c.orderId, "card", null, "OK-" + c.orderId);

        assertThat(res.paymentStatus()).isEqualTo("APPROVED");
        assertThat(res.orderStatus()).isEqualTo("PAID");
        assertThat(seatRepository.findById(c.seatId).orElseThrow().getStatus()).isEqualTo(SeatStatus.SOLD);
        assertThat(holdRepository.findById(c.holdId).orElseThrow().getStatus()).isEqualTo(SeatHoldStatus.CONVERTED);
    }

    @Test
    void 승인_거절은_결제FAILED_주문은_PENDING_유지() {
        long user = 21L;
        Ctx c = order(user, 1);

        PaymentResponse res = paymentService.pay(user, c.orderId, "card", null, "FAIL-" + c.orderId);

        assertThat(res.paymentStatus()).isEqualTo("FAILED");
        assertThat(res.orderStatus()).isEqualTo("PENDING"); // 재시도 가능
        assertThat(seatRepository.findById(c.seatId).orElseThrow().getStatus()).isEqualTo(SeatStatus.HELD);
    }

    @Test
    void 같은_멱등키_재결제는_한번만_처리된다() {
        long user = 22L;
        Ctx c = order(user, 1);
        String key = "OK-idem-" + c.orderId;

        Long p1 = paymentService.pay(user, c.orderId, "card", null, key).paymentId();
        Long p2 = paymentService.pay(user, c.orderId, "card", null, key).paymentId();

        assertThat(p2).isEqualTo(p1); // 같은 결제 재사용
        assertThat(paymentRepository.count()).isEqualTo(1);
    }

    @Test
    void 이미_PAID_주문은_재결제할_수_없다() {
        long user = 23L;
        Ctx c = order(user, 1);
        paymentService.pay(user, c.orderId, "card", null, "OK-" + c.orderId);

        assertThatThrownBy(() -> paymentService.pay(user, c.orderId, "card", null, "OK2-" + c.orderId))
                .isInstanceOf(BusinessException.class); // INVALID_STATE_TRANSITION
    }

    @Test
    void 결제창_확정은_주문PAID_좌석SOLD() {
        long user = 24L;
        Ctx c = order(user, 1);

        PaymentResponse res = paymentService.confirm(user, c.orderId, "OK-KEY-" + c.orderId);

        assertThat(res.paymentStatus()).isEqualTo("APPROVED");
        assertThat(res.orderStatus()).isEqualTo("PAID");
        assertThat(seatRepository.findById(c.seatId).orElseThrow().getStatus()).isEqualTo(SeatStatus.SOLD);
    }

    @Test
    void 결제창_확정_같은_paymentKey는_한번만_처리된다() {
        long user = 25L;
        Ctx c = order(user, 1);
        String key = "OK-KEY-idem-" + c.orderId;

        Long p1 = paymentService.confirm(user, c.orderId, key).paymentId();
        Long p2 = paymentService.confirm(user, c.orderId, key).paymentId();

        assertThat(p2).isEqualTo(p1);
        assertThat(paymentRepository.count()).isEqualTo(1);
    }

    // --- helpers ---

    @Test
    void 만료sweep이_먼저_이기면_결제는_실패하고_주문은_PAID로_남지_않는다() {
        // TS-011 반대 방향: 만료 sweep이 먼저 승리(좌석 AVAILABLE·홀드 EXPIRED)한 상황을 재현.
        // order는 아직 PENDING·expiresAt 미래(결제 진입 검사 통과)라, 결제가 markPaid엔 성공할 수 있다.
        Ctx c = order(60L, 1);
        jdbc.update("update seats set status='AVAILABLE' where id=?", c.seatId());
        jdbc.update("update seat_holds set status='EXPIRED' where id=?", c.holdId());

        // finalizePaid: markPaid는 성공하나 sellSeats/convertHold가 0행 → 영향행수 검증 실패 → 트랜잭션 롤백
        assertThatThrownBy(() -> paymentService.pay(60L, c.orderId(), "card", null, "OK-" + c.orderId()))
                .isInstanceOf(BusinessException.class);

        // 롤백으로 주문은 PAID로 확정되지 않음(PENDING 유지) — "PAID인데 좌석 없음" 방지
        assertThat(orderRepository.findById(c.orderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(seatRepository.findById(c.seatId()).orElseThrow().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(holdRepository.findById(c.holdId()).orElseThrow().getStatus()).isEqualTo(SeatHoldStatus.EXPIRED);
    }

    @Test
    void IMP010_결제_만료_양방향_레이스_before_after() {
        // IMP-010: 결제 확정 × 만료 sweep의 양방향 경쟁을 trials회 재현해 "불일치 수"를 before/after로 측정.
        // before = 무가드 sweep(정방향) + 영향행수 미검사 결제(반대방향), after = 조건부 가드 + 영향행수 검증.
        int trials = 10;
        int before = 0, after = 0;
        TransactionTemplate tx = new TransactionTemplate(txManager);

        for (int i = 0; i < trials; i++) {
            // ── 정방향: 결제 승리 후 만료 sweep ──
            // before: 무가드 release(구버전)가 SOLD 좌석을 AVAILABLE로 되돌림 → 결제 좌석 유실
            Long fb = takeHeldSeat();
            jdbc.update("update seats set status='SOLD' where id=?", fb);         // 결제 승리
            jdbc.update("update seats set status='AVAILABLE' where id=?", fb);    // 무가드 release
            if ("AVAILABLE".equals(seatStat(fb))) before++;
            // after: 조건부 releaseSeats(from=HELD) → SOLD는 0행 → 유지
            Long fa = takeHeldSeat();
            jdbc.update("update seats set status='SOLD' where id=?", fa);
            tx.executeWithoutResult(s ->
                    seatRepository.releaseSeats(List.of(fa), SeatStatus.AVAILABLE, SeatStatus.HELD));
            if ("AVAILABLE".equals(seatStat(fa))) after++;

            // ── 반대방향: 만료 sweep 승리 후 결제 ──
            // before: 좌석이 이미 풀렸는데 markPaid만 성공(영향행수 미검사) → 주문 PAID·좌석 AVAILABLE
            Ctx rb = order(3000 + i, 1);
            jdbc.update("update seats set status='AVAILABLE' where id=?", rb.seatId());
            jdbc.update("update seat_holds set status='EXPIRED' where id=?", rb.holdId());
            jdbc.update("update orders set status='PAID' where id=? and status='PENDING'", rb.orderId());
            if ("PAID".equals(orderStat(rb.orderId())) && !"SOLD".equals(seatStat(rb.seatId()))) before++;
            // after: 실제 결제 → finalizePaid 영향행수 검증 → 예외 → 롤백 → 주문 PAID 아님
            long raUser = 4000L + i;
            Ctx ra = order(raUser, 1);
            jdbc.update("update seats set status='AVAILABLE' where id=?", ra.seatId());
            jdbc.update("update seat_holds set status='EXPIRED' where id=?", ra.holdId());
            try {
                paymentService.pay(raUser, ra.orderId(), "card", null, "OK-" + ra.orderId());
            } catch (RuntimeException ignore) { /* 영향행수 불일치 → 롤백 예외 */ }
            if ("PAID".equals(orderStat(ra.orderId())) && !"SOLD".equals(seatStat(ra.seatId()))) after++;
        }

        // before: 정방향+반대방향 각 trial 1건씩 = 2*trials 불일치 / after: 0
        assertThat(before).isEqualTo(2 * trials);
        assertThat(after).isZero();
    }

    private Long takeHeldSeat() {
        Long id = seatRepository.findByEventId(eventId).stream()
                .filter(s -> s.getStatus() == SeatStatus.AVAILABLE).map(Seat::getId).findFirst().orElseThrow();
        jdbc.update("update seats set status='HELD' where id=?", id);
        return id;
    }

    private String seatStat(Long id) {
        return jdbc.queryForObject("select status from seats where id=?", String.class, id);
    }

    private String orderStat(Long id) {
        return jdbc.queryForObject("select status from orders where id=?", String.class, id);
    }

    private record Ctx(Long orderId, Long holdId, Long seatId) {}

    private Ctx order(long userId, int count) {
        String token = queueService.issue(userId, eventId).token();
        admissionService.admit(eventId);
        List<Long> ids = seatRepository.findByEventId(eventId).stream()
                .filter(s -> s.getStatus() == SeatStatus.AVAILABLE)
                .map(Seat::getId).limit(count).toList();
        Long holdId = seatService.hold(userId, eventId, ids, token).holdId();
        Long orderId = orderService.create(userId, holdId).orderId();
        return new Ctx(orderId, holdId, ids.get(0));
    }
}
