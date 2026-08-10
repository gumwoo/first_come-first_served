package com.flowticket.event.kopis;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
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
    /**
     * <b>동기화 경로에만</b> 적용한다. 사용자 요청 경로(fetchDetail)에 쓰면 요청 스레드를 재우게 되어,
     * 외부 지연이 톰캣 스레드를 묶는 실패를 방어 장치로 재현하는 꼴이 된다({@link KopisRateLimiter}).
     */
    private final KopisRateLimiter rateLimiter;

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
                       MeterRegistry meterRegistry,
                       KopisRateLimiter rateLimiter) {
        this.detailClient = detailClient;
        this.syncClient = syncClient;
        this.serviceKey = serviceKey;
        this.meterRegistry = meterRegistry;
        this.rateLimiter = rateLimiter;
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
     * <p>태그는 셋으로 제한한다(카디널리티).
     * <pre>
     *   operation : detail | list
     *   outcome   : success | empty | error
     *   cause     : none | http_400 등 | timeout | io | parse | unknown
     * </pre>
     *
     * <p>{@code cause}를 <b>실패 종류</b>로 잡은 것이 핵심이다. 처음에는 이 자리에 HTTP 상태
     * 코드를 넣었는데, 그러면 <b>200을 정상 수신하고 XML 파싱에서 깨진 경우</b>가 "응답을 받지
     * 못함"으로 잘못 분류된다. 실패 원인을 가르려고 만든 지표가 원인을 뭉개면 의미가 없다.
     * 실패 지점마다 던지는 예외 타입이 다르므로 호출 구조를 바꾸지 않고 그것으로 구분한다.
     */
    private <T> T recorded(String operation, Call<T> call, Function<T, String> outcomeOf)
            throws Exception {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        String cause = "unknown";
        try {
            T result = call.get();
            outcome = outcomeOf.apply(result);
            cause = "none";
            return result;
        } catch (RestClientResponseException e) {
            cause = "http_" + e.getStatusCode().value(); // 응답은 왔다(400 차단 등)
            throw e;
        } catch (ResourceAccessException e) {
            cause = timedOut(e) ? "timeout" : "io"; // 응답 자체를 못 받음
            throw e;
        } catch (IOException e) {
            cause = "parse"; // HTTP는 성공했고 우리 XmlMapper가 실패
            throw e;
        } finally {
            sample.stop(meterRegistry.timer(METRIC,
                    "operation", operation, "outcome", outcome, "cause", cause));
        }
    }

    /**
     * 읽기/연결 타임아웃인지 판별한다. 예외 체인을 훑는 이유는 request factory에 따라
     * 감싸는 타입이 다르기 때문이다 — JDK HttpClient는 {@link HttpTimeoutException},
     * Simple/Apache 계열은 {@link SocketTimeoutException}을 싣는다.
     * (실제로 무엇이 실리는지는 KopisTimeoutTest가 실측으로 확인한다.)
     */
    private static boolean timedOut(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
                return true;
            }
            if (t.getCause() == t) {
                break; // 자기 참조 방어
            }
        }
        return false;
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

    /**
     * 공연목록 조회(기간/페이지). 실패 시 빈 목록 반환(동기화는 best-effort).
     *
     * <p><b>동기화 배치 전용</b>이므로 호출 전에 레이트 리밋을 통과한다. 90일을 31일 청크로
     * 나누면 청크 3개 × 최대 10페이지 = <b>최대 30회 연속 호출</b>이고, 간격 없이 쏘면
     * KOPIS의 IP 제한(1초 10회)을 넘길 수 있다.
     */
    public List<KopisEvent> fetchList(String stdate, String eddate, int cpage, int rows) {
        try {
            rateLimiter.acquire();
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
        } catch (InterruptedException e) {
            // 플래그를 복구해 인터럽트를 삼키지 않는다 — 삼키면 종료 신호가 사라진다.
            //
            // 다만 이것이 **배치 전체를 즉시 끝내지는 않는다.** 여기서 빈 목록을 돌려주면
            // fetchListAll의 페이지 루프만 끝나고, 상위 sync()는 다음 31일 청크로 넘어간다.
            // 플래그가 살아 있어 다음 acquire()에서 곧바로 다시 던지므로 실질적으로는 빠르게
            // 빠져나가지만, 구조적 보장은 아니다. upsert가 멱등이라 중간 종료가 데이터를
            // 망가뜨리지는 않는다.
            Thread.currentThread().interrupt();
            log.warn("[kopis] 목록 조회 중단(인터럽트)");
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn("[kopis] 목록 조회 실패: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 공연상세 조회(관람시간/연령/가격 등). 실패 시 empty.
     *
     * <p><b>이제 호출자는 동기화 배치 하나다.</b> 예전에는 사용자 요청 경로에서 불렸고, 그때는
     * 여기에 제한기를 걸 수 없었다 — 요청 스레드를 재우면 외부 지연이 톰캣 스레드를 묶어 API
     * 전체가 멎는 실패를 방어 장치로 재현하는 꼴이기 때문이다. 사용자 경로에서 호출을 걷어낸
     * 지금은 걸어야 한다. 걸지 않으면 상세 배치가 응답 속도만큼(약 80ms → 초당 12회) 나가
     * IP 제한(1초 10회)을 다시 넘긴다.
     *
     * <p>목록과 <b>같은 제한기 인스턴스</b>를 쓰므로 list + detail 합산이 설정값 이하로 유지된다.
     */
    public Optional<KopisEventDetail> fetchDetail(String kopisId) {
        try {
            rateLimiter.acquire();
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
        } catch (InterruptedException e) {
            // 목록 조회와 같은 이유로 삼키지 않는다 — 삼키면 종료 신호가 사라진다.
            // KopisDetailSyncer의 루프가 매 건 isInterrupted()를 보고 빠져나간다.
            Thread.currentThread().interrupt();
            log.warn("[kopis] 상세 조회 중단(인터럽트) id={}", kopisId);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("[kopis] 상세 조회 실패 id={}: {}", kopisId, e.getMessage());
            return Optional.empty();
        }
    }
}

