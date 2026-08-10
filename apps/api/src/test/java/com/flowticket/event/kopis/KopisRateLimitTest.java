package com.flowticket.event.kopis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * KOPIS 이용 제한(<b>IP당 1초 10회, 초과 시 서비스 중지</b>)을 지키는지 검증한다.
 *
 * <p>이 제한을 몰라서 부하 테스트 중 상세 조회가 초당 70회 수준으로 나갔고 400 Request Blocked를
 * 2,014건 맞았다. 성능 문제이기 전에 <b>남의 서비스에 대한 문제</b>다.
 */
class KopisRateLimitTest {

    private static final String BASE_URL = "http://kopis.test";

    private static String listXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><dbs>"
                + "<db><mt20id>PF1</mt20id><prfnm>공연</prfnm></db></dbs>";
    }

    private record Fixture(KopisClient client, MockRestServiceServer server) {}

    /** permitsPerSecond를 직접 지정해 대기 시간을 짧게 만든 fixture. */
    private Fixture fixture(double permitsPerSecond) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        return new Fixture(
                new KopisClient(client, client, "test-key", new SimpleMeterRegistry(),
                        new KopisRateLimiter(permitsPerSecond)),
                server);
    }

    @Test
    void 목록조회는_설정한_간격을_지킨다() {
        // 20/s → 호출 간격 50ms. 3회면 최소 100ms(첫 호출은 즉시).
        Fixture f = fixture(20);
        for (int i = 0; i < 3; i++) {
            f.server().expect(requestTo(containsString("pblprfr")))
                    .andRespond(withSuccess(listXml().getBytes(StandardCharsets.UTF_8),
                            MediaType.APPLICATION_XML));
        }

        long start = System.nanoTime();
        for (int i = 0; i < 3; i++) {
            f.client().fetchList("20260101", "20260131", i + 1, 100);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        f.server().verify();
        assertThat(elapsedMs)
                .as("3회 호출이면 간격이 2번 들어가야 한다(50ms × 2)")
                .isGreaterThanOrEqualTo(100);
    }

    @Test
    void 상세조회는_레이트리밋에_걸리지_않는다() {
        // 사용자 요청 경로다. 여기서 스레드를 재우면 외부 지연이 톰캣 스레드를 묶어
        // API 전체가 멎는 실패를 방어 장치로 재현하게 된다 — 그래서 의도적으로 적용하지 않는다.
        // 극단적으로 느린 제한(0.1/s = 10초 간격)을 걸어도 상세 조회는 즉시 끝나야 한다.
        Fixture f = fixture(0.1);
        for (int i = 0; i < 3; i++) {
            f.server().expect(requestTo(containsString("pblprfr")))
                    .andRespond(withSuccess(listXml().getBytes(StandardCharsets.UTF_8),
                            MediaType.APPLICATION_XML));
        }

        long start = System.nanoTime();
        for (int i = 0; i < 3; i++) {
            f.client().fetchDetail("PF" + i);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        f.server().verify();
        assertThat(elapsedMs)
                .as("사용자 경로는 제한기를 타지 않아야 한다(10초 간격 설정에도 즉시 반환)")
                .isLessThan(3_000);
    }
}
