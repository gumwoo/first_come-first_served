package com.flowticket.global.config;

import com.flowticket.global.metrics.BookingDbInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 예매 경로 DB 계측(BookingDbMetrics)용 인터셉터 등록. @BookingDbOp가 없는 핸들러에서는 아무것도 하지 않는다. */
@Configuration
public class BookingDbMetricsWebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new BookingDbInterceptor());
    }
}
