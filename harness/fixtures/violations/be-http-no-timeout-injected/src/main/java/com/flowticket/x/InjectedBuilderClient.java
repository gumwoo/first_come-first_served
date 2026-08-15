package com.flowticket.x;

import org.springframework.web.client.RestClient;

/**
 * 위반 fixture ②: 주입받은 빌더를 쓰는 형태.
 *
 * 규칙 ⑱ 초안은 RestClient.builder()/create() 만 봐서 이 형태를 놓쳤다. 하필 TS-028의
 * 수정이 Toss를 이 형태로 바꿨기 때문에, 그 수정 이후 정작 그 파일이 규칙의 시야에서
 * 사라지는 상태였다(리뷰에서 잡힘).
 */
public class InjectedBuilderClient {

    private final RestClient client;

    public InjectedBuilderClient(RestClient.Builder builder) {
        // requestFactory 없음 → 타임아웃 없음 → 규칙 ⑱이 잡아야 한다.
        this.client = builder.clone()
                .baseUrl("https://example.com")
                .build();
    }
}
