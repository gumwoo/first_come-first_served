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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
    private final ObjectProvider<OrderService> self; // 트랜잭션 프록시 self-호출용

    public OrderService(OrderRepository orderRepository, OrderItemRepository orderItemRepository,
                        SeatHoldRepository holdRepository, SeatHoldItemRepository holdItemRepository,
                        SeatRepository seatRepository, EventSeatPriceRepository priceRepository,
                        ObjectProvider<OrderService> self) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.holdRepository = holdRepository;
        this.holdItemRepository = holdItemRepository;
        this.seatRepository = seatRepository;
        this.priceRepository = priceRepository;
        this.self = self;
    }

    /**
     * 주문 생성: hold 검증(HELD·소유자·미만료) → 가격 스냅샷 → order(PENDING).
     * <b>같은 hold로는 활성 주문이 하나만 존재한다.</b>
     *
     * <p>앱의 "찾고 → 없으면 생성"은 <b>순차 더블 POST</b>만 막는다. 동시에 오면 둘 다 "없음"을
     * 보고 각자 INSERT하므로, 최종 방어선은 부분 UNIQUE 인덱스({@code uq_orders_active_hold})다.
     * 제약에 걸린 쪽은 <b>이미 다른 요청이 만든 주문</b>을 멱등하게 반환한다(결제·환불과 같은 형태).
     *
     * <p>캐치가 트랜잭션 <b>밖</b>에 있어야 한다. 안에서 잡으면 이미 rollback-only로 표시돼
     * 이어지는 조회·커밋이 실패한다.
     *
     * <p><b>활성 주문이 안 잡히면 우리가 아는 제약이 아니다</b> — NOT NULL·FK 같은 진짜 버그다.
     * 그때는 원 예외를 그대로 올린다. 가입({@code AuthService.duplicateOf})과 같은 규칙이다:
     * 제약 위반을 도메인 예외로 바꾸는 건 <b>정체를 확인한 것만</b>이고, 나머지는 손대지 않는다.
     *
     * <p>⚠️ {@code NOT_SUPPORTED}가 <b>반드시</b> 필요하다. 이 클래스에는 클래스 레벨
     * {@code @Transactional(readOnly = true)}가 붙어 있어, 메서드에 아무것도 없으면
     * <b>읽기 전용 트랜잭션이 이미 열린 상태</b>로 들어온다. 그러면 {@code createTx}의
     * {@code REQUIRED}가 새 트랜잭션을 만드는 대신 <b>그 읽기 전용 트랜잭션에 참여</b>해
     * (1) INSERT가 read-only 오류로 실패하고 (2) 경계가 분리되지 않아 캐치도 무의미해진다.
     * 초안이 그렇게 작성됐고 CI에서 44개 테스트가 깨져 드러났다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public OrderResponse create(Long userId, Long holdId) {
        try {
            return self.getObject().createTx(userId, holdId);
        } catch (DataIntegrityViolationException e) {
            return orderRepository.findFirstByHoldIdAndStatusIn(holdId, ACTIVE)
                    .map(this::toResponse)
                    // 원 예외를 삼키고 BusinessException(INTERNAL_ERROR)로 바꾸면 스택이 사라진다:
                    // BusinessException은 전역 핸들러의 전용 분기가 먼저 잡아가 log.error를 타지 않아,
                    // 응답은 500인데 로그에는 아무 단서도 남지 않는다. 그대로 올려 원인을 남긴다.
                    .orElseThrow(() -> e);
        }
    }

    /** 실제 생성 트랜잭션. 제약 위반은 {@link #create}가 밖에서 잡는다. */
    @Transactional
    public OrderResponse createTx(Long userId, Long holdId) {
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
                    .grade(s.getGrade()).price(priceMap.getOrDefault(s.getGrade(), 0))
                    // 좌석 위치도 주문 시점 값으로 굳힌다(가격과 같은 이유 — ADR-004).
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
