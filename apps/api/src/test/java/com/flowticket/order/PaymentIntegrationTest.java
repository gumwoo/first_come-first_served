package com.flowticket.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.global.error.BusinessException;
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
