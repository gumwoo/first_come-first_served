package com.flowticket.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.global.error.BusinessException;
import com.flowticket.order.domain.OrderStatus;
import com.flowticket.order.dto.RefundResponse;
import com.flowticket.order.repository.OrderItemRepository;
import com.flowticket.order.repository.OrderRepository;
import com.flowticket.order.repository.PaymentRepository;
import com.flowticket.order.repository.RefundRepository;
import com.flowticket.order.service.OrderService;
import com.flowticket.order.service.PaymentService;
import com.flowticket.order.service.RefundService;
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
import java.time.LocalDate;
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
 * 예매 취소·환불(S06 BE-2): PAID+시점 게이트에서만, 좌석 복구, 이중 환불 멱등, 소유자 검증.
 */
@SpringBootTest
@Testcontainers
class RefundIntegrationTest {

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

    @Autowired RefundService refundService;
    @Autowired PaymentService paymentService;
    @Autowired OrderService orderService;
    @Autowired SeatService seatService;
    @Autowired SeatSeeder seatSeeder;
    @Autowired SeatRepository seatRepository;
    @Autowired EventRepository eventRepository;
    @Autowired QueueService queueService;
    @Autowired QueueAdmissionService admissionService;
    @Autowired OrderRepository orderRepository;
    @Autowired OrderItemRepository orderItemRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired RefundRepository refundRepository;
    @Autowired SeatHoldRepository holdRepository;
    @Autowired SeatHoldItemRepository holdItemRepository;
    @Autowired EventSeatPriceRepository priceRepository;

    @BeforeEach
    void clean() {
        refundRepository.deleteAll();
        paymentRepository.deleteAll();
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        holdItemRepository.deleteAll();
        holdRepository.deleteAll();
        seatRepository.deleteAll();
        priceRepository.deleteAll();
        eventRepository.deleteAll();
    }

    @Test
    void 환불은_주문REFUNDED_좌석AVAILABLE_refunds기록() {
        long user = 60L;
        Long eventId = event("RF1", LocalDate.now().plusDays(30)); // D-30 → 수수료 0
        Ctx c = paidOrder(user, eventId);

        RefundResponse res = refundService.refund(user, c.orderId, "단순 변심", "R-" + c.orderId);

        assertThat(res.orderStatus()).isEqualTo("REFUNDED");
        assertThat(res.refundAmount()).isEqualTo(c.amount); // D-30 전액
        assertThat(orderRepository.findById(c.orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(seatRepository.findById(c.seatId).orElseThrow().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(refundRepository.count()).isEqualTo(1);
    }

    @Test
    void 비PAID_주문은_REFUND_NOT_ALLOWED() {
        long user = 61L;
        Long eventId = event("RF2", LocalDate.now().plusDays(30));
        Long orderId = order(user, eventId); // PENDING(미결제)

        assertThatThrownBy(() -> refundService.refund(user, orderId, null, "R-" + orderId))
                .isInstanceOf(BusinessException.class); // REFUND_NOT_ALLOWED
    }

    @Test
    void 같은_멱등키_이중환불은_한번만() {
        long user = 62L;
        Long eventId = event("RF3", LocalDate.now().plusDays(30));
        Ctx c = paidOrder(user, eventId);
        String key = "R-idem-" + c.orderId;

        Long r1 = refundService.refund(user, c.orderId, null, key).refundId();
        Long r2 = refundService.refund(user, c.orderId, null, key).refundId();

        assertThat(r2).isEqualTo(r1);
        assertThat(refundRepository.count()).isEqualTo(1);
        assertThat(orderRepository.findById(c.orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.REFUNDED);
    }

    @Test
    void 소유자_아니면_FORBIDDEN() {
        long user = 63L, other = 64L;
        Long eventId = event("RF4", LocalDate.now().plusDays(30));
        Ctx c = paidOrder(user, eventId);

        assertThatThrownBy(() -> refundService.refund(other, c.orderId, null, "R-" + c.orderId))
                .isInstanceOf(BusinessException.class); // FORBIDDEN
    }

    @Test
    void 공연_당일이후는_PAID여도_환불불가() {
        long user = 65L;
        Long eventId = event("RF5", LocalDate.now()); // 당일 → 환불 불가
        Ctx c = paidOrder(user, eventId);

        assertThatThrownBy(() -> refundService.refund(user, c.orderId, null, "R-" + c.orderId))
                .isInstanceOf(BusinessException.class); // REFUND_NOT_ALLOWED
        assertThat(orderRepository.findById(c.orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
    }

    // --- helpers ---

    private record Ctx(Long orderId, Long seatId, int amount) {}

    private Long event(String kopisId, LocalDate startDate) {
        Event e = eventRepository.save(Event.builder()
                .kopisId(kopisId).title("환불 테스트").genre("연극")
                .status(EventStatus.ON_SALE).startDate(startDate).build());
        seatSeeder.seedForEvent(e.getId());
        return e.getId();
    }

    private Long order(long userId, Long eventId) {
        String token = queueService.issue(userId, eventId).token();
        admissionService.admit(eventId);
        List<Long> ids = seatRepository.findByEventId(eventId).stream()
                .filter(s -> s.getStatus() == SeatStatus.AVAILABLE)
                .map(Seat::getId).limit(1).toList();
        Long holdId = seatService.hold(userId, eventId, ids, token).holdId();
        return orderService.create(userId, holdId).orderId();
    }

    private Ctx paidOrder(long userId, Long eventId) {
        Long orderId = order(userId, eventId);
        Long seatId = orderItemRepository.findByOrderId(orderId).get(0).getSeatId();
        int amount = orderRepository.findById(orderId).orElseThrow().getAmount();
        paymentService.pay(userId, orderId, "card", null, "OK-" + orderId);
        return new Ctx(orderId, seatId, amount);
    }
}
