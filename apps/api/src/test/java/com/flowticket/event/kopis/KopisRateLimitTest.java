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
    void 상세조회도_같은_제한기를_통과한다() {
        // ⚠️ 이 단언은 예전에 **정반대**였다. 상세 조회가 사용자 요청 경로에 있던 시절에는
        // 여기에 제한기를 걸 수 없었다 — 요청 스레드를 재우면 외부 지연이 톰캣 스레드를 묶어
        // API 전체가 멎는 실패를 방어 장치로 재현하게 되기 때문이다.
        //
        // 상세 조회를 동기화 배치로 옮기면서 전제가 바뀌었다. 이제 호출자가 배치 하나뿐이라
        // 블로킹이 타당하고, 오히려 걸지 않으면 배치가 응답 속도만큼(약 80ms → 초당 12회)
        // 나가 IP 제한(1초 10회)을 넘긴다.
        //
        // "사용자 경로에 외부 호출이 없다"는 보호는 EventDetailNoExternalCallTest가 맡는다.
        Fixture f = fixture(20); // 50ms 간격
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
                .as("상세도 제한기를 타야 한다(50ms × 2)")
                .isGreaterThanOrEqualTo(100);
    }

    @Test
    void 목록과_상세가_같은_제한기를_공유한다() {
        // 둘이 별도 제한기를 쓰면 합산이 설정값의 2배가 되어 IP 제한을 넘긴다.
        // 같은 인스턴스를 통과하는지 확인한다 — 목록 1 + 상세 1이면 간격이 1번 들어가야 한다.
        Fixture f = fixture(10); // 100ms 간격
        f.server().expect(requestTo(containsString("pblprfr")))
                .andRespond(withSuccess(listXml().getBytes(StandardCharsets.UTF_8),
                        MediaType.APPLICATION_XML));
        f.server().expect(requestTo(containsString("pblprfr")))
                .andRespond(withSuccess(listXml().getBytes(StandardCharsets.UTF_8),
                        MediaType.APPLICATION_XML));

        long start = System.nanoTime();
        f.client().fetchList("20260101", "20260131", 1, 100);
        f.client().fetchDetail("PF1");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        f.server().verify();
        assertThat(elapsedMs)
                .as("목록과 상세가 같은 제한기를 공유해야 한다(100ms 간격 1회)")
                .isGreaterThanOrEqualTo(100);
    }
}
