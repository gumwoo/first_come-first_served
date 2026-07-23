package com.flowticket.admin.service;

import com.flowticket.admin.dto.AdminDashboardResponse;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.global.kafka.KafkaHealthService;
import com.flowticket.order.domain.OrderStatus;
import com.flowticket.order.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 운영 대시보드 집계(S07). 이벤트 수·결제 완료 수·매출 + Kafka 연결 상태(Phase 4). */
@Service
public class AdminDashboardService {

    private final EventRepository eventRepository;
    private final OrderRepository orderRepository;
    private final KafkaHealthService kafkaHealth;

    public AdminDashboardService(EventRepository eventRepository, OrderRepository orderRepository,
                                 KafkaHealthService kafkaHealth) {
        this.eventRepository = eventRepository;
        this.orderRepository = orderRepository;
        this.kafkaHealth = kafkaHealth;
    }

    @Transactional(readOnly = true)
    public AdminDashboardResponse dashboard() {
        return new AdminDashboardResponse(
                eventRepository.count(),
                orderRepository.countByStatus(OrderStatus.PAID),
                orderRepository.sumPaidRevenue(),
                kafkaHealth.isConnected()); // Phase 4: 실 연결 상태
    }
}
