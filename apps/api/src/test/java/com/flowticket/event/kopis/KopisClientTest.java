package com.flowticket.event.kopis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
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
    private record Fixture(KopisClient client, MockRestServiceServer server) {}

    private Fixture fixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KopisClient client = new KopisClient(builder, BASE_URL, "test-key");
        return new Fixture(client, server);
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
}
