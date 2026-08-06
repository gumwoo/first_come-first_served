package com.flowticket.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowticket.order.domain.OrderStatus;
import com.flowticket.order.repository.OrderItemRepository;
import com.flowticket.order.repository.OrderRepository;
import com.flowticket.seat.repository.EventSeatPriceRepository;
import com.flowticket.seat.repository.SeatHoldItemRepository;
import com.flowticket.seat.repository.SeatHoldRepository;
import com.flowticket.seat.repository.SeatRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 주문 생성이 제약 위반을 <b>어디까지</b> 도메인 예외로 바꾸는지.
 *
 * <p>동시 생성이 하나로 수렴하는지는 실제 DB가 필요해 통합 테스트가 맡는다.
 * 여기서는 그 반대편, <b>우리가 모르는 제약 위반이 조용히 삼켜지지 않는지</b>를 결정적으로 본다.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock OrderRepository orderRepository;
    @Mock OrderItemRepository orderItemRepository;
    @Mock SeatHoldRepository holdRepository;
    @Mock SeatHoldItemRepository holdItemRepository;
    @Mock SeatRepository seatRepository;
    @Mock EventSeatPriceRepository priceRepository;
    @Mock ObjectProvider<OrderService> self;
    @InjectMocks OrderService orderService;

    /** 트랜잭션 경계를 나누는 프록시 self-호출 대상. 단위 테스트에는 프록시가 없어 목으로 세운다. */
    @Mock OrderService selfProxy;

    @Test
    void 우리가_아는_제약이_아니면_원_예외를_그대로_올린다() {
        // uq_orders_active_hold 위반이라면 "이미 만들어진 활성 주문"이 조회돼야 한다.
        // 조회가 비었다는 건 위반의 정체가 다른 것(NOT NULL·FK 등 진짜 버그)이라는 뜻이다.
        DataIntegrityViolationException cause =
                new DataIntegrityViolationException("null value in column \"amount\"");
        when(self.getObject()).thenReturn(selfProxy);
        when(selfProxy.createTx(anyLong(), anyLong())).thenThrow(cause);
        when(orderRepository.findFirstByHoldIdAndStatusIn(anyLong(), any()))
                .thenReturn(Optional.empty());

        // BusinessException(INTERNAL_ERROR)로 바꾸면 전역 핸들러의 전용 분기가 먼저 잡아
        // log.error를 타지 않는다 — 응답은 500인데 로그에 스택이 한 줄도 안 남는다.
        assertThatThrownBy(() -> orderService.create(7L, 42L))
                .isSameAs(cause);
    }

    @Test
    void 멱등_조회는_부분_UNIQUE_인덱스와_같은_상태집합을_본다() {
        // 인덱스는 PENDING·VBANK_WAITING에만 걸려 있다. 조회 집합이 그보다 넓으면
        // 만료·취소된 주문이 "이미 있는 주문"으로 잡혀 정당한 재주문이 막히고,
        // 좁으면 제약에 걸린 요청이 승자를 못 찾아 원 예외로 500이 나간다.
        DataIntegrityViolationException cause = new DataIntegrityViolationException("uq_orders_active_hold");
        when(self.getObject()).thenReturn(selfProxy);
        when(selfProxy.createTx(anyLong(), anyLong())).thenThrow(cause);
        when(orderRepository.findFirstByHoldIdAndStatusIn(anyLong(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.create(7L, 42L)).isSameAs(cause);

        ArgumentCaptor<List<OrderStatus>> captor = ArgumentCaptor.forClass(List.class);
        verify(orderRepository).findFirstByHoldIdAndStatusIn(anyLong(), captor.capture());
        assertThat(captor.getValue())
                .containsExactlyInAnyOrder(OrderStatus.PENDING, OrderStatus.VBANK_WAITING);
    }
}
