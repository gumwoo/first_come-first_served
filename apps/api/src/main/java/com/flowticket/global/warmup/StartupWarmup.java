package com.flowticket.global.warmup;

import com.flowticket.event.domain.Event;
import com.flowticket.event.repository.EventRepository;
import java.net.http.HttpClient;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 새 파드가 트래픽을 받기 전에 요청 경로를 미리 돌린다(A′ 개선안 — IMP-033 §6·§8, 기본 꺼짐).
 *
 * IMP-033 A′에서 폭증 중 HPA가 새로 띄운 파드가 예매 요청의 약 61%를 받았고, 그 파드들의 서버 처리 시간이 기존 파드보다
 * 길었다(실측 — 새 파드 197~1,289ms vs 기존 24~53ms). 원인이 "차가운 JIT"라는 것은 추론이다. 이 장치는 그 추론에 대응하는
 * 개선안이고, 효과는 A′(이 장치 켬) vs B′ 같은 조건 재비교로 판정한다 — 이것만으로 효과를 주장하지 않는다.
 *
 * 스프링 부트는 ApplicationRunner가 모두 끝난 뒤에 readiness를 ACCEPTING_TRAFFIC으로 바꾼다. 그래서 여기서 시간을 쓰는
 * 동안 /readyz는 503이고(K8s·ALB가 트래픽을 보내지 않는다), liveness는 이미 CORRECT라 startupProbe와 충돌하지 않는다.
 * 대가: 새 파드가 Ready가 되기까지 duration만큼 늦다 — 폭증 중 확장이 그만큼 늦게 효과를 낸다.
 *
 * 부작용이 없는 공개 조회만 부른다. 이벤트 상세(GET /events/{id})는 실시간 랭킹 조회수를 올려서 쓰지 않는다.
 * 예매 쓰기 경로는 실제 데이터를 바꾸므로 부르지 않는다 — 데워지는 것은 톰캣·스프링 MVC·보안 필터·잭슨·JPA 읽기·JDBC·Hikari다.
 * 워밍업 요청은 http.server.requests 지표에서 뺀다(WarmupObservationConfig) — 측정 결과를 오염시키지 않게.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "warmup.enabled", havingValue = "true")
public class StartupWarmup implements ApplicationRunner {

    /** 워밍업 요청 표시. 이 헤더가 붙은 요청은 지표에서 빠진다. */
    public static final String HEADER = "X-Flowticket-Warmup";

    private final Environment environment;
    private final EventRepository eventRepository;
    private final long durationMs;
    private final int threads;
    private volatile WarmupLoop.Result lastResult;

    public StartupWarmup(Environment environment,
                         EventRepository eventRepository,
                         @Value("${warmup.duration-ms:20000}") long durationMs,
                         @Value("${warmup.threads:2}") int threads) {
        this.environment = environment;
        this.eventRepository = eventRepository;
        this.durationMs = durationMs;
        this.threads = threads;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "8080"));
            // 루프백이라 짧게 준다. 하나가 막혀도 워밍업 전체(=readiness)가 무한히 늦어지지 않게(TS-028과 같은 이유).
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());
            factory.setReadTimeout(Duration.ofSeconds(3));
            RestClient client = RestClient.builder()
                    .baseUrl("http://127.0.0.1:" + port)
                    .requestFactory(factory)
                    .defaultHeader(HEADER, "1")
                    .build();
            Long eventId = eventRepository.findAll(PageRequest.of(0, 1)).stream()
                    .findFirst().map(Event::getId).orElse(null);

            lastResult = new WarmupLoop(() -> once(client, eventId), durationMs, threads, System::nanoTime).run();
            log.info("[warmup] 완료 — 요청 {}회·실패 {}회·{}ms(스레드 {}, 좌석맵 대상 공연 {})",
                    lastResult.requests(), lastResult.failures(), lastResult.elapsedMs(), threads, eventId);
        } catch (RuntimeException e) {
            log.warn("[warmup] 건너뜀 — 준비 중 실패: {}", e.toString());
        }
    }

    /** 마지막 워밍업 결과(없으면 null). 테스트와 진단용. */
    WarmupLoop.Result lastResult() {
        return lastResult;
    }

    private static boolean once(RestClient client, Long eventId) {
        boolean ok = client.get().uri("/events?page=0&size=20").retrieve().toBodilessEntity()
                .getStatusCode().is2xxSuccessful();
        if (eventId != null) {
            ok &= client.get().uri("/events/{id}/seats", eventId).retrieve().toBodilessEntity()
                    .getStatusCode().is2xxSuccessful();
        }
        return ok;
    }
}
