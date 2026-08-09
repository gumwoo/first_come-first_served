package com.flowticket.event.kopis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * KOPIS XML 파싱 단위 테스트. MockRestServiceServer로 실제 HTTP 응답 바이트를 주입해
 * byte[] → XmlMapper(UTF-8) 전체 경로를 검증한다.
 * 특히 한글 인코딩 회귀(과거 String.class + ISO-8859-1로 한글이 깨진 버그)를 막는다.
 */
class KopisClientTest {

    private static final String BASE_URL = "http://kopis.test";

    /** 빌더 + 바인딩된 Mock 서버로 KopisClient를 구성한다. */
    private record Fixture(KopisClient client, MockRestServiceServer server, MeterRegistry meters) {}

    private Fixture fixture() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // detail·sync 두 경로 모두 같은 mock 서버로 검증한다(파싱 로직은 공통).
        // 운영에서는 KopisClientConfig가 타임아웃이 다른 RestClient 둘을 주입한다.
        RestClient client = builder.build();
        MeterRegistry meters = new SimpleMeterRegistry();
        return new Fixture(new KopisClient(client, client, "test-key", meters), server, meters);
    }

    @Test
    void fetchList_파싱하고_한글이_깨지지_않는다() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <dbs>
                  <db>
                    <mt20id>PF260001</mt20id>
                    <prfnm>2026 여름 콘서트</prfnm>
                    <fcltynm>올림픽홀</fcltynm>
                    <genrenm>대중음악</genrenm>
                    <poster>http://image/p.jpg</poster>
                    <prfpdfrom>2026.07.01</prfpdfrom>
                    <prfpdto>2026.07.03</prfpdto>
                    <prfstate>공연예정</prfstate>
                  </db>
                </dbs>
                """;
        Fixture f = fixture();
        f.server().expect(requestTo(containsString("pblprfr")))
                .andRespond(withSuccess(xml.getBytes(StandardCharsets.UTF_8), MediaType.APPLICATION_XML));

        List<KopisEvent> result = f.client().fetchList("20260701", "20260703", 1, 100);

        f.server().verify();
        assertThat(result).hasSize(1);
        KopisEvent e = result.get(0);
        assertThat(e.kopisId).isEqualTo("PF260001");
        assertThat(e.title).isEqualTo("2026 여름 콘서트"); // 한글 정상
        assertThat(e.venue).isEqualTo("올림픽홀");
        assertThat(e.genre).isEqualTo("대중음악");
    }

    @Test
    void fetchList_빈응답이면_빈목록() {
        Fixture f = fixture();
        f.server().expect(requestTo(containsString("pblprfr")))
                .andRespond(withSuccess(new byte[0], MediaType.APPLICATION_XML));

        assertThat(f.client().fetchList("20260701", "20260703", 1, 100)).isEmpty();
    }

    @Test
    void fetchDetail_파싱하고_한글_상세필드를_채운다() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <dbs>
                  <db>
                    <mt20id>PF260001</mt20id>
                    <prfruntime>1시간 30분</prfruntime>
                    <prfage>만 12세 이상</prfage>
                    <pcseguidance>전석 30,000원</pcseguidance>
                    <prfcast>가수 김플로우</prfcast>
                    <sty>여름밤의 콘서트</sty>
                    <dtguidance>매일 19시</dtguidance>
                  </db>
                </dbs>
                """;
        Fixture f = fixture();
        f.server().expect(requestTo(containsString("pblprfr")))
                .andRespond(withSuccess(xml.getBytes(StandardCharsets.UTF_8), MediaType.APPLICATION_XML));

        Optional<KopisEventDetail> result = f.client().fetchDetail("PF260001");

        f.server().verify();
        assertThat(result).isPresent();
        KopisEventDetail d = result.get();
        assertThat(d.runningTime).isEqualTo("1시간 30분");
        assertThat(d.priceText).isEqualTo("전석 30,000원");
        assertThat(d.cast).isEqualTo("가수 김플로우");
        assertThat(d.synopsis).isEqualTo("여름밤의 콘서트");
    }

    @Test
    void fetchDetail_빈응답이면_empty() {
        Fixture f = fixture();
        f.server().expect(requestTo(containsString("pblprfr")))
                .andRespond(withSuccess(new byte[0], MediaType.APPLICATION_XML));

        assertThat(f.client().fetchDetail("PF260001")).isEmpty();
    }

    @Test
    void fetchListAll_가득찬_페이지는_계속_부족하면_중단한다() {
        Fixture f = fixture();
        // page1: rows(2)만큼 가득 → 다음 페이지 요청, page2: 1건(부족) → 중단
        f.server().expect(requestTo(containsString("pblprfr")))
                .andRespond(withSuccess(listXml(2).getBytes(StandardCharsets.UTF_8), MediaType.APPLICATION_XML));
        f.server().expect(requestTo(containsString("pblprfr")))
                .andRespond(withSuccess(listXml(1).getBytes(StandardCharsets.UTF_8), MediaType.APPLICATION_XML));

        List<KopisEvent> result = f.client().fetchListAll("20260701", "20260731", 2, 10);

        f.server().verify(); // 정확히 2번 호출(3번째 없음)
        assertThat(result).hasSize(3);
    }

    /** db 항목 n개를 가진 KOPIS 목록 XML 생성. */
    private static String listXml(int n) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><dbs>");
        for (int i = 0; i < n; i++) {
            sb.append("<db><mt20id>PF").append(i).append("</mt20id><prfnm>공연").append(i).append("</prfnm></db>");
        }
        return sb.append("</dbs>").toString();
    }

    @Test
    void 외부호출_실패도_지표에_남는다() {
        // KOPIS가 400을 주면 폴백이 정상 200 축약 응답을 내보내 **로그 말고는 흔적이 없었다**.
        // 지표가 있어야 "우리 p95 상승"과 "외부 실패율 상승"을 나란히 놓고 원인을 좁힐 수 있다.
        Fixture f = fixture();
        f.server().expect(requestTo(containsString("pblprfr")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        Optional<KopisEventDetail> result = f.client().fetchDetail("PF294961");

        assertThat(result).isEmpty(); // 사용자에게는 그대로 degrade
        Timer timer = f.meters().find("kopis.api.requests")
                .tag("operation", "detail").tag("outcome", "error").tag("status", "400").timer();
        assertThat(timer).as("400 실패가 status=400 태그로 계측돼야 한다").isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void 외부호출_성공도_지표에_남는다() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <dbs><db><mt20id>PF260001</mt20id><prfnm>공연</prfnm></db></dbs>
                """;
        Fixture f = fixture();
        f.server().expect(requestTo(containsString("pblprfr")))
                .andRespond(withSuccess(xml.getBytes(StandardCharsets.UTF_8), MediaType.APPLICATION_XML));

        f.client().fetchDetail("PF260001");

        Timer timer = f.meters().find("kopis.api.requests")
                .tag("operation", "detail").tag("outcome", "success").tag("status", "200").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }
}
