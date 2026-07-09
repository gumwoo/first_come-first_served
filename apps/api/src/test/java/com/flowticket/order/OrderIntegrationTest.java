package com.flowticket.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.global.error.BusinessException;
import com.flowticket.order.dto.OrderResponse;
import com.flowticket.order.repository.OrderItemRepository;
import com.flowticket.order.repository.OrderRepository;
import com.flowticket.order.service.OrderService;
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
 * 주문 생성(BE-1): hold→order 승격 + 가격 스냅샷 + 멱등 + 소유자/만료 검증.
 * capacity 높게·워커 비활성으로 결정적, hold-ttl 1s로 만료 케이스 재현.
 */
@SpringBootTest
@Testcontainers
class OrderIntegrationTest {

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
        r.add("seat.hold-ttl", () -> "1");
        r.add("seat.sweep-interval-ms", () -> "3600000");
    }

    @Autowired OrderService orderService;
    @Autowired SeatService seatService;
    @Autowired SeatSeeder seatSeeder;
    @Autowired SeatRepository seatRepository;
    @Autowired EventRepository eventRepository;
    @Autowired QueueService queueService;
    @Autowired QueueAdmissionService admissionService;
    @Autowired OrderRepository orderRepository;
    @Autowired OrderItemRepository orderItemRepository;
    @Autowired SeatHoldRepository holdRepository;
    @Autowired SeatHoldItemRepository holdItemRepository;
    @Autowired EventSeatPriceRepository priceRepository;

    private Long eventId;

    @BeforeEach
    void seed() {
        // 테스트 격리 — FK 순서대로 정리(order_items→orders→hold_items→holds→seats→prices→events)
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        holdItemRepository.deleteAll();
        holdRepository.deleteAll();
        seatRepository.deleteAll();
        priceRepository.deleteAll();
        eventRepository.deleteAll();

        Event e = eventRepository.save(Event.builder()
                .kopisId("ORD1").title("주문 테스트").genre("연극").status(EventStatus.ON_SALE).build());
        eventId = e.getId();
        seatSeeder.seedForEvent(eventId);
    }

    @Test
    void 주문_생성은_hold를_승격하고_가격을_스냅샷한다() {
        long user = 10L;
        Long holdId = holdSeats(user, 2);

        OrderResponse res = orderService.create(user, holdId);

        assertThat(res.status()).isEqualTo("PENDING");
        assertThat(res.items()).hasSize(2);
        assertThat(res.amount()).isPositive();
        assertThat(res.items().stream().mapToInt(OrderResponse.OrderItemResponse::price).sum())
                .isEqualTo(res.amount()); // amount = 라인 가격 합(스냅샷)
    }

    @Test
    void 같은_hold로_재생성하면_동일_주문을_반환한다() {
        long user = 11L;
        Long holdId = holdSeats(user, 1);

        Long o1 = orderService.create(user, holdId).orderId();
        Long o2 = orderService.create(user, holdId).orderId();

        assertThat(o2).isEqualTo(o1); // 멱등(더블 POST 방어)
    }

    @Test
    void 남의_hold로_주문을_만들_수_없다() {
        Long holdId = holdSeats(12L, 1);

        assertThatThrownBy(() -> orderService.create(999L, holdId))
                .isInstanceOf(BusinessException.class); // FORBIDDEN
    }

    @Test
    void 만료된_hold로는_주문을_만들_수_없다() throws Exception {
        long user = 13L;
        Long holdId = holdSeats(user, 1);

        Thread.sleep(1500); // hold-ttl(1s) 경과

        assertThatThrownBy(() -> orderService.create(user, holdId))
                .isInstanceOf(BusinessException.class); // HOLD_EXPIRED
    }

    // --- helpers ---

    private Long holdSeats(long userId, int count) {
        String token = queueService.issue(userId, eventId).token();
        admissionService.admit(eventId);
        List<Long> ids = seatRepository.findByEventId(eventId).stream()
                .filter(s -> s.getStatus() == SeatStatus.AVAILABLE)
                .map(Seat::getId).limit(count).toList();
        return seatService.hold(userId, eventId, ids, token).holdId();
    }
}
