package com.flowticket.global.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

/** 표시된 핸들러에서만 동작 이름을 걸고, 요청이 끝나면 반드시 지운다(톰캣 스레드 재사용). */
class BookingDbInterceptorTest {

    private final BookingDbInterceptor interceptor = new BookingDbInterceptor();
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void clear() {
        BookingDbContext.clear();
    }

    @Test
    void 표시된_핸들러는_동작_이름을_걸고_끝나면_지운다() throws Exception {
        HandlerMethod handler = new HandlerMethod(new Handlers(), "hold");

        interceptor.preHandle(request, response, handler);
        assertThat(BookingDbContext.current()).isEqualTo("hold");

        interceptor.afterCompletion(request, response, handler, null);
        assertThat(BookingDbContext.current()).isNull();
    }

    @Test
    void 표시가_없는_핸들러는_아무것도_걸지_않는다() throws Exception {
        interceptor.preHandle(request, response, new HandlerMethod(new Handlers(), "seats"));

        assertThat(BookingDbContext.current()).isNull();
    }

    @Test
    void 예외로_끝나도_지운다() throws Exception {
        HandlerMethod handler = new HandlerMethod(new Handlers(), "hold");
        interceptor.preHandle(request, response, handler);

        interceptor.afterCompletion(request, response, handler, new IllegalStateException("boom"));

        assertThat(BookingDbContext.current()).isNull();
    }

    static class Handlers {
        @BookingDbOp("hold")
        public void hold() {
        }

        public void seats() {
        }
    }
}
