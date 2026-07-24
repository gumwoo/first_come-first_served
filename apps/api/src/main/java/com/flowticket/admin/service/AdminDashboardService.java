package com.flowticket.admin.service;

import com.flowticket.admin.dto.AdminDashboardResponse;
import com.flowticket.dlq.domain.DlqStatus;
import com.flowticket.dlq.repository.DlqMessageRepository;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.global.kafka.KafkaHealthService;
import com.flowticket.order.domain.OrderStatus;
import com.flowticket.order.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 운영 대시보드 집계(S07). 이벤트·결제·매출 + Kafka 연결 상태(4a) + DLQ 적체(4c). */
@Service
public class AdminDashboardService {

    private final EventRepository eventRepository;
    private final OrderRepository orderRepository;
    private final KafkaHealthService kafkaHealth;
    private final DlqMessageRepository dlqRepository;

    public AdminDashboardService(EventRepository eventRepository, OrderRepository orderRepository,
                                 KafkaHealthService kafkaHealth, DlqMessageRepository dlqRepository) {
        this.eventRepository = eventRepository;
        this.orderRepository = orderRepository;
        this.kafkaHealth = kafkaHealth;
        this.dlqRepository = dlqRepository;
    }

    @Transactional(readOnly = true)
    public AdminDashboardResponse dashboard() {
        return new AdminDashboardResponse(
                eventRepository.count(),
                orderRepository.countByStatus(OrderStatus.PAID),
                orderRepository.sumPaidRevenue(),
                kafkaHealth.isConnected(),           // 4a: 실 연결 상태
                dlqRepository.countByStatus(DlqStatus.PENDING)); // 4c: 미처리 적체
    }
}
