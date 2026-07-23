package com.flowticket.admin.service;

import com.flowticket.admin.dto.AdminOrderSummary;
import com.flowticket.auth.domain.User;
import com.flowticket.auth.repository.UserRepository;
import com.flowticket.event.domain.Event;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.global.common.PageResponse;
import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import com.flowticket.order.domain.Order;
import com.flowticket.order.domain.OrderStatus;
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
import org.springframework.util.StringUtils;

/**
 * 운영 주문 조회(S07). 마이페이지와 달리 <b>전 사용자</b> 주문을 대상으로 하며, 상태 필터 + 페이징.
 * 주문자(user)·공연(event) 정보는 배치 조회로 N+1을 피한다(MyOrderService와 동일 패턴).
 */
@Service
public class AdminOrderService {

    private final OrderRepository orderRepository;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;

    public AdminOrderService(OrderRepository orderRepository, EventRepository eventRepository,
                             UserRepository userRepository) {
        this.orderRepository = orderRepository;
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminOrderSummary> list(String status, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Order> orders = StringUtils.hasText(status)
                ? orderRepository.findByStatusOrderByIdDesc(parseStatus(status), pageable)
                : orderRepository.findAllByOrderByIdDesc(pageable);

        List<Order> content = orders.getContent();
        Map<Long, Event> eventById = eventRepository
                .findAllById(content.stream().map(Order::getEventId).distinct().toList())
                .stream().collect(Collectors.toMap(Event::getId, Function.identity()));
        Map<Long, User> userById = userRepository
                .findAllById(content.stream().map(Order::getUserId).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));

        return PageResponse.from(orders.map(o -> {
            Event e = eventById.get(o.getEventId());
            User u = userById.get(o.getUserId());
            return new AdminOrderSummary(
                    o.getId(), o.getEventId(),
                    e != null ? e.getTitle() : null,
                    o.getUserId(),
                    u != null ? u.getEmail() : null,
                    o.getStatus().name(), o.getAmount(),
                    o.getCreatedAt(), o.getPaidAt());
        }));
    }

    private OrderStatus parseStatus(String status) {
        try {
            return OrderStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }
}
