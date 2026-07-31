package com.flowticket.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.order.domain.Order;
import com.flowticket.order.domain.OrderStatus;
import com.flowticket.order.gateway.PaymentGateway;
import com.flowticket.order.repository.OrderRepository;
import com.flowticket.order.service.PaymentReconciliationService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * S08 2단계(ADR-011): PG 승인은 났는데 트랜잭션이 롤백돼 <b>DB에 흔적이 없는</b> 미아 승인을
 * 정산 잡이 찾아 취소하는지 검증. 아웃박스로는 닫을 수 없는 클래스(닫을 행 자체가 없음).
 */
@SpringBootTest
@Testcontainers
class PaymentReconciliationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");
    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("jwt.secret", () -> "integration-test-secret-0123456789-0123456789-0123456789");
        r.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        r.add("queue.admit-interval-ms", () -> "3600000");
        r.add("seat.sweep-interval-ms", () -> "3600000");
        r.add("order.sweep-interval-ms", () -> "3600000");
        r.add("outbox.relay-interval-ms", () -> "3600000");
        r.add("payment.reconcile-interval-ms", () -> "3600000"); // 정산 스케줄 비활성 → 직접 호출
    }

    @Autowired PaymentReconciliationService reconciliation;
    @Autowired OrderRepository orderRepository;
    @Autowired EventRepository eventRepository;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @SpyBean PaymentGateway gateway;

    private Long eventId;

    @BeforeEach
    void seed() {
        orderRepository.deleteAll();
        eventRepository.deleteAll();
        Event e = eventRepository.save(Event.builder()
                .kopisId("RECON1").title("정산 테스트").genre("연극").status(EventStatus.ON_SALE).build());
        eventId = e.getId();
    }

    @Test
    void 미확정_주문에_PG승인이_남아있으면_취소한다() {
        // 결제 승인 직후 크래시로 롤백된 상황: 주문은 PENDING으로 남고 payments 행은 없다.
        Long orderId = saveOrder(OrderStatus.PENDING, LocalDateTime.now().minusHours(1));
        doReturn(PaymentGateway.Inquiry.approved("PG-TID-" + orderId))
                .when(gateway).inquire(orderId);

        reconciliation.reconcileOrphanApprovals();

        verify(gateway, times(1)).refund(eq("PG-TID-" + orderId), eq(10000));
    }

    @Test
    void PG에_승인이_없으면_아무것도_취소하지_않는다() {
        // 평시(사용자가 결제하지 않고 만료) — Mock은 Inquiry.none()을 반환한다.
        saveOrder(OrderStatus.EXPIRED, LocalDateTime.now().minusHours(1));

        reconciliation.reconcileOrphanApprovals();

        verify(gateway, never()).refund(anyString(), anyInt());
    }

    @Test
    void 결제완료_주문은_정산_후보가_아니다() {
        // PAID는 승인이 남아 있는 게 정상 — 조회조차 하지 않아야 한다(정상 결제 오취소 방지).
        Long paid = saveOrder(OrderStatus.PAID, LocalDateTime.now().minusHours(1));

        reconciliation.reconcileOrphanApprovals();

        verify(gateway, never()).inquire(paid);
        verify(gateway, never()).refund(anyString(), anyInt());
    }

    @Test
    void 유예시간_안의_최근_주문은_건드리지_않는다() {
        // 아직 결제가 진행 중일 수 있는 구간(기본 유예 10분) — 조회 대상에서 제외.
        Long recent = saveOrder(OrderStatus.PENDING, LocalDateTime.now().minusMinutes(1));

        reconciliation.reconcileOrphanApprovals();

        verify(gateway, never()).inquire(recent);
    }

    /** 주문 빌더는 항상 PENDING으로 생성하므로, 다른 상태가 필요하면 직접 갱신한다. */
    private Long saveOrder(OrderStatus status, LocalDateTime expiresAt) {
        Order order = orderRepository.save(Order.builder()
                .eventId(eventId).userId(900L).holdId(1L).amount(10000)
                .expiresAt(expiresAt).build());
        assertThat(order.getId()).isNotNull();
        if (status != OrderStatus.PENDING) {
            jdbc.update("update orders set status = ? where id = ?", status.name(), order.getId());
        }
        return order.getId();
    }
}
