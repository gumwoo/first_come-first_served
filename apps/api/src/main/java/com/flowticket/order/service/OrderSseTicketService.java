package com.flowticket.order.service;

import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import com.flowticket.global.security.JwtProvider;
import com.flowticket.order.domain.Order;
import com.flowticket.order.repository.OrderRepository;
import io.jsonwebtoken.Claims;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 SSE 구독 자격: 발급(소유자 확인)과 검증(티켓 대조).
 *
 * EventSource는 헤더를 못 붙이므로 전용 타입·주문 하나·짧은 TTL의 티켓을 URL로 싣는다.
 * TTL은 새 구독을 시작할 수 있는 창이지 스트림 수명이 아니다(ADR-017).
 */
@Service
public class OrderSseTicketService {

    private final OrderRepository orderRepository;
    private final JwtProvider jwtProvider;

    public OrderSseTicketService(OrderRepository orderRepository, JwtProvider jwtProvider) {
        this.orderRepository = orderRepository;
        this.jwtProvider = jwtProvider;
    }

    /** 소유자에게만 발급한다. 남의 주문이면 여기서 끊긴다. */
    @Transactional(readOnly = true)
    public String issue(Long userId, Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return jwtProvider.createSseTicket(userId, orderId);
    }

    /**
     * 구독 직전 검증. 무효·만료 티켓은 401, 다른 주문의 티켓은 403.
     *
     * 둘 다 2xx가 아니어야 EventSource가 CLOSED가 되고, 프론트가 새 티켓을 받아 다시 연다.
     * 티켓의 주문과 경로의 주문을 대조하지 않으면 자기 티켓으로 남의 주문을 구독할 수 있다(ADR-017).
     */
    public void verify(String ticket, Long orderId) {
        if (ticket == null || !jwtProvider.isValid(ticket, JwtProvider.TYPE_SSE)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        Claims claims = jwtProvider.parse(ticket);
        Long ticketOrderId = claims.get("orderId", Number.class) == null
                ? null
                : claims.get("orderId", Number.class).longValue();
        if (!orderId.equals(ticketOrderId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}
