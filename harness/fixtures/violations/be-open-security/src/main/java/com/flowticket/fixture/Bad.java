package com.flowticket.fixture;

public class Bad {
    void configure(Object http) {
        // 위반: 전체 요청 개방
        // http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
        String cfg = "anyRequest().permitAll()";
    }
}
