package com.flowticket.event.kopis;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

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
    private final MeterRegistry meterRegistry;

    /** 외부 연동 지표 이름. Prometheus에서는 kopis_api_requests_seconds_{count,sum}이 된다. */
    private static final String METRIC = "kopis.api.requests";

    /**
     * RestClient는 {@link KopisClientConfig}가 만들어 넘긴다 — 이 클래스가 직접 빌드하지 않는다.
     * 타임아웃은 request factory에 붙는데, 그걸 여기서 설정하면 {@code MockRestServiceServer}가
     * 심어둔 mock factory를 덮어써 단위 테스트가 실제 네트워크로 나가버린다.
     */
    public KopisClient(@Qualifier("kopisDetailClient") RestClient detailClient,
                       @Qualifier("kopisSyncClient") RestClient syncClient,
                       @Value("${kopis.service-key:}") String serviceKey,
                       MeterRegistry meterRegistry) {
        this.detailClient = detailClient;
        this.syncClient = syncClient;
        this.serviceKey = serviceKey;
        this.meterRegistry = meterRegistry;
        this.xmlMapper = (XmlMapper) new XmlMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * 외부 호출을 계측한다 — 실패해도 예외를 밖으로 내지 않는 구조라 <b>지표가 없으면 보이지 않는다.</b>
     *
     * <p>실제로 그랬다: KOPIS가 모든 상세 조회에 400 Request Blocked를 돌려주고 있었는데,
     * 폴백이 정상 200으로 축약 응답을 내보내 사용자도 우리도 몰랐다. 발견한 경로는 대시보드에서
     * 이 엔드포인트만 p95가 높은 것을 보고 파고든 것이었다. 지표가 있었으면 실패율이 바로 보였다.
     *
     * <p>태그는 셋으로 제한한다(카디널리티). {@code status}는 HTTP 상태 코드거나, 응답 자체를
     * 받지 못한 경우 {@code "io"}다 — 400(차단)과 타임아웃을 구분해야 원인이 갈린다.
     */
    private <T> T recorded(String operation, Call<T> call, Function<T, String> outcomeOf)
            throws Exception {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        String status = "io";
        try {
            T result = call.get();
            outcome = outcomeOf.apply(result);
            status = "200";
            return result;
        } catch (RestClientResponseException e) {
            status = String.valueOf(e.getStatusCode().value()); // 400 등 — 응답은 왔다
            throw e;
        } finally {
            sample.stop(meterRegistry.timer(METRIC,
                    "operation", operation, "outcome", outcome, "status", status));
        }
    }

    /** XML 파싱이 checked 예외를 던지므로 Supplier로는 감쌀 수 없다. */
    @FunctionalInterface
    private interface Call<T> {
        T get() throws Exception;
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
            return recorded("list", () -> {
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
                    return Collections.<KopisEvent>emptyList();
                }
                KopisListResponse parsed = xmlMapper.readValue(xml, KopisListResponse.class);
                return parsed.items != null ? parsed.items : Collections.<KopisEvent>emptyList();
            }, result -> result.isEmpty() ? "empty" : "success");
        } catch (Exception e) {
            log.warn("[kopis] 목록 조회 실패: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 공연상세 조회(관람시간/연령/가격 등). 실패 시 empty. */
    public Optional<KopisEventDetail> fetchDetail(String kopisId) {
        try {
            return recorded("detail", () -> {
                byte[] xml = detailClient.get()
                        .uri(uriBuilder -> uriBuilder.path("/pblprfr/{id}")
                                .queryParam("service", serviceKey)
                                .build(kopisId))
                        .retrieve()
                        .body(byte[].class);
                if (xml == null || xml.length == 0) {
                    return Optional.<KopisEventDetail>empty();
                }
                KopisDetailResponse parsed = xmlMapper.readValue(xml, KopisDetailResponse.class);
                if (parsed.items == null || parsed.items.isEmpty()) {
                    return Optional.<KopisEventDetail>empty();
                }
                return Optional.of(parsed.items.get(0));
            }, result -> result.isPresent() ? "success" : "empty");
        } catch (Exception e) {
            log.warn("[kopis] 상세 조회 실패 id={}: {}", kopisId, e.getMessage());
            return Optional.empty();
        }
    }
}

