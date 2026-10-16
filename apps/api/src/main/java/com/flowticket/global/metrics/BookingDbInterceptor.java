package com.flowticket.global.metrics;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

/** @BookingDbOp가 달린 핸들러를 처리하는 동안 요청 스레드에 동작 이름을 건다(BookingDbContext). */
public class BookingDbInterceptor implements AsyncHandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod method) {
            BookingDbOp op = method.getMethodAnnotation(BookingDbOp.class);
            if (op != null) {
                BookingDbContext.set(op.value());
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        BookingDbContext.clear();
    }

    /**
     * 비동기 처리로 넘어가면 처음 스레드에서는 afterCompletion이 불리지 않는다. 그 스레드는 풀로 돌아가므로 여기서 지운다.
     * 지금 표시된 핸들러는 모두 동기라 해당이 없지만, 비동기 핸들러에 표시가 붙어도 값이 다음 요청에 새지 않게 둔다.
     */
    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response,
                                               Object handler) {
        BookingDbContext.clear();
    }
}
