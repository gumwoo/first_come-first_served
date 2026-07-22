package com.flowticket.admin.service;

import com.flowticket.admin.dto.AdminDashboardResponse;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.order.domain.OrderStatus;
import com.flowticket.order.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 운영 대시보드 집계(S07). 현재는 이벤트 수·결제 완료 수·매출 골격. Kafka 지표는 Phase 4. */
@Service
public class AdminDashboardService {

    private final EventRepository eventRepository;
    private final OrderRepository orderRepository;

    public AdminDashboardService(EventRepository eventRepository, OrderRepository orderRepository) {
        this.eventRepository = eventRepository;
        this.orderRepository = orderRepository;
    }

    @Transactional(readOnly = true)
    public AdminDashboardResponse dashboard() {
        return new AdminDashboardResponse(
                eventRepository.count(),
                orderRepository.countByStatus(OrderStatus.PAID),
                orderRepository.sumPaidRevenue(),
                false); // Kafka 미구현 — Phase 4에서 연결
    }
}
