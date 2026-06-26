package com.flowticket.foo;

// VIOLATION: actuator 전체를 permitAll → 정보 노출. 하네스가 실패해야 함
public class OpenSecurityConfig {
    void config(Object http) {
        // requestMatchers("/actuator/**").permitAll()
        String path = "/actuator/**";
        boolean permitAll = true;
    }
}
