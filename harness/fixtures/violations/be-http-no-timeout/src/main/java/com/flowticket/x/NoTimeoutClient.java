package com.flowticket.x;

import org.springframework.web.client.RestClient;

/** 위반 fixture: RestClient를 만들면서 requestFactory(타임아웃)를 주지 않는다. */
public class NoTimeoutClient {

    private final RestClient client;

    public NoTimeoutClient() {
        // 기본 타임아웃이 없어 무한 대기가 된다 → 규칙 ⑱이 잡아야 한다.
        this.client = RestClient.builder().baseUrl("https://example.com").build();
    }
}
