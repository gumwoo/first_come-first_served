package com.flowticket.order.service;

import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import com.flowticket.order.domain.Order;
import com.flowticket.order.domain.OrderItem;
import com.flowticket.order.domain.OrderStatus;
import com.flowticket.order.dto.OrderResponse;
import com.flowticket.order.dto.OrderResponse.OrderItemResponse;
import com.flowticket.order.repository.OrderItemRepository;
import com.flowticket.order.repository.OrderRepository;
import com.flowticket.seat.domain.EventSeatPrice;
import com.flowticket.seat.domain.Seat;
import com.flowticket.seat.domain.SeatGrade;
import com.flowticket.seat.domain.SeatHold;
import com.flowticket.seat.domain.SeatHoldItem;
import com.flowticket.seat.domain.SeatHoldStatus;
import com.flowticket.seat.repository.EventSeatPriceRepository;
import com.flowticket.seat.repository.SeatHoldItemRepository;
import com.flowticket.seat.repository.SeatHoldRepository;
import com.flowticket.seat.repository.SeatRepository;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 주문 생성/조회. 좌석 선점(hold)을 검증해 주문(PENDING) + 가격 스냅샷으로 승격. */
@Service
@Transactional(readOnly = true)
public class OrderService {

    private static final List<OrderStatus> ACTIVE = List.of(OrderStatus.PENDING, OrderStatus.VBANK_WAITING);

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final SeatHoldRepository holdRepository;
    private final SeatHoldItemRepository holdItemRepository;
    private final SeatRepository seatRepository;
    private final EventSeatPriceRepository priceRepository;

    public OrderService(OrderRepository orderRepository, OrderItemRepository orderItemRepository,
                        SeatHoldRepository holdRepository, SeatHoldItemRepository holdItemRepository,
                        SeatRepository seatRepository, EventSeatPriceRepository priceRepository) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.holdRepository = holdRepository;
        this.holdItemRepository = holdItemRepository;
        this.seatRepository = seatRepository;
        this.priceRepository = priceRepository;
    }

    /** 주문 생성: hold 검증(HELD·소유자·미만료) → 가격 스냅샷 → order(PENDING). 같은 hold는 멱등. */
    @Transactional
    public OrderResponse create(Long userId, Long holdId) {
        if (holdId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        SeatHold hold = holdRepository.findById(holdId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!hold.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (hold.getStatus() != SeatHoldStatus.HELD) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION);
        }
        if (hold.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.HOLD_EXPIRED);
        }

        // 멱등: 같은 hold로 이미 활성 주문이 있으면 그대로 반환(더블 POST 방어)
        return orderRepository.findFirstByHoldIdAndStatusIn(holdId, ACTIVE)
                .map(this::toResponse)
                .orElseGet(() -> toResponse(build(userId, hold)));
    }

    @Transactional(readOnly = true)
    public OrderResponse get(Long orderId, Long userId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return toResponse(order);
    }

    private Order build(Long userId, SeatHold hold) {
        List<Long> seatIds = holdItemRepository.findByHoldId(hold.getId()).stream()
                .map(SeatHoldItem::getSeatId).toList();
        Map<SeatGrade, Integer> priceMap = priceMap(hold.getEventId());
        List<Seat> seats = seatRepository.findAllById(seatIds);

        int amount = seats.stream().mapToInt(s -> priceMap.getOrDefault(s.getGrade(), 0)).sum();
        Order order = orderRepository.save(Order.builder()
                .eventId(hold.getEventId()).userId(userId).holdId(hold.getId())
                .amount(amount).expiresAt(hold.getExpiresAt()).build());
        for (Seat s : seats) {
            orderItemRepository.save(OrderItem.builder()
                    .orderId(order.getId()).seatId(s.getId())
                    .grade(s.getGrade()).price(priceMap.getOrDefault(s.getGrade(), 0)).build());
        }
        return order;
    }

    private Map<SeatGrade, Integer> priceMap(Long eventId) {
        Map<SeatGrade, Integer> m = new EnumMap<>(SeatGrade.class);
        for (EventSeatPrice p : priceRepository.findByEventId(eventId)) {
            m.put(p.getGrade(), p.getPrice());
        }
        return m;
    }

    private OrderResponse toResponse(Order o) {
        List<OrderItemResponse> items = orderItemRepository.findByOrderId(o.getId()).stream()
                .map(i -> new OrderItemResponse(i.getSeatId(), i.getGrade().name(), i.getPrice()))
                .toList();
        return new OrderResponse(o.getId(), o.getStatus().name(), o.getAmount(), o.getExpiresAt(), items);
    }
}
