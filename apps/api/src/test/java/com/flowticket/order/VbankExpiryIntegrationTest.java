package com.flowticket.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.order.domain.OrderStatus;
import com.flowticket.order.dto.PaymentResponse;
import com.flowticket.order.repository.OrderItemRepository;
import com.flowticket.order.repository.OrderRepository;
import com.flowticket.order.repository.PaymentRepository;
import com.flowticket.order.service.OrderExpiryService;
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
import com.flowticket.support.SharedContainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 무통장(vbank) 발급/입금확인 + 주문 만료 sweep(BE-3). hold-ttl 2s로 만료 케이스 재현.
 */
@SpringBootTest
class VbankExpiryIntegrationTest {

    static final PostgreSQLContainer<?> postgres = SharedContainers.POSTGRES;
    static final GenericContainer<?> redisContainer = SharedContainers.REDIS;

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
        r.add("seat.hold-ttl", () -> "2");
        r.add("seat.sweep-interval-ms", () -> "3600000");
        r.add("order.sweep-interval-ms", () -> "3600000"); // 워커 자동실행 비활성(수동 호출)
    }

    @Autowired PaymentService paymentService;
    @Autowired OrderExpiryService orderExpiryService;
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
                .kopisId("VB1").title("무통장 테스트").genre("연극").status(EventStatus.ON_SALE).build());
        eventId = e.getId();
        seatSeeder.seedForEvent(eventId);
    }

    @Test
    void 무통장은_가상계좌_발급하고_입금대기로_간다() {
        long user = 30L;
        Ctx c = order(user, 1);

        PaymentResponse res = paymentService.pay(user, c.orderId, "vbank", null, "VB-" + c.orderId);

        assertThat(res.orderStatus()).isEqualTo("VBANK_WAITING");
        assertThat(res.vbankAccount()).isNotBlank();
        assertThat(orderRepository.findById(c.orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.VBANK_WAITING);
    }

    @Test
    void 입금_확인은_주문PAID_좌석SOLD_홀드CONVERTED() {
        long user = 31L;
        Ctx c = order(user, 1);
        paymentService.pay(user, c.orderId, "vbank", null, "VB-" + c.orderId);

        PaymentResponse res = paymentService.confirmVbankDeposit(user, c.orderId);

        assertThat(res.orderStatus()).isEqualTo("PAID");
        assertThat(seatRepository.findById(c.seatId).orElseThrow().getStatus()).isEqualTo(SeatStatus.SOLD);
        assertThat(holdRepository.findById(c.holdId).orElseThrow().getStatus()).isEqualTo(SeatHoldStatus.CONVERTED);
    }

    @Test
    void 제한시간_지난_미완료_주문은_sweep으로_EXPIRED() throws Exception {
        long user = 32L;
        Ctx c = order(user, 1); // expires_at = now + hold-ttl(2s)

        Thread.sleep(2500);
        orderExpiryService.sweepExpired();

        assertThat(orderRepository.findById(c.orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.EXPIRED);
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
