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
 * 주문 SSE 구독 자격 — 발급(소유자 확인)과 검증(티켓 대조).
 *
 * <p><b>왜 티켓인가</b>: {@code /sse/orders/{id}}는 인가 없이 열려 있었고 {@code id}는 순차
 * 정수라, 누구나 남의 주문 상태 변화를 관찰할 수 있었다. 그렇다고 Bearer를 요구할 수는 없다 —
 * {@code EventSource}는 요청 헤더를 붙이지 못한다. fetch 기반 스트림으로 바꾸면 헤더는 붙지만
 * 브라우저의 자동 재연결을 잃는데, 이 프로젝트의 복구는 그 재연결 위에 서 있다(ADR-015).
 *
 * <p>그래서 <b>구독 전용 단명 티켓</b>을 URL로 싣는다. 대기열이 이미 같은 모양이다 —
 * {@code /sse/queue/{token}}은 추측 불가능한 값 자체가 자격증명이다(ADR-002).
 *
 * <p>⚠️ <b>URL에 실리는 자격증명이라는 점은 그대로 비용이다.</b> 접근 로그·리퍼러에 남는다.
 * 그래서 (1) access token이 아니라 <b>이 용도로만 쓰이는 타입</b>이고, (2) <b>주문 하나에</b>
 * 묶여 있고, (3) TTL이 짧다. 세 가지가 함께 있어야 노출의 값이 낮아진다.
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
     * 구독 직전 검증. 실패는 모두 401이다.
     *
     * <p><b>401이어야 하는 이유가 있다.</b> {@code EventSource}는 2xx가 아닌 응답을 받으면
     * 재연결을 포기하고 {@code readyState}를 CLOSED로 바꾼다. 프론트는 그 신호를 보고 티켓을
     * 새로 발급받아 다시 연다 — 200으로 답하면서 스트림만 닫으면 브라우저가 만료된 티켓으로
     * 무한히 재시도한다.
     *
     * <p>티켓의 주문과 경로의 주문을 대조하는 것이 요점이다. 대조하지 않으면 자기 주문
     * 티켓 하나로 남의 주문을 구독할 수 있어, 인가를 넣은 의미가 사라진다.
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
