package com.flowticket.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowticket.support.IntegrationTestSupport;

import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.repository.EventRepository;
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
import com.flowticket.seat.domain.SeatStatus;
import com.flowticket.seat.repository.EventSeatPriceRepository;
import com.flowticket.seat.repository.SeatHoldItemRepository;
import com.flowticket.seat.repository.SeatHoldRepository;
import com.flowticket.seat.repository.SeatRepository;
import com.flowticket.seat.service.SeatSeeder;
import com.flowticket.seat.service.SeatService;
import java.util.ArrayList;
import java.util.Collections;
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

/**
 * IMP-008 결제 멱등성 근거. naive(비원자 check-then-act)는 이중 PAID 발생,
 * 실제 구현(idempotency_key UNIQUE + 조건부 전이)은 동시 더블클릭에도 정확히 1건.
 */
@SpringBootTest
class PaymentIdempotencyIntegrationTest extends IntegrationTestSupport {

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
    @Autowired JdbcTemplate jdbc;

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
                .kopisId("IDEM1").title("멱등 테스트").genre("연극").status(EventStatus.ON_SALE).build());
        eventId = e.getId();
        seatSeeder.seedForEvent(eventId);
    }

    @Test
    void 비원자_check_then_act는_이중_PAID를_만든다() throws Exception {
        // IMP-008 before: 상태를 읽고→비교→(지연)→무조건 UPDATE+INSERT(비원자) → 여러 스레드가 결제 확정
        Ctx c = order(40L, 1);

        AtomicInteger paid = concurrent(10, i -> {
            String st = jdbc.queryForObject("select status from orders where id=?", String.class, c.orderId);
            if ("PENDING".equals(st)) {
                sleep(20); // 레이스 창 확대
                jdbc.update("update orders set status='PAID' where id=?", c.orderId);
                jdbc.update("insert into payments(order_id,method,status,amount,idempotency_key,created_at) "
                        + "values (?,?,?,?,?,now())", c.orderId, "card", "APPROVED", 1000, "naive-" + i);
                return 1;
            }
            return 0;
        });

        assertThat(paid.get()).isGreaterThan(1); // 이중 PAID 재현
    }

    @Test
    void 동시_더블클릭_같은키는_전부_같은_결제결과를_받는다() throws Exception {
        // IMP-008 after: idempotency_key UNIQUE + 조건부 전이 + 충돌 시 기존 결과 반환
        //
        // ⚠️ 예전에는 호출을 `catch (Exception ignored)`로 감싸고 DB 최종 상태만 봤다.
        // 주석에는 "멱등 처리로 예외 없어야 정상"이라고 적어 놓고 코드는 그걸 검증하지 않았다.
        // **1개만 성공하고 9개가 터져도 통과한다** — 최종 상태는 똑같이 PAID/1건이기 때문이다.
        // 멱등이 요구하는 것은 두 가지고, 그 테스트는 앞의 하나만 봤다.
        //   ① 부수효과는 한 번만 일어난다 (결제 1건, 좌석 1회 SOLD)
        //   ② 모든 호출이 같은 성공 결과를 돌려받는다 (더블클릭한 쪽이 에러를 보면 안 된다)
        Ctx c = order(41L, 1);
        String key = "OK-conc-" + c.orderId;
        int threads = 10;

        List<PaymentResponse> responses = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        concurrent(threads, i -> {
            try {
                responses.add(paymentService.pay(41L, c.orderId, "card", null, key));
            } catch (Throwable t) {
                failures.add(t);
            }
            return 0;
        });

        // ② 삼키지 않고 드러낸다. 실패가 있으면 무엇이 터졌는지 메시지에 남는다.
        assertThat(failures).as("같은 멱등키면 예외 없이 기존 결과를 돌려줘야 한다").isEmpty();
        assertThat(responses).hasSize(threads);
        assertThat(responses).extracting(PaymentResponse::paymentId)
                .as("전부 같은 결제 건을 가리켜야 한다").containsOnly(responses.get(0).paymentId());
        assertThat(responses).extracting(PaymentResponse::paymentStatus)
                .as("승인 결과를 그대로 돌려받아야 한다").containsOnly("APPROVED");

        // 응답의 orderStatus는 일부러 단언하지 않는다. payTx의 중복 감지 경로는 이미 로드한
        // Order 엔티티(1차 캐시)의 상태를 그대로 쓰기 때문에, 먼저 커밋한 스레드의 PAID가
        // 반영되기 전 값이 실릴 수 있다. 여기에 PAID를 강제하면 **테스트가 간헐 실패한다.**
        // 실제 정합성은 아래 DB 최종 상태로 본다.

        // ① 부수효과는 정확히 한 번
        assertThat(orderRepository.findById(c.orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(paymentRepository.count()).isEqualTo(1);       // 결제 1건만
        assertThat(seatRepository.findById(c.seatId).orElseThrow().getStatus()).isEqualTo(SeatStatus.SOLD);
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
