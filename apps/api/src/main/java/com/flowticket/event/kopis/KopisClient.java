package com.flowticket.event.kopis;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** KOPIS OpenAPI 호출 + XML 파싱. 외부 호출이므로 트랜잭션 경계 밖에서 사용한다. */
@Slf4j
@Component
public class KopisClient {

    private final RestClient restClient;
    private final String serviceKey;
    private final XmlMapper xmlMapper;

    public KopisClient(@Value("${kopis.base-url}") String baseUrl,
                       @Value("${kopis.service-key:}") String serviceKey) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.serviceKey = serviceKey;
        this.xmlMapper = (XmlMapper) new XmlMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /** 공연목록 조회(기간/페이지). 실패 시 빈 목록 반환(동기화는 best-effort). */
    public List<KopisEvent> fetchList(String stdate, String eddate, int cpage, int rows) {
        try {
            String xml = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/pblprfr")
                            .queryParam("service", serviceKey)
                            .queryParam("stdate", stdate)
                            .queryParam("eddate", eddate)
                            .queryParam("cpage", cpage)
                            .queryParam("rows", rows)
                            .build())
                    .retrieve()
                    .body(String.class);
            if (xml == null || xml.isBlank()) {
                return Collections.emptyList();
            }
            KopisListResponse parsed = xmlMapper.readValue(xml, KopisListResponse.class);
            return parsed.items != null ? parsed.items : Collections.emptyList();
        } catch (Exception e) {
            log.warn("[kopis] 목록 조회 실패: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
