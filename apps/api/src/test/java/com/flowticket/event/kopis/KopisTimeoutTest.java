package com.flowticket.event.kopis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 외부 API가 응답하지 않을 때 우리 스레드가 풀려나는지 검증한다(TS-028).
 *
 * 타임아웃이 없으면 응답 없는 KOPIS가 톰캣 스레드를 묶고, readiness는 UP이라 K8s가 빼주지 않는다.
 * MockRestServiceServer는 요청을 가로채 타임아웃 설정이 관여하지 않으므로, 받기만 하고
 * 응답하지 않는 소켓을 직접 띄운다.
 */
class KopisTimeoutTest {

    /** 연결은 받아주지만 응답을 절대 쓰지 않는 서버: "느린 외부 API"를 흉내낸다. */
    private ServerSocket silentServer;
    private ExecutorService accepter;

    @BeforeEach
    void startSilentServer() throws IOException {
        silentServer = new ServerSocket(0);
        accepter = Executors.newSingleThreadExecutor();
        accepter.submit(() -> {
            while (!silentServer.isClosed()) {
                try {
                    Socket s = silentServer.accept();
                    // 응답을 쓰지 않는다. 소켓은 열어둔 채 방치: read timeout이 유일한 탈출구다.
                    s.getInputStream().read();
                } catch (IOException ignored) {
                    return;
                }
            }
        });
    }

    @AfterEach
    void stop() throws IOException {
        silentServer.close();
        accepter.shutdownNow();
    }

    private MeterRegistry meters;

    private KopisClient clientPointingAtSilentServer() {
        String baseUrl = "http://localhost:" + silentServer.getLocalPort();
        KopisClientConfig config = new KopisClientConfig();
        meters = new SimpleMeterRegistry();
        return new KopisClient(
                config.kopisDetailClient(org.springframework.web.client.RestClient.builder(), baseUrl),
                config.kopisSyncClient(org.springframework.web.client.RestClient.builder(), baseUrl),
                "test-key",
                meters,
                new KopisRateLimiter(1000));
    }

    @Test
    void 상세조회는_응답없는_외부API에_묶이지_않는다() {
        KopisClient client = clientPointingAtSilentServer();

        // read timeout 3초 + 여유. 타임아웃이 없으면 여기서 영원히 멈춰 테스트가 죽는다.
        Optional<KopisEventDetail> result = assertTimeoutPreemptively(
                Duration.ofSeconds(15),
                () -> client.fetchDetail("PF000001"),
                "응답 없는 외부 API에 스레드가 묶였다. 타임아웃이 걸리지 않았다");

        // 던지지 않고 빈 값으로 degrade해야 한다(상세는 없어도 응답할 수 있다).
        assertThat(result).isEmpty();

        // 타임아웃이 cause=timeout으로 분류되는지 본다. request factory가 싣는 예외 타입은 여기서 판정한다.
        // io나 unknown으로 새면 원인 구분이 무의미해진다.
        Timer timer = meters.find("kopis.api.requests")
                .tag("operation", "detail").tag("outcome", "error").tag("cause", "timeout").timer();
        assertThat(timer)
                .as("읽기 타임아웃은 cause=timeout으로 계측돼야 한다(io/parse와 구분)")
                .isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void 목록조회도_응답없는_외부API에_묶이지_않는다() {
        KopisClient client = clientPointingAtSilentServer();

        List<KopisEvent> result = assertTimeoutPreemptively(
                Duration.ofSeconds(25), // sync는 read timeout 10초라 여유를 더 준다
                () -> client.fetchList("20260101", "20260102", 1, 100),
                "응답 없는 외부 API에 동기화 스레드가 묶였다");

        assertThat(result).isEmpty();
    }
}
