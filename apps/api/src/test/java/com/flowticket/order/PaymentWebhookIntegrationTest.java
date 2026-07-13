package com.flowticket.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.global.error.BusinessException;
import com.flowticket.order.domain.OrderStatus;
import com.flowticket.order.domain.PaymentStatus;
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
 * 가상계좌 입금 웹훅(Toss DEPOSIT_CALLBACK, BE-5). Mock 게이트웨이로 결정론 검증.
 * secret 대조(위조 거부), status=DONE에서만 확정, 재전송 멱등(이미 PAID면 no-op)을 확인한다.
 */
@SpringBootTest
@Testcontainers
class PaymentWebhookIntegrationTest {

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
                .kopisId("WH1").title("웹훅 테스트").genre("연극").status(EventStatus.ON_SALE).build());
        eventId = e.getId();
        seatSeeder.seedForEvent(eventId);
    }

    @Test
    void 정상_입금웹훅은_주문PAID_좌석SOLD_홀드CONVERTED() {
        long user = 40L;
        Ctx c = order(user, 1);
        String secret = issueVbank(user, c.orderId);

        paymentService.handleVbankDepositWebhook(toss(c.orderId), "DONE", secret);

        assertThat(orderRepository.findById(c.orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(seatRepository.findById(c.seatId).orElseThrow().getStatus()).isEqualTo(SeatStatus.SOLD);
        assertThat(holdRepository.findById(c.holdId).orElseThrow().getStatus()).isEqualTo(SeatHoldStatus.CONVERTED);
    }

    @Test
    void 위조_secret_웹훅은_거부되고_주문은_VBANK_WAITING_유지() {
        long user = 41L;
        Ctx c = order(user, 1);
        issueVbank(user, c.orderId);

        assertThatThrownBy(() -> paymentService.handleVbankDepositWebhook(toss(c.orderId), "DONE", "FORGED-SECRET"))
                .isInstanceOf(BusinessException.class); // FORBIDDEN

        assertThat(orderRepository.findById(c.orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.VBANK_WAITING);
    }

    @Test
    void 재전송_웹훅은_멱등_한번만_PAID_처리() {
        long user = 42L;
        Ctx c = order(user, 1);
        String secret = issueVbank(user, c.orderId);

        paymentService.handleVbankDepositWebhook(toss(c.orderId), "DONE", secret);
        paymentService.handleVbankDepositWebhook(toss(c.orderId), "DONE", secret); // Toss 재전송 시뮬레이션

        assertThat(orderRepository.findById(c.orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(paymentRepository.findAll().stream()
                .filter(p -> p.getStatus() == PaymentStatus.APPROVED).count()).isEqualTo(1);
    }

    @Test
    void 입금완료_아닌_status는_대기_유지() {
        long user = 43L;
        Ctx c = order(user, 1);
        String secret = issueVbank(user, c.orderId);

        paymentService.handleVbankDepositWebhook(toss(c.orderId), "WAITING_FOR_DEPOSIT", secret);

        assertThat(orderRepository.findById(c.orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.VBANK_WAITING);
    }

    // --- helpers ---

    private record Ctx(Long orderId, Long holdId, Long seatId) {}

    private String toss(Long orderId) {
        return "FLOWTICKET-ORDER-" + orderId;
    }

    /** 가상계좌 발급 후 저장된 secret 반환(웹훅 대조용). */
    private String issueVbank(long userId, Long orderId) {
        paymentService.pay(userId, orderId, "vbank", null, "VB-" + orderId);
        return paymentRepository
                .findFirstByOrderIdAndStatusOrderByIdDesc(orderId, PaymentStatus.READY)
                .orElseThrow().getVbankSecret();
    }

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
