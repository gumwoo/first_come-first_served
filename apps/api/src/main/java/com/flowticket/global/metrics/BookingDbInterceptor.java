package com.flowticket.global.metrics;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** @BookingDbOp가 달린 핸들러를 처리하는 동안 요청 스레드에 동작 이름을 건다(BookingDbContext). */
public class BookingDbInterceptor implements HandlerInterceptor {

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
}
