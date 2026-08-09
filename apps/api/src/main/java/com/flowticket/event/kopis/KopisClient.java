package com.flowticket.event.kopis;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** KOPIS OpenAPI 호출 + XML 파싱. 외부 호출이므로 트랜잭션 경계 밖에서 사용한다. */
@Slf4j
@Component
public class KopisClient {

    /**
     * 사용자 요청 경로(GET /events/{id})에서 쓰는 클라이언트. <b>짧게</b> 끊는다.
     * 상세 정보는 없어도 응답할 수 있으므로(폴백 존재), 기다리는 것보다 포기하는 편이 낫다.
     */
    private final RestClient detailClient;
    /**
     * 관리자 동기화 잡에서 쓰는 클라이언트. 사용자를 기다리게 하지 않으므로 더 너그럽게 준다.
     * 목록 조회는 rows=100까지 받아 상세 조회보다 오래 걸릴 수 있는데, 여기에 3초를 걸면
     * <b>지금 잘 돌던 시딩이 깨진다</b>(1,446건을 이 경로로 수집했다).
     */
    private final RestClient syncClient;
    private final String serviceKey;
    private final XmlMapper xmlMapper;

    /**
     * RestClient는 {@link KopisClientConfig}가 만들어 넘긴다 — 이 클래스가 직접 빌드하지 않는다.
     * 타임아웃은 request factory에 붙는데, 그걸 여기서 설정하면 {@code MockRestServiceServer}가
     * 심어둔 mock factory를 덮어써 단위 테스트가 실제 네트워크로 나가버린다.
     */
    public KopisClient(@Qualifier("kopisDetailClient") RestClient detailClient,
                       @Qualifier("kopisSyncClient") RestClient syncClient,
                       @Value("${kopis.service-key:}") String serviceKey) {
        this.detailClient = detailClient;
        this.syncClient = syncClient;
        this.serviceKey = serviceKey;
        this.xmlMapper = (XmlMapper) new XmlMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * 기간 내 공연을 페이지 끝까지 수집. 반환 건수가 rows 미만이면 마지막 페이지로 보고 중단.
     * maxPages로 페이지 수를 제한한다(무한 루프/과호출 방지). best-effort.
     */
    public List<KopisEvent> fetchListAll(String stdate, String eddate, int rows, int maxPages) {
        List<KopisEvent> all = new java.util.ArrayList<>();
        for (int page = 1; page <= maxPages; page++) {
            List<KopisEvent> batch = fetchList(stdate, eddate, page, rows);
            all.addAll(batch);
            if (batch.size() < rows) {
                break; // 마지막 페이지(가득 차지 않음) → 중단
            }
        }
        return all;
    }

    /** 공연목록 조회(기간/페이지). 실패 시 빈 목록 반환(동기화는 best-effort). */
    public List<KopisEvent> fetchList(String stdate, String eddate, int cpage, int rows) {
        try {
            // byte[]로 받아 XML 선언(UTF-8)을 XmlMapper가 직접 감지(String 변환 시 ISO-8859-1 깨짐 방지)
            byte[] xml = syncClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/pblprfr")
                            .queryParam("service", serviceKey)
                            .queryParam("stdate", stdate)
                            .queryParam("eddate", eddate)
                            .queryParam("cpage", cpage)
                            .queryParam("rows", rows)
                            .build())
                    .retrieve()
                    .body(byte[].class);
            if (xml == null || xml.length == 0) {
                return Collections.emptyList();
            }
            KopisListResponse parsed = xmlMapper.readValue(xml, KopisListResponse.class);
            return parsed.items != null ? parsed.items : Collections.emptyList();
        } catch (Exception e) {
            log.warn("[kopis] 목록 조회 실패: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 공연상세 조회(관람시간/연령/가격 등). 실패 시 empty. */
    public Optional<KopisEventDetail> fetchDetail(String kopisId) {
        try {
            byte[] xml = detailClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/pblprfr/{id}")
                            .queryParam("service", serviceKey)
                            .build(kopisId))
                    .retrieve()
                    .body(byte[].class);
            if (xml == null || xml.length == 0) {
                return Optional.empty();
            }
            KopisDetailResponse parsed = xmlMapper.readValue(xml, KopisDetailResponse.class);
            if (parsed.items == null || parsed.items.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(parsed.items.get(0));
        } catch (Exception e) {
            log.warn("[kopis] 상세 조회 실패 id={}: {}", kopisId, e.getMessage());
            return Optional.empty();
        }
    }
}

