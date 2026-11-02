package com.flowticket.global.sse;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowticket.global.security.JwtProvider;
import com.flowticket.order.sse.OrderSseRegistry;
import com.flowticket.seat.sse.SeatSseRegistry;
import com.flowticket.support.IntegrationTestSupport;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 실제 서블릿 컨테이너에서 연결이 시간 초과로 끝나면 레지스트리 키도 사라지는지 본다.
 *
 * 없는 공연 ID로도 구독된다. 이벤트가 오지 않는 ID라 전달 실패로는 제거되지 않아, 시간 초과가 유일한 정리 경로다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "seat.sse-timeout-ms=2000")
class SseRegistryTimeoutIntegrationTest extends IntegrationTestSupport {

    @LocalServerPort int port;
    @Autowired JwtProvider jwtProvider;
    @Autowired SeatSseRegistry seats;
    @Autowired OrderSseRegistry orders;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void 시간_초과로_끝난_연결의_키가_남지_않는다() throws Exception {
        List<InputStream> streams = new ArrayList<>();
        try {
            for (long id = 9001; id <= 9003; id++) {
                streams.add(open("/sse/events/" + id + "/seats"));
                streams.add(open("/sse/orders/" + id + "?ticket=" + jwtProvider.createSseTicket(1L, id)));
            }
            assertThat(keys(seats, "byEvent")).containsKeys(9001L, 9002L, 9003L);
            assertThat(keys(orders, "byOrder")).containsKeys(9001L, 9002L, 9003L);

            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline
                    && !(keys(seats, "byEvent").isEmpty() && keys(orders, "byOrder").isEmpty())) {
                Thread.sleep(100);
            }

            assertThat(keys(seats, "byEvent")).isEmpty();
            assertThat(keys(orders, "byOrder")).isEmpty();
        } finally {
            for (InputStream stream : streams) {
                stream.close();
            }
        }
    }

    /** 초기 프레임까지 읽어 구독이 등록된 뒤에 돌려준다. */
    private InputStream open(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<InputStream> res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(res.statusCode()).isEqualTo(200);
        res.body().readNBytes(":open".length());
        return res.body();
    }

    private static Map<?, ?> keys(Object registry, String field) {
        return (Map<?, ?>) ReflectionTestUtils.getField(registry, field);
    }
}
