package com.flowticket.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.global.common.PageResponse;
import com.flowticket.global.error.BusinessException;
import com.flowticket.order.dto.MyOrderDetail;
import com.flowticket.order.dto.MyOrderSummary;
import com.flowticket.order.repository.OrderItemRepository;
import com.flowticket.order.repository.OrderRepository;
import com.flowticket.order.repository.PaymentRepository;
import com.flowticket.order.service.MyOrderService;
import com.flowticket.order.service.OrderService;
import com.flowticket.order.service.PaymentService;
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
 * 마이페이지 예매 조회(S06 BE-1): 본인 주문만·상태 탭·페이징, 상세 소유자 검증(403/404).
 */
@SpringBootTest
@Testcontainers
class MyOrderIntegrationTest {

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

    @Autowired MyOrderService myOrderService;
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
                .kopisId("MY1").title("마이페이지 테스트").genre("연극").status(EventStatus.ON_SALE).build());
        eventId = e.getId();
        seatSeeder.seedForEvent(eventId);
    }

    @Test
    void 목록은_본인_주문만_최신순() {
        long me = 50L, other = 51L;
        order(me);
        order(me);
        order(other); // 남의 주문은 안 보여야 함

        PageResponse<MyOrderSummary> res = myOrderService.list(me, null, 0, 20);

        assertThat(res.total()).isEqualTo(2);
        assertThat(res.items()).allSatisfy(s -> assertThat(s.eventTitle()).isEqualTo("마이페이지 테스트"));
        // 최신순(id 내림차순) — 두 번째로 만든 주문이 먼저
        assertThat(res.items().get(0).orderId()).isGreaterThan(res.items().get(1).orderId());
    }

    @Test
    void 예정탭은_PAID만_노출() {
        long me = 52L;
        Long paidOrder = order(me);
        paymentService.pay(me, paidOrder, "card", null, "OK-" + paidOrder);
        order(me); // PENDING 유지

        PageResponse<MyOrderSummary> upcoming = myOrderService.list(me, "upcoming", 0, 20);

        assertThat(upcoming.total()).isEqualTo(1);
        assertThat(upcoming.items().get(0).orderId()).isEqualTo(paidOrder);
        assertThat(upcoming.items().get(0).status()).isEqualTo("PAID");
    }

    @Test
    void 상세는_소유자만_조회_그외_FORBIDDEN() {
        long me = 53L, other = 54L;
        Long orderId = order(me);

        MyOrderDetail detail = myOrderService.detail(me, orderId);
        assertThat(detail.orderId()).isEqualTo(orderId);
        assertThat(detail.items()).isNotEmpty();

        assertThatThrownBy(() -> myOrderService.detail(other, orderId))
                .isInstanceOf(BusinessException.class); // FORBIDDEN
    }

    @Test
    void 없는_주문_상세는_NOT_FOUND() {
        assertThatThrownBy(() -> myOrderService.detail(55L, 999999L))
                .isInstanceOf(BusinessException.class); // NOT_FOUND
    }

    // --- helpers ---

    /** 주문 1건 생성(좌석 1석 선점 → 주문). 반환 orderId. */
    private Long order(long userId) {
        String token = queueService.issue(userId, eventId).token();
        admissionService.admit(eventId);
        List<Long> ids = seatRepository.findByEventId(eventId).stream()
                .filter(s -> s.getStatus() == SeatStatus.AVAILABLE)
                .map(Seat::getId).limit(1).toList();
        Long holdId = seatService.hold(userId, eventId, ids, token).holdId();
        return orderService.create(userId, holdId).orderId();
    }
}
