package com.flowticket.admin.service;

import com.flowticket.admin.dto.AdminDashboardResponse;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.order.domain.OrderStatus;
import com.flowticket.order.repository.OrderRepository;
import com.flowticket.outbox.domain.OutboxStatus;
import com.flowticket.outbox.repository.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 운영 대시보드 집계. 이벤트·결제·매출 + 아웃박스 미발행·격리(ADR-028). */
@Service
public class AdminDashboardService {

    private final EventRepository eventRepository;
    private final OrderRepository orderRepository;
    private final OutboxEventRepository outboxRepository;

    public AdminDashboardService(EventRepository eventRepository, OrderRepository orderRepository,
                                 OutboxEventRepository outboxRepository) {
        this.eventRepository = eventRepository;
        this.orderRepository = orderRepository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional(readOnly = true)
    public AdminDashboardResponse dashboard() {
        return new AdminDashboardResponse(
                eventRepository.count(),
                orderRepository.countByStatus(OrderStatus.PAID),
                orderRepository.sumPaidRevenue(),
                outboxRepository.countByStatus(OutboxStatus.PENDING),
                outboxRepository.countByStatus(OutboxStatus.DEAD));
    }
}
