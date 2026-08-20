package com.flowticket.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import com.flowticket.global.security.JwtProvider;
import com.flowticket.order.domain.Order;
import com.flowticket.order.repository.OrderRepository;
import com.flowticket.order.service.OrderSseTicketService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 주문 SSE 구독 인가. {@code /sse/orders/{id}}는 인가 없이 열려 있었고 {@code id}가 순차
 * 정수라 남의 주문 상태 변화를 관찰할 수 있었다 — 그 경로를 티켓으로 닫는다.
 *
 * <p>티켓이 막아야 하는 것은 셋이다: <b>티켓 없음</b>, <b>다른 종류의 토큰</b>,
 * <b>다른 주문의 티켓</b>. 마지막이 특히 중요하다 — 대조하지 않으면 자기 주문 티켓 하나로
 * 남의 주문을 구독할 수 있어 인가를 넣은 의미가 사라진다.
 */
class OrderSseTicketServiceTest {

    private static final long OWNER = 1L;
    private static final long STRANGER = 2L;
    private static final long MY_ORDER = 100L;
    private static final long OTHER_ORDER = 200L;

    private static final String SECRET = "order-sse-ticket-test-secret-0123456789-0123456789";

    private OrderRepository orderRepository;
    private JwtProvider jwtProvider;
    private OrderSseTicketService service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        jwtProvider = newProvider(300);
        service = new OrderSseTicketService(orderRepository, jwtProvider);
        givenOrder(MY_ORDER, OWNER);
        givenOrder(OTHER_ORDER, STRANGER);
    }

    // ── 발급 ────────────────────────────────────────────────

    @Test
    @DisplayName("남의 주문에는 티켓을 발급하지 않는다")
    void 남의_주문은_발급_거부() {
        assertThatThrownBy(() -> service.issue(STRANGER, MY_ORDER))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("없는 주문은 404")
    void 없는_주문() {
        when(orderRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issue(OWNER, 999L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_FOUND);
    }

    // ── 검증 ────────────────────────────────────────────────

    @Test
    @DisplayName("소유자가 받은 티켓은 자기 주문 구독에 통한다")
    void 정상_티켓은_통과() {
        String ticket = service.issue(OWNER, MY_ORDER);

        assertThatCode(() -> service.verify(ticket, MY_ORDER)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("티켓이 없으면 401 — EventSource가 재연결을 포기하도록")
    void 티켓_없음은_401() {
        assertThatThrownBy(() -> service.verify(null, MY_ORDER))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("다른 주문의 티켓으로는 구독할 수 없다 — 이걸 대조하지 않으면 인가가 무의미하다")
    void 다른_주문_티켓은_거부() {
        String otherTicket = service.issue(STRANGER, OTHER_ORDER);

        assertThatThrownBy(() -> service.verify(otherTicket, MY_ORDER))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("만료된 티켓은 401")
    void 만료_티켓은_401() {
        var expiredProvider = newProvider(-60); // 이미 지난 만료시각
        var expiredService = new OrderSseTicketService(orderRepository, expiredProvider);
        String expired = expiredService.issue(OWNER, MY_ORDER);

        assertThatThrownBy(() -> service.verify(expired, MY_ORDER))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("구독 티켓은 API 호출용 토큰으로 쓸 수 없다 — 용도가 분리돼야 URL 노출의 값이 낮아진다")
    void 티켓은_access_토큰이_아니다() {
        String ticket = service.issue(OWNER, MY_ORDER);

        assertThat(jwtProvider.isValid(ticket, JwtProvider.TYPE_ACCESS)).isFalse();
        assertThat(jwtProvider.isValid(ticket, JwtProvider.TYPE_REFRESH)).isFalse();
        assertThat(jwtProvider.isValid(ticket, JwtProvider.TYPE_SSE)).isTrue();
    }

    // ── 헬퍼 ────────────────────────────────────────────────

    /**
     * {@code init()}은 {@code @PostConstruct}라 패키지 밖에서 부를 수 없다. 테스트 때문에
     * 접근 범위를 넓히는 대신 리플렉션으로 부른다 — 프로덕션 API를 테스트 편의로 바꾸지 않는다.
     */
    private JwtProvider newProvider(long sseTtlSeconds) {
        var provider = new JwtProvider(SECRET, 1800, 3600, sseTtlSeconds);
        ReflectionTestUtils.invokeMethod(provider, "init");
        return provider;
    }

    private void givenOrder(long orderId, long userId) {
        Order order = mock(Order.class);
        when(order.getUserId()).thenReturn(userId);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
    }
}
