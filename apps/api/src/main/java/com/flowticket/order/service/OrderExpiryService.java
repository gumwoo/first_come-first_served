package com.flowticket.order.service;

import com.flowticket.order.domain.OrderStatus;
import com.flowticket.order.repository.OrderRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 만료 회수. 결제 제한시각(=hold 잔여 TTL) 지난 미완료 주문(PENDING/VBANK_WAITING)을 EXPIRED로.
 * 좌석/hold 복구는 좌석 만료 sweep(SeatHoldExpiryService)이 담당(order.expires_at == hold.expires_at).
 */
@Slf4j
@Service
public class OrderExpiryService {

    private static final List<OrderStatus> ACTIVE =
            List.of(OrderStatus.PENDING, OrderStatus.VBANK_WAITING);

    private final OrderRepository orderRepository;

    public OrderExpiryService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Scheduled(fixedRateString = "${order.sweep-interval-ms:60000}")
    @Transactional
    public void sweepExpired() {
        int n = orderRepository.expireOverdue(ACTIVE, LocalDateTime.now());
        if (n > 0) {
            log.info("[order] 주문 만료 회수 {}건", n);
        }
    }
}
