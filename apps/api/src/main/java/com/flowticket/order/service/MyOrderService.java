package com.flowticket.order.service;

import com.flowticket.event.domain.Event;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.global.common.PageResponse;
import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import com.flowticket.order.domain.Order;
import com.flowticket.order.domain.OrderStatus;
import com.flowticket.order.dto.MyOrderDetail;
import com.flowticket.order.dto.MyOrderSummary;
import com.flowticket.order.dto.OrderResponse.OrderItemResponse;
import com.flowticket.order.repository.OrderItemRepository;
import com.flowticket.order.repository.OrderRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 마이페이지 예매 조회(S06). 본인 주문만 노출(소유자 검증). 목록은 탭(전체/예정/취소) 필터 + 페이징(ADR-001).
 * 공연 정보(제목/포스터/일시)는 events에서 배치 조회해 N+1을 피한다.
 */
@Service
public class MyOrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final EventRepository eventRepository;

    public MyOrderService(OrderRepository orderRepository, OrderItemRepository orderItemRepository,
                          EventRepository eventRepository) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.eventRepository = eventRepository;
    }

    /** tab: null/all=전체, upcoming=예정(PAID), cancelled=취소(CANCELLED·REFUNDED). */
    @Transactional(readOnly = true)
    public PageResponse<MyOrderSummary> list(Long userId, String tab, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Order> orders = switch (tab == null ? "all" : tab) {
            case "upcoming" -> orderRepository.findByUserIdAndStatusInOrderByIdDesc(
                    userId, List.of(OrderStatus.PAID), pageable);
            case "cancelled" -> orderRepository.findByUserIdAndStatusInOrderByIdDesc(
                    userId, List.of(OrderStatus.CANCELLED, OrderStatus.REFUNDED), pageable);
            default -> orderRepository.findByUserIdOrderByIdDesc(userId, pageable);
        };

        List<Order> content = orders.getContent();
        Map<Long, Event> eventById = eventRepository
                .findAllById(content.stream().map(Order::getEventId).distinct().toList())
                .stream().collect(Collectors.toMap(Event::getId, Function.identity()));
        Map<Long, Long> seatCountByOrder = orderItemRepository
                .findByOrderIdIn(content.stream().map(Order::getId).toList())
                .stream().collect(Collectors.groupingBy(i -> i.getOrderId(), Collectors.counting()));

        return PageResponse.from(orders.map(o -> {
            Event e = eventById.get(o.getEventId());
            return new MyOrderSummary(
                    o.getId(), o.getEventId(),
                    e != null ? e.getTitle() : null,
                    e != null ? e.getPosterUrl() : null,
                    e != null ? e.getStartDate() : null,
                    o.getStatus().name(), o.getAmount(),
                    seatCountByOrder.getOrDefault(o.getId(), 0L).intValue(),
                    o.getCreatedAt());
        }));
    }

    @Transactional(readOnly = true)
    public MyOrderDetail detail(Long userId, Long orderId) {
        Order o = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!o.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        Event e = eventRepository.findById(o.getEventId()).orElse(null);
        List<OrderItemResponse> items = orderItemRepository.findByOrderId(orderId).stream()
                .map(i -> new OrderItemResponse(i.getSeatId(), i.getGrade().name(), i.getPrice()))
                .toList();
        return new MyOrderDetail(
                o.getId(), o.getEventId(),
                e != null ? e.getTitle() : null,
                e != null ? e.getPosterUrl() : null,
                e != null ? e.getVenue() : null,
                e != null ? e.getStartDate() : null,
                o.getStatus().name(), o.getAmount(), o.getPaidAt(), items);
    }
}
