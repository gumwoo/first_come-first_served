package com.flowticket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 스케줄러 활성화는 {@link com.flowticket.global.config.SchedulingConfig}로 분리했다 — 테스트에서 꺼야 하기 때문. */
@SpringBootApplication
public class FlowTicketApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlowTicketApplication.class, args);
    }
}
