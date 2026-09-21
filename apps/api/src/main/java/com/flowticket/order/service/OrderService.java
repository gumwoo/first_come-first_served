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
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
    /** 트랜잭션 경계를 코드로 연다. 커밋에서 나는 제약 위반을 경계 밖에서 잡아야 하기 때문이다. */
    private final TransactionTemplate tx;

    private final Clock clock;

    public OrderService(OrderRepository orderRepository, OrderItemRepository orderItemRepository,
                        SeatHoldRepository holdRepository, SeatHoldItemRepository holdItemRepository,
                        SeatRepository seatRepository, EventSeatPriceRepository priceRepository,
                        TransactionTemplate tx, Clock clock) {
        this.clock = clock;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.holdRepository = holdRepository;
        this.holdItemRepository = holdItemRepository;
        this.seatRepository = seatRepository;
        this.priceRepository = priceRepository;
        this.tx = tx;
    }

    /**
     * 주문 생성: hold 검증(HELD·소유자·미만료) → 가격 스냅샷 → order(PENDING).
     *
     * 동시 생성의 최종 방어선은 부분 UNIQUE(uq_orders_active_hold)이고, 진 쪽은 기존 주문을 반환한다.
     * 확인한 제약이 아니면 원 예외를 올린다.
     *
     * 트랜잭션은 TransactionTemplate으로 연다. 제약 위반은 커밋에서 나므로 캐치가 경계 밖에 있어야
     * 하는데, 그 경계가 이 메서드의 알고리즘 자체다(ADR-019). NOT_SUPPORTED는 클래스의 readOnly
     * 트랜잭션과 호출자의 트랜잭션을 모두 끊어, 템플릿이 항상 새 경계를 열게 한다(TS-014).
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public OrderResponse create(Long userId, Long holdId) {
        try {
            return tx.execute(status -> createTx(userId, holdId));
        } catch (DataIntegrityViolationException e) {
            return orderRepository.findFirstByHoldIdAndStatusIn(holdId, ACTIVE)
                    .map(this::toResponse)
                    // 원 예외를 삼키고 BusinessException(INTERNAL_ERROR)로 바꾸면 스택이 사라진다:
                    // BusinessException은 전역 핸들러의 전용 분기가 먼저 잡아가 log.error를 타지 않아,
                    // 응답은 500인데 로그에는 아무 단서도 남지 않는다. 그대로 올려 원인을 남긴다.
                    .orElseThrow(() -> e);
        }
    }

    /** 생성 본문. 반드시 create()의 트랜잭션 경계 안에서 호출된다. */
    private OrderResponse createTx(Long userId, Long holdId) {
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
        if (hold.getExpiresAt().isBefore(LocalDateTime.now(clock))) {
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
                    .grade(s.getGrade()).price(priceMap.getOrDefault(s.getGrade(), 0))
                    // 좌석 위치도 주문 시점 값으로 굳힌다(가격과 같은 이유: ADR-004).
                    .seatRow(s.getSeatRow()).seatCol(s.getSeatCol()).build());
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
                .map(i -> new OrderItemResponse(i.getSeatId(), i.getGrade().name(), i.getPrice(),
                        i.getSeatRow(), i.getSeatCol()))
                .toList();
        return new OrderResponse(o.getId(), o.getEventId(), o.getStatus().name(),
                o.getAmount(), o.getExpiresAt(), items);
    }
}
