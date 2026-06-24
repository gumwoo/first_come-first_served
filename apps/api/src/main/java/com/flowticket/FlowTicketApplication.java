package com.flowticket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class FlowTicketApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlowTicketApplication.class, args);
    }
}
