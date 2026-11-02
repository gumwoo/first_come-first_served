package com.flowticket.order;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.flowticket.support.IntegrationTestSupport;

import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.order.domain.Order;
import com.flowticket.order.gateway.PaymentGateway;
import com.flowticket.order.repository.OrderRepository;
import com.flowticket.order.service.PaymentReconciliationService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 결제 정산이 후보를 끝까지 도는지 검증한다.
 *
 * 만료된 주문은 조회해도 상태가 그대로라 후보에 남는다. 마지막 조회 시각을 남기지 않으면 매 틱 같은
 * 앞쪽 묶음만 보고, 뒤쪽의 실제 미아 승인은 소급 한계 밖으로 밀려날 때까지 조회되지 않는다.
 * 후보 수가 배치보다 많을 때만 드러나므로 배치를 2로 줄여 재현한다.
 */
@SpringBootTest
@TestPropertySource(properties = "payment.reconcile-batch-size=2")
class PaymentReconciliationRotationIntegrationTest extends IntegrationTestSupport {

    @Autowired PaymentReconciliationService reconciliation;
    @Autowired OrderRepository orderRepository;
    @Autowired EventRepository eventRepository;
    @Autowired JdbcTemplate jdbc;
    @SpyBean PaymentGateway gateway;

    private Long eventId;

    @BeforeEach
    void seed() {
        orderRepository.deleteAll();
        eventRepository.deleteAll();
        Event e = eventRepository.save(Event.builder()
                .kopisId("RECONRT").title("정산 순회 테스트").genre("연극").status(EventStatus.ON_SALE).build());
        eventId = e.getId();
    }

    /** 배치 밖에 있는 후보에만 승인이 남아 있다. 앞쪽은 승인 없음을 확인해도 후보에 그대로 남는다. */
    @Test
    void 배치_뒤쪽_후보의_미아_승인도_다음_틱에_취소한다() {
        List<Long> orderIds = expiredOrders(3);
        Long last = orderIds.get(2);
        doReturn(PaymentGateway.Inquiry.approved("PG-TID-" + last)).when(gateway).inquire(last);

        reconciliation.reconcileOrphanApprovals();
        reconciliation.reconcileOrphanApprovals();

        verify(gateway, times(1)).refund(eq("PG-TID-" + last), eq(10000), anyString());
    }

    /** 조회에 실패한 후보도 표시한다. 표시하지 않으면 그 후보가 매 틱 선두로 돌아와 뒤쪽을 다시 굶긴다. */
    @Test
    void PG_조회가_실패해도_뒤쪽_후보로_넘어간다() {
        List<Long> orderIds = expiredOrders(3);
        doThrow(new RuntimeException("pg down")).when(gateway).inquire(anyLong());

        reconciliation.reconcileOrphanApprovals();
        reconciliation.reconcileOrphanApprovals();

        for (Long orderId : orderIds) {
            verify(gateway, times(1)).inquire(orderId);
        }
    }

    @Test
    void 방금_조회한_후보는_재조회_간격_안에_다시_묻지_않는다() {
        Long orderId = expiredOrders(1).get(0);

        reconciliation.reconcileOrphanApprovals();
        reconciliation.reconcileOrphanApprovals();

        verify(gateway, times(1)).inquire(orderId);
    }

    /** 표시는 후보에서 빼는 것이 아니다. 간격이 지나면 다시 물어 뒤늦게 남은 승인도 잡는다. */
    @Test
    void 재조회_간격이_지나면_다시_조회한다() {
        Long orderId = expiredOrders(1).get(0);
        reconciliation.reconcileOrphanApprovals();
        jdbc.update("update orders set reconcile_checked_at = now() - interval '2 hours' where id = ?", orderId);

        reconciliation.reconcileOrphanApprovals();

        verify(gateway, times(2)).inquire(orderId);
    }

    // --- helpers ---

    /** 유예(기본 10분)는 지나고 소급 한계(기본 24시간) 안인 만료 주문. 앞 번호일수록 오래됐다. */
    private List<Long> expiredOrders(int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Order order = orderRepository.save(Order.builder()
                    .eventId(eventId).userId(900L).holdId(1000L + i).amount(10000)
                    .expiresAt(LocalDateTime.now().minusHours(count + 1 - i)).build());
            jdbc.update("update orders set status = 'EXPIRED' where id = ?", order.getId());
            ids.add(order.getId());
        }
        return ids;
    }
}
