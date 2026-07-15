package com.flowticket.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.order.domain.OrderStatus;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.flowticket.support.SharedContainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * IMP-009 환불 멱등성 근거. naive(비원자 check-then-act)는 이중 환불·이중 좌석복구를 만든다.
 * 실제 구현(refunds.idempotency_key UNIQUE + 조건부 전이 PAID→CANCELLED→REFUNDED)은
 * 동시 더블클릭에도 정확히 환불 1건. (결제 IMP-008과 같은 결.)
 */
@SpringBootTest
class RefundIdempotencyIntegrationTest {

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
    @Autowired JdbcTemplate jdbc;

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
    void 비원자_check_then_act는_이중_환불을_만든다() throws Exception {
        // IMP-009 before: 상태 읽고→PAID면→(지연)→무조건 UPDATE REFUNDED + INSERT refund + 좌석복구(비원자)
        Ctx c = paidOrder(70L);

        AtomicInteger refunded = concurrent(10, i -> {
            String st = jdbc.queryForObject("select status from orders where id=?", String.class, c.orderId);
            if ("PAID".equals(st)) {
                sleep(20); // 레이스 창 확대
                jdbc.update("update orders set status='REFUNDED' where id=?", c.orderId);
                jdbc.update("insert into refunds(order_id,amount,fee,idempotency_key,created_at) "
                        + "values (?,?,?,?,now())", c.orderId, c.amount, 0, "naive-" + i);
                jdbc.update("update seats set status='AVAILABLE' where id=?", c.seatId);
                return 1;
            }
            return 0;
        });

        assertThat(refunded.get()).isGreaterThan(1);          // 이중 환불 재현
        assertThat(refundRepository.count()).isGreaterThan(1); // 환불행도 다수
    }

    @Test
    void 동시_더블클릭_같은키는_정확히_한번만_환불된다() throws Exception {
        // IMP-009 after: idempotency_key UNIQUE + 조건부 전이 + 충돌 시 기존 결과 반환
        Ctx c = paidOrder(71L);
        String key = "R-conc-" + c.orderId;

        concurrent(10, i -> {
            try {
                refundService.refund(71L, c.orderId, "변심", key);
            } catch (Exception ignored) {
                // 멱등 처리로 예외 없어야 정상
            }
            return 0;
        });

        assertThat(orderRepository.findById(c.orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(refundRepository.count()).isEqualTo(1);      // 환불 1건만
        assertThat(seatRepository.findById(c.seatId).orElseThrow().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
    }

    // --- helpers ---

    private record Ctx(Long orderId, Long seatId, int amount) {}

    private Ctx paidOrder(long userId) {
        Event e = eventRepository.save(Event.builder()
                .kopisId("RIDEM" + userId).title("환불멱등 테스트").genre("연극")
                .status(EventStatus.ON_SALE).startDate(LocalDate.now().plusDays(30)).build());
        Long eventId = e.getId();
        seatSeeder.seedForEvent(eventId);

        String token = queueService.issue(userId, eventId).token();
        admissionService.admit(eventId);
        List<Long> ids = seatRepository.findByEventId(eventId).stream()
                .filter(s -> s.getStatus() == SeatStatus.AVAILABLE)
                .map(Seat::getId).limit(1).toList();
        Long holdId = seatService.hold(userId, eventId, ids, token).holdId();
        Long orderId = orderService.create(userId, holdId).orderId();
        int amount = orderRepository.findById(orderId).orElseThrow().getAmount();
        paymentService.pay(userId, orderId, "card", null, "OK-" + orderId);
        return new Ctx(orderId, ids.get(0), amount);
    }

    private AtomicInteger concurrent(int threads, IntUnaryOperator op) throws Exception {
        AtomicInteger success = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    if (op.applyAsInt(idx) == 1) {
                        success.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
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
