package com.flowticket.global.sse;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowticket.global.security.JwtProvider;
import com.flowticket.support.IntegrationTestSupport;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * TS-052 회귀: SSE 응답이 실제 HTTP 응답에서 Cache-Control: no-transform을 갖는가.
 *
 * Next(웹 서버)의 압축 미들웨어는 이 지시어가 있을 때만 SSE를 압축하지 않는다. 없으면 프레임이 압축
 * 버퍼에 갇혀 브라우저에 실시간으로 닿지 않는다. 컨트롤러에서 단 헤더를 Spring Security의 기본
 * 캐시 헤더(no-cache, no-store, ...)가 덮어쓰면 수정이 무효가 되므로, 단위 테스트가 아니라 필터 체인을
 * 전부 거친 실제 응답 헤더를 본다.
 *
 * 스트림은 끝나지 않으므로 헤더와 초기 프레임(:open)만 읽고 닫는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SseResponseHeaderIntegrationTest extends IntegrationTestSupport {

    @LocalServerPort int port;
    @Autowired JwtProvider jwtProvider;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void 주문_SSE_응답은_no_transform을_갖는다() throws Exception {
        long orderId = 4242L;
        String ticket = jwtProvider.createSseTicket(1L, orderId); // verify는 티켓만 대조한다(주문 행 불필요)

        assertNoTransform("/sse/orders/" + orderId + "?ticket=" + ticket);
    }

    @Test
    void 좌석_SSE_응답은_no_transform을_갖는다() throws Exception {
        assertNoTransform("/sse/events/4242/seats");
    }

    private void assertNoTransform(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<InputStream> res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = res.body()) {
            assertThat(res.statusCode()).isEqualTo(200);
            assertThat(res.headers().firstValue("Content-Type")).hasValueSatisfying(
                    ct -> assertThat(ct).startsWith("text/event-stream"));
            // 여러 줄로 오면 수신 측(Node)은 쉼표로 합쳐 본다 — 합친 값에 지시어가 있어야 한다.
            String cacheControl = String.join(", ", res.headers().allValues("Cache-Control"));
            assertThat(cacheControl).contains("no-transform");

            // 초기 프레임까지 실제로 흘러나오는지(응답이 커밋된 뒤의 헤더를 본 것인지) 확인한다.
            byte[] head = body.readNBytes(":open".length());
            assertThat(new String(head, StandardCharsets.UTF_8)).isEqualTo(":open");
        }
    }
}
